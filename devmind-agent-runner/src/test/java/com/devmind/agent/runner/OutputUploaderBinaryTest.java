package com.devmind.agent.runner;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-83 两路回传：严格 UTF-8 解码失败 = 二进制产出（xlsx 等），走独立裸字节通道
 * {@code POST /api/agent/output/{sessionId}/attachment?name=...}；文本产出仍走既有 JSON 通道。
 */
class OutputUploaderBinaryTest {

    /** 一次请求的抓包记录：rawPath 含 query，body 为原始字节。 */
    record Capture(String rawPath, String contentType, byte[] body) {
    }

    @Test
    void 二进制文件走附件通道裸字节上传() throws Exception {
        Path dir = Files.createTempDirectory("uploader-bin");
        Path out = Files.createDirectories(dir.resolve(".devmind/output"));
        // 非法 UTF-8 字节序列（0xFF 起头）= 二进制产出
        byte[] bin = {(byte) 0xFF, (byte) 0xD8, 0x00, 0x01, (byte) 0xFE, 0x42};
        Files.write(out.resolve("指标清单.xlsx"), bin);

        var caps = new CopyOnWriteArrayList<Capture>();
        HttpServer srv = serve(caps);
        try {
            String err = OutputUploader.upload(configPointingAt(srv), "s1", dir);
            assertNull(err, "回传应成功: " + err);
            assertEquals(1, caps.size(), "应只发一次附件请求");
            Capture c = caps.get(0);
            assertTrue(c.rawPath().startsWith("/api/agent/output/s1/attachment?"), "应走附件通道: " + c.rawPath());
            assertTrue(c.rawPath().contains("name="), "query 应带文件名: " + c.rawPath());
            assertTrue(c.rawPath().contains("token=tok"), "query 应带 token: " + c.rawPath());
            assertEquals("application/octet-stream", c.contentType(), "请求头应为裸字节流");
            org.junit.jupiter.api.Assertions.assertArrayEquals(bin, c.body(), "body 应为原始字节");
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void 文本与二进制混合时各走各的通道() throws Exception {
        Path dir = Files.createTempDirectory("uploader-mix");
        Path out = Files.createDirectories(dir.resolve(".devmind/output"));
        Files.writeString(out.resolve("analysis.md"), "# 分析", StandardCharsets.UTF_8);
        byte[] bin = {(byte) 0x89, 0x50, 0x4E, 0x47, (byte) 0xC0}; // PNG 魔数 + 非法 UTF-8
        Files.write(out.resolve("chart.png"), bin);

        var caps = new CopyOnWriteArrayList<Capture>();
        HttpServer srv = serve(caps);
        try {
            String err = OutputUploader.upload(configPointingAt(srv), "s1", dir);
            assertNull(err, "回传应成功: " + err);
            assertEquals(2, caps.size(), "文本一次 JSON + 二进制一次裸字节: " + caps);
            Capture json = caps.stream().filter(c -> !c.rawPath().contains("/attachment?")).findFirst().orElseThrow();
            assertTrue(new String(json.body(), StandardCharsets.UTF_8).contains("analysis.md"),
                    "文本文件应走 JSON 通道");
            Capture attach = caps.stream().filter(c -> c.rawPath().contains("/attachment?")).findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertArrayEquals(bin, attach.body());
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void 空字节文件按文本走原通道() throws Exception {
        Path dir = Files.createTempDirectory("uploader-empty");
        Path out = Files.createDirectories(dir.resolve(".devmind/output"));
        Files.write(out.resolve("empty.log"), new byte[0]);

        var caps = new CopyOnWriteArrayList<Capture>();
        HttpServer srv = serve(caps);
        try {
            String err = OutputUploader.upload(configPointingAt(srv), "s1", dir);
            assertNull(err, "回传应成功: " + err);
            assertEquals(1, caps.size());
            assertTrue(caps.get(0).rawPath().endsWith("token=tok"),
                    "空文件 UTF-8 解码成功应按文本走 JSON 通道: " + caps.get(0).rawPath());
        } finally {
            srv.stop(0);
        }
    }

    private RunnerConfig configPointingAt(HttpServer srv) {
        return new RunnerConfig("ws://127.0.0.1:" + srv.getAddress().getPort() + "/ws/agent",
                "tok", "", "acceptEdits", Path.of("."), Map.of(), 4, "claude", Path.of("."));
    }

    private HttpServer serve(CopyOnWriteArrayList<Capture> caps) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/api/agent/output/", ex -> {
            caps.add(new Capture(ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()),
                    ex.getRequestHeaders().getFirst("Content-Type"),
                    ex.getRequestBody().readAllBytes()));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        srv.start();
        return srv;
    }
}
