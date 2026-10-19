package com.devmind.agent.runner;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OutputUploader#upload(RunnerConfig, String, Path, Instant)} 的会话归属过滤：
 * worklog 共享草稿区（{worklogRoot}/&lt;owner&gt;/ 跨会话复用且永不删除）只回传 notBefore
 * 之后写出的文件（= 本会话产出），历史旧稿不再混入回传（"一推好多天"的根因修复）。
 */
class OutputUploaderCutoffTest {

    @Test
    void 只回传截止点之后写出的文件() throws Exception {
        Path dir = Files.createTempDirectory("uploader-cutoff");
        Path out = Files.createDirectories(dir.resolve(".devmind/output"));
        Files.writeString(out.resolve("daily-2026-10-18.md"), "昨天的旧稿");
        Files.writeString(out.resolve("daily-2026-10-19.md"), "本会话新稿");
        // 旧稿 mtime 拨回 2 小时前（上个会话的产出）；新稿保持当前时刻
        Files.setLastModifiedTime(out.resolve("daily-2026-10-18.md"),
                java.nio.file.attribute.FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS)));

        var bodies = new CopyOnWriteArrayList<String>();
        HttpServer srv = serve(bodies);
        try {
            RunnerConfig config = configPointingAt(srv);
            String err = OutputUploader.upload(config, "s1", dir,
                    Instant.now().minus(10, ChronoUnit.MINUTES));
            assertNull(err, "回传应成功: " + err);
            assertTrue(bodies.size() == 1, "应只发一次请求");
            assertTrue(bodies.get(0).contains("daily-2026-10-19.md"), "新稿必须回传: " + bodies.get(0));
            assertFalse(bodies.get(0).contains("daily-2026-10-18.md"), "旧稿不得混入: " + bodies.get(0));
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void 无截止点不过滤保持旧行为() throws Exception {
        Path dir = Files.createTempDirectory("uploader-nocutoff");
        Path out = Files.createDirectories(dir.resolve(".devmind/output"));
        Files.writeString(out.resolve("a.md"), "x");
        Files.setLastModifiedTime(out.resolve("a.md"),
                java.nio.file.attribute.FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS)));

        var bodies = new CopyOnWriteArrayList<String>();
        HttpServer srv = serve(bodies);
        try {
            String err = OutputUploader.upload(configPointingAt(srv), "s1", dir);
            assertNull(err, "回传应成功: " + err);
            assertTrue(bodies.get(0).contains("a.md"), "不过滤时旧文件照常回传");
        } finally {
            srv.stop(0);
        }
    }

    private RunnerConfig configPointingAt(HttpServer srv) {
        return new RunnerConfig("ws://127.0.0.1:" + srv.getAddress().getPort() + "/ws/agent",
                "tok", "", "acceptEdits", Path.of("."), Map.of(), 4, "claude", Path.of("."));
    }

    private HttpServer serve(CopyOnWriteArrayList<String> bodies) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/api/agent/output/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        srv.start();
        return srv;
    }
}
