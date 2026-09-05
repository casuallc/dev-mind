package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RunnerUpgrader：下载 URL 派生 / sha256 校验 / currentJar 非 jar 语义。 */
class RunnerUpgraderTest {

    private static RunnerConfig config(String serverUrl) {
        return new RunnerConfig(serverUrl, "dmag_test token", "", "acceptEdits",
                Path.of("."), Map.of(), 4, "claude", Path.of("./workspaces"));
    }

    @Test
    void derivesHttpDownloadUrlFromWs() {
        String url = RunnerUpgrader.downloadUrl(config("ws://172.20.140.224:8080/ws/agent"));
        assertTrue(url.startsWith("http://172.20.140.224:8080/api/agent-nodes/runner-package/download?token="),
                url);
        assertTrue(url.contains("dmag_test+token") || url.contains("dmag_test%20token"), url);
    }

    @Test
    void derivesHttpsFromWss() {
        String url = RunnerUpgrader.downloadUrl(config("wss://devmind.example.com/ws/agent"));
        assertTrue(url.startsWith("https://devmind.example.com/api/agent-nodes/runner-package/download"), url);
    }

    /** 起 JDK 内置 HttpServer 伺候指定字节，返回 base URL（无尾部斜杠）。 */
    private static com.sun.net.httpserver.HttpServer serve(byte[] content) throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dl", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void downloadAndVerifyOk(@TempDir Path dir) throws Exception {
        byte[] content = "fake-jar-bytes".getBytes();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var server = serve(content);
        try {
            Path dst = dir.resolve("sub").resolve("runner.jar.new");
            RunnerUpgrader.downloadAndVerify(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/dl", sha, dst);
            assertEquals(content.length, Files.size(dst));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void checksumMismatchDeletesResidue(@TempDir Path dir) throws Exception {
        var server = serve("abc".getBytes());
        try {
            Path dst = dir.resolve("runner.jar.new");
            assertThrows(Exception.class, () -> RunnerUpgrader.downloadAndVerify(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/dl", "00".repeat(32), dst));
            assertFalse(Files.exists(dst), "校验失败应删除 .new 残渣");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpErrorRejected(@TempDir Path dir) throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dl", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            Path dst = dir.resolve("runner.jar.new");
            assertThrows(Exception.class, () -> RunnerUpgrader.downloadAndVerify(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/dl", "x", dst));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void currentJarEmptyOutsideJarLaunch() {
        // 单测从 target/classes 目录启动，CodeSource 不是 jar → empty（无法自升级，符合预期）
        assertTrue(RunnerUpgrader.currentJar().isEmpty());
    }
}
