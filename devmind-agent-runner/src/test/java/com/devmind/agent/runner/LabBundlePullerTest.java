package com.devmind.agent.runner;

import com.devmind.common.decision.LabBundles;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-56 {@link LabBundlePuller}：拉包 → 读清单 → 解包 → 返回入口脚本与数据绝对路径；
 * HTTP 错误、空包、清单缺入口、包内缺文件一律 IOException（调用方据此让 exec 失败，不降级）。
 */
class LabBundlePullerTest {

    private static final byte[] SCRIPT = "print('eval')\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PAYLOAD = "{\"items\":[1]}".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private RunnerConfig configPointingAt(HttpServer srv) {
        return new RunnerConfig("ws://127.0.0.1:" + srv.getAddress().getPort() + "/ws/agent",
                "tok", "", "acceptEdits", Path.of("."), Map.of(), 4, "claude", Path.of("."));
    }

    private HttpServer serve(int status, byte[] body, String disposition) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/api/agent/decision-lab/bundles/", ex -> {
            if (disposition != null) {
                ex.getResponseHeaders().add("Content-Disposition", disposition);
            }
            ex.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) {
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        srv.start();
        return srv;
    }

    private static byte[] bundleZip(String entry, Map<String, byte[]> files, byte[] payload) {
        Map<String, byte[]> all = new LinkedHashMap<>(files);
        return LabBundles.pack(new LabBundles.Manifest(entry, "payload.json"), all, payload);
    }

    @Test
    void pullsExtractsAndExposesPaths() throws Exception {
        server = serve(200, bundleZip("laya_eval.py", Map.of("laya_eval.py", SCRIPT), PAYLOAD), null);
        LabBundlePuller.Materialized m = LabBundlePuller.pull(configPointingAt(server), "evaluation", "e1");
        try {
            assertTrue(Files.isRegularFile(m.script()));
            assertTrue(Files.isRegularFile(m.payload()));
            assertEquals("laya_eval.py", m.script().getFileName().toString());
            assertEquals(new String(PAYLOAD, StandardCharsets.UTF_8),
                    Files.readString(m.payload(), StandardCharsets.UTF_8));
            // 默认包名（服务端没给 Content-Disposition 时）也要有值：日志里要能说清拉了哪个包
            assertEquals("evaluation-e1.zip", m.fileName());
        } finally {
            LabBundlePuller.deleteQuietly(m.dir());
        }
        assertFalse(Files.exists(m.dir()), "用完即弃：临时目录必须删掉");
    }

    @Test
    void usesContentDispositionFileName() throws Exception {
        // 服务端把包名安全化成纯 ASCII 后才进响应头（HTTP 头是 latin-1），这里用它的实际输出形态
        server = serve(200, bundleZip("laya_eval.py", Map.of("laya_eval.py", SCRIPT), PAYLOAD),
                "attachment; filename=\"baseline_v1.zip\"");
        LabBundlePuller.Materialized m = LabBundlePuller.pull(configPointingAt(server), "evaluation", "e1");
        try {
            assertEquals("baseline_v1.zip", m.fileName());
        } finally {
            LabBundlePuller.deleteQuietly(m.dir());
        }
    }

    @Test
    void httpErrorFails() throws Exception {
        server = serve(404, new byte[0], null);
        IOException e = assertThrows(IOException.class,
                () -> LabBundlePuller.pull(configPointingAt(server), "evaluation", "e1"));
        assertTrue(e.getMessage().contains("404"), e.getMessage());
    }

    @Test
    void emptyBodyFails() throws Exception {
        server = serve(200, new byte[0], null);
        assertThrows(IOException.class,
                () -> LabBundlePuller.pull(configPointingAt(server), "evaluation", "e1"));
    }

    @Test
    void missingEntryScriptFails() throws Exception {
        // 清单说入口是 a.py，包里只有 b.py：解包后立刻失败，不让脚本跑到一半才说找不到
        byte[] zip = bundleZip("a.py", Map.of("b.py", SCRIPT), PAYLOAD);
        server = serve(200, zip, null);
        IOException e = assertThrows(IOException.class,
                () -> LabBundlePuller.pull(configPointingAt(server), "evaluation", "e1"));
        assertTrue(e.getMessage().contains("a.py"), e.getMessage());
    }

    @Test
    void missingPayloadFails() throws Exception {
        // 清单声明 payloadName=missing.json，包里实际没有它（手工组包：pack 总会写一份数据）
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(LabBundles.MANIFEST_NAME));
            zip.write("{\"entry\":\"laya_eval.py\",\"payloadName\":\"missing.json\"}"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("laya_eval.py"));
            zip.write(SCRIPT);
            zip.closeEntry();
        }
        server = serve(200, out.toByteArray(), null);
        IOException e = assertThrows(IOException.class,
                () -> LabBundlePuller.pull(configPointingAt(server), "evaluation", "e1"));
        assertTrue(e.getMessage().contains("数据文件"), e.getMessage());
    }

    @Test
    void deleteQuietlyToleratesMissingDirAndNull() {
        LabBundlePuller.deleteQuietly(null);
        LabBundlePuller.deleteQuietly(Path.of("no-such-dir-9f3a1c"));
    }
}
