package com.devmind.agent.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RunnerHttp} 的 insecure 客户端行为实证（CAP-21 https 自签支持）：
 * keytool 现场生成 SAN 与连接主机不匹配的自签证书起 HttpsServer——
 * 默认客户端必须握手失败（证书不受信+主机名不匹配双重验证），
 * insecure 客户端必须成功（证明 trust-all 与主机名校验关闭都真生效）。
 * 若 JDK 行为变化（空串 endpoint identification 不再关主机名校验），此测试会红。
 */
class RunnerHttpTest {

    @TempDir
    Path dir;

    private HttpsServer server;
    private String baseUrl;

    @BeforeEach
    void startTlsServer() throws Exception {
        Path keystore = dir.resolve("ks.p12");
        // CN/SAN 均为 example.invalid：连 127.0.0.1 时主机名校验必不匹配，
        // 防止「证书域名恰好对上」掩盖主机名校验未关闭的回归
        String javaHome = System.getProperty("java.home");
        Process gen = new ProcessBuilder(
                Path.of(javaHome, "bin", isWindows() ? "keytool.exe" : "keytool").toString(),
                "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048",
                "-keystore", keystore.toString(), "-storepass", "changeit", "-keypass", "changeit",
                "-storetype", "PKCS12", "-validity", "2",
                "-dname", "CN=example.invalid", "-ext", "SAN=dns:example.invalid")
                .redirectErrorStream(true).start();
        String genOut = new String(gen.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!gen.waitFor(30, TimeUnit.SECONDS) || gen.exitValue() != 0) {
            throw new IllegalStateException("keytool 生成自签证书失败: " + genOut);
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(ctx));
        server.createContext("/ping", ex -> {
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        baseUrl = "https://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void defaultClientRejectsSelfSigned() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/ping")).GET().build();
        assertThrows(IOException.class,
                () -> RunnerHttp.newClient(false).send(req, HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    void insecureClientTrustsSelfSignedAndSkipsHostnameCheck() throws Exception {
        // 关主机名校验靠内部系统属性且在 java.net.http 内部类首次初始化时一次性读定
        // （实测先建 HttpRequest 再设属性都已太晚），生产上由 AgentRunnerMain.main() 加载配置后
        // 第一时间设置——子 JVM 以 -D 形式模拟这一时序
        Process p = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString(),
                "-Djdk.internal.httpclient.disableHostnameVerification=true",
                "-cp", System.getProperty("java.class.path"),
                ChildMain.class.getName(), baseUrl + "/ping")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(60, TimeUnit.SECONDS) ? p.exitValue() : -1,
                "子进程应成功: " + out);
        assertEquals("pong", out.trim(), out);
    }

    /**
     * {@link RunnerHttp#connectWebSocket} 黑洞实证：对端 accept 后永不回升级响应
     * （服务端重启窗口/中间设备丢半开连接的现场复刻），裸 join 会永久挂死、
     * 保活循环未启动盖不到 → 必须在超时处快速失败走重连退避（2026-11-12 事故回归网）。
     */
    @Test
    void connectWebSocketTimesOutOnBlackHole() throws Exception {
        AtomicReference<Socket> held = new AtomicReference<>();
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread accepter = Thread.ofVirtual().start(() -> {
                try {
                    held.set(ss.accept()); // accept 后握住不回一字节：升级响应永远不来
                } catch (IOException ignored) {
                }
            });
            long t0 = System.currentTimeMillis();
            IOException ex = assertThrows(IOException.class, () ->
                    RunnerHttp.connectWebSocket(HttpClient.newHttpClient(),
                            URI.create("ws://127.0.0.1:" + ss.getLocalPort() + "/ws"),
                            new WebSocket.Listener() { }, 500));
            long elapsed = System.currentTimeMillis() - t0;
            assertTrue(ex.getMessage() != null && ex.getMessage().contains("超时"), ex.toString());
            assertTrue(elapsed < 10_000, "应在超时附近快速失败，实际 " + elapsed + "ms");
            accepter.join(5_000);
        } finally {
            Socket s = held.get();
            if (s != null) {
                s.close();
            }
        }
    }

    /** 迟到完成的守门：abandon 后首个回调必须 abort 且不delegate业务 listener（防幽灵连接驱动业务帧）。 */
    @Test
    void abandonGuardAbortsLateCompletion() {
        AtomicBoolean delegated = new AtomicBoolean();
        AtomicBoolean aborted = new AtomicBoolean();
        WebSocket fake = fakeWebSocket(aborted);
        RunnerHttp.AbandonGuard guard = new RunnerHttp.AbandonGuard(new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                delegated.set(true);
            }
        });
        guard.abandon();
        guard.onOpen(fake);
        guard.onText(fake, "x", true);
        guard.onBinary(fake, java.nio.ByteBuffer.allocate(0), true);
        assertFalse(delegated.get(), "放弃后业务 listener 不应收到任何回调");
        assertTrue(aborted.get(), "放弃后首个回调必须 abort 幽灵连接");
    }

    /** 未放弃时正常透传（守门不能误伤健康连接）。 */
    @Test
    void abandonGuardDelegatesWhenNotAbandoned() {
        AtomicBoolean delegated = new AtomicBoolean();
        WebSocket fake = fakeWebSocket(new AtomicBoolean());
        RunnerHttp.AbandonGuard guard = new RunnerHttp.AbandonGuard(new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                delegated.set(true);
            }
        });
        guard.onOpen(fake);
        assertTrue(delegated.get());
    }

    private static WebSocket fakeWebSocket(AtomicBoolean aborted) {
        return (WebSocket) Proxy.newProxyInstance(RunnerHttpTest.class.getClassLoader(),
                new Class<?>[] {WebSocket.class}, (proxy, method, args) -> {
                    if ("abort".equals(method.getName())) {
                        aborted.set(true);
                    }
                    return switch (method.getReturnType().getName()) {
                        case "boolean" -> false;
                        case "int" -> 0;
                        default -> null;
                    };
                });
    }

    /** 子 JVM 入口：全新 JVM 里经 {@link RunnerHttp#newClient(boolean)} 构造 insecure 客户端 GET。 */    public static final class ChildMain {
        public static void main(String[] args) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(args[0])).GET().build();
            HttpResponse<String> resp = RunnerHttp.newClient(true).send(req,
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + resp.statusCode());
            }
            System.out.print(resp.body());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
