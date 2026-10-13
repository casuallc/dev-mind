package com.devmind.agent.runner;

import com.devmind.common.agent.exec.GitProxyBroker;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CAP-58 FR-06 {@link GitAuthProxy} 链路面（假上游 HttpServer）：
 * key→凭据查表注入 Authorization、query/POST body/协议头透传、响应流式回写、
 * 未知或已吊销 key → 404。
 */
class GitAuthProxyTest {

    private HttpServer upstream;
    private GitAuthProxy proxy;
    private GitProxyBroker broker;
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private final AtomicReference<String> seenQuery = new AtomicReference<>();
    private final AtomicReference<String> seenGitProtocol = new AtomicReference<>();
    private final AtomicReference<byte[]> seenBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.createContext("/", ex -> {
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            seenQuery.set(ex.getRequestURI().getRawQuery());
            seenGitProtocol.set(ex.getRequestHeaders().getFirst("Git-Protocol"));
            byte[] req = ex.getRequestBody().readAllBytes();
            seenBody.set(req);
            byte[] resp = "POST".equals(ex.getRequestMethod()) ? req
                    : "refs-data".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("X-Upstream", "yes");
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        upstream.start();
        String upstreamOrigin = "http://127.0.0.1:" + upstream.getAddress().getPort();
        broker = new GitProxyBroker("http://127.0.0.1:0/git");
        broker.register("k1", upstreamOrigin + "/group/repo.git", "pat-123");
        RunnerConfig config = new RunnerConfig("ws://127.0.0.1:8080/ws/agent", "dmag_t", "",
                "acceptEdits", Path.of("."), Map.of(), 1, "fake", Path.of("."));
        proxy = new GitAuthProxy(broker, config);
        proxy.start(0);
    }

    @AfterEach
    void tearDown() {
        proxy.stop();
        upstream.stop(0);
    }

    private HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    private String url(String key, String rest) {
        return "http://127.0.0.1:" + proxy.port() + "/git/" + key + rest;
    }

    @Test
    void getInjectsAuthAndPassesQuery() throws Exception {
        HttpResponse<String> resp = client().send(
                HttpRequest.newBuilder(URI.create(url("k1", "/group/repo.git/info/refs?service=git-upload-pack")))
                        .header("Git-Protocol", "version=2").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertEquals("refs-data", resp.body());
        assertEquals("yes", resp.headers().firstValue("X-Upstream").orElse(""));
        assertEquals("Basic " + Base64.getEncoder().encodeToString(
                "oauth2:pat-123".getBytes(StandardCharsets.UTF_8)), seenAuth.get());
        assertEquals("service=git-upload-pack", seenQuery.get());
        assertEquals("version=2", seenGitProtocol.get());
    }

    @Test
    void postBodyForwardedByteExact() throws Exception {
        byte[] body = new byte[4096];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i % 251); // 含 0 字节的伪二进制（pack 流形态）
        }
        HttpResponse<byte[]> resp = client().send(
                HttpRequest.newBuilder(URI.create(url("k1", "/group/repo.git/git-upload-pack")))
                        .header("Content-Type", "application/x-git-upload-pack-request")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, resp.statusCode());
        assertArrayEquals(body, seenBody.get(), "上行 body 必须字节无损");
        assertArrayEquals(body, resp.body(), "响应 body 必须字节无损");
    }

    @Test
    void unknownOrRevokedKeyIs404() throws Exception {
        assertEquals(404, client().send(
                HttpRequest.newBuilder(URI.create(url("nope", "/group/repo.git/info/refs"))).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        // 会话收口/释放吊销后：同 key 立即 404（agent 看到清晰失败而非挂起）
        broker.revoke("k1");
        assertEquals(404, client().send(
                HttpRequest.newBuilder(URI.create(url("k1", "/group/repo.git/info/refs"))).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertNull(seenAuth.get(), "404 请求不得触达上游");
    }
}
