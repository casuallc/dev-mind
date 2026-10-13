package com.devmind.agent.runner;

import com.devmind.common.agent.exec.GitProxyBroker;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-58 FR-06 全链路面：真实 git 客户端 → {@link GitAuthProxy} → 真实 smart-HTTP 上游
 * （{@code git http-backend} CGI 桥，强制校验 Authorization）。覆盖 ls-remote / clone /
 * push（receive-pack POST 流式上行）三条 git 协议路径，并验证上游无凭据必 401
 * （即凭据确实来自代理注入而非 git 客户端自带）。
 */
class GitAuthProxyRealGitTest {

    private static final String TOKEN = "tok-123";
    private static final String EXPECTED_AUTH = "Basic " + Base64.getEncoder()
            .encodeToString(("oauth2:" + TOKEN).getBytes(StandardCharsets.UTF_8));

    @TempDir
    Path tmp;

    private HttpServer upstream;
    private GitAuthProxy proxy;
    private String gitExecPath;

    @BeforeEach
    void setUp() throws Exception {
        gitExecPath = git(tmp, "--exec-path").trim();
        // 远端：bare 库 + 一笔初始提交
        Path remote = tmp.resolve("repo.git");
        git(tmp, "init", "--bare", "-b", "main", remote.toString());
        Path seed = tmp.resolve("seed");
        git(tmp, "clone", remote.toString(), seed.toString());
        Files.writeString(seed.resolve("README.md"), "hello");
        git(seed, "add", ".");
        git(seed, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init");
        git(seed, "push", "origin", "main");

        // 上游 smart-HTTP（git http-backend CGI 桥），无 Authorization 一律 401
        upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.createContext("/", this::cgi);
        upstream.start();
        String upstreamOrigin = "http://127.0.0.1:" + upstream.getAddress().getPort();

        GitProxyBroker broker = new GitProxyBroker("http://127.0.0.1:0/git");
        broker.register("k1", upstreamOrigin + "/repo.git", TOKEN);
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

    private String proxyRepoUrl() {
        return "http://127.0.0.1:" + proxy.port() + "/git/k1/repo.git";
    }

    @Test
    void lsRemoteAndCloneThroughProxy() throws Exception {
        // 真实 git 客户端裸跑（URL 无凭据、GIT_TERMINAL_PROMPT=0）——能成 = 代理注入生效
        String out = git(tmp, "ls-remote", proxyRepoUrl());
        assertTrue(out.contains("refs/heads/main"), out);

        Path clone = tmp.resolve("clone1");
        git(tmp, "clone", proxyRepoUrl(), clone.toString());
        assertEquals("hello", Files.readString(clone.resolve("README.md")));
    }

    @Test
    void pushThroughProxy() throws Exception {
        // receive-pack 路径：POST 大 body 流式上行 + 鉴权注入
        Path clone = tmp.resolve("clone2");
        git(tmp, "clone", proxyRepoUrl(), clone.toString());
        Files.writeString(clone.resolve("new.txt"), "pushed-by-agent");
        git(clone, "add", ".");
        git(clone, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "work");
        git(clone, "push", "origin", "main");
        // 远端真收到了这笔提交
        String log = git(tmp.resolve("repo.git"), "log", "--oneline", "main");
        assertTrue(log.contains("work"), log);
    }

    @Test
    void upstreamRejectsMissingAuth() throws Exception {
        // 对照组：直连上游不带 Authorization → 401（凭据只能来自代理）
        HttpResponse<String> resp = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).build().send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://127.0.0.1:" + upstream.getAddress().getPort()
                                                + "/repo.git/info/refs?service=git-upload-pack"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode());
    }

    // ---------------- git http-backend CGI 桥（上游 smart-HTTP 服务器） ----------------

    private void cgi(HttpExchange ex) throws IOException {
        try (ex) {
            if (!EXPECTED_AUTH.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                byte[] body = "auth required\n".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("WWW-Authenticate", "Basic realm=git");
                ex.sendResponseHeaders(401, body.length);
                ex.getResponseBody().write(body);
                return;
            }
            byte[] reqBody = ex.getRequestBody().readAllBytes();
            ProcessBuilder pb = new ProcessBuilder(
                    Path.of(gitExecPath, "git-http-backend").toString());
            Map<String, String> env = pb.environment();
            env.put("GIT_PROJECT_ROOT", tmp.toString());
            env.put("GIT_HTTP_EXPORT_ALL", "1");
            // http-backend 只对已认证用户开 receive-pack（推），无 REMOTE_USER 恒 403
            env.put("REMOTE_USER", "oauth2");
            env.put("REQUEST_METHOD", ex.getRequestMethod());
            env.put("PATH_INFO", ex.getRequestURI().getRawPath());
            String q = ex.getRequestURI().getRawQuery();
            env.put("QUERY_STRING", q == null ? "" : q);
            env.put("CONTENT_LENGTH", String.valueOf(reqBody.length));
            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            if (ct != null) {
                env.put("CONTENT_TYPE", ct);
            }
            String gp = ex.getRequestHeaders().getFirst("Git-Protocol");
            if (gp != null) {
                env.put("HTTP_GIT_PROTOCOL", gp);
            }
            Process p = pb.start();
            try (OutputStream stdin = p.getOutputStream()) {
                stdin.write(reqBody);
            }
            ByteArrayOutputStream cgiOut = new ByteArrayOutputStream();
            try (InputStream stdout = p.getInputStream()) {
                stdout.transferTo(cgiOut);
            }
            try {
                if (!p.waitFor(60, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("git http-backend 超时");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException(ie);
            }
            byte[] raw = cgiOut.toByteArray();
            // CGI 响应 = 头段（\r\n 行）+ 空行 + body；Status: 头映射为响应码
            int headerEnd = indexOfHeaderEnd(raw);
            int status = 200;
            for (String line : new String(raw, 0, headerEnd, StandardCharsets.ISO_8859_1).split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Status")) {
                    status = Integer.parseInt(value.split(" ")[0]);
                } else {
                    ex.getResponseHeaders().add(name, value);
                }
            }
            byte[] body = java.util.Arrays.copyOfRange(raw, headerEnd + 4, raw.length);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
        }
    }

    private static int indexOfHeaderEnd(byte[] raw) throws IOException {
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                return i;
            }
        }
        throw new IOException("git http-backend 输出无 CGI 头结束标记");
    }

    // ---------------- git CLI（禁交互弹窗，防测试挂死） ----------------

    private static String git(Path cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", cwd.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(90, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("git " + String.join(" ", args) + " 超时");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 失败: " + out);
        }
        return out;
    }
}
