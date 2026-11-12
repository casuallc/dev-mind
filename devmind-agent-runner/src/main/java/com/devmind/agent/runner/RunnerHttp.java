package com.devmind.agent.runner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * runner 侧统一 HttpClient 工厂（CAP-21 https 支持）。
 *
 * <p>{@code agent.properties} 的 {@code insecure=true} 时（平台 https 自签证书部署，
 * 一键安装脚本探测到证书校验失败会自动写入该项），所有对服务端的 wss/https 调用
 * （长连接、隧道、升级/上下文/产物下载、文件上传）信任一切证书并关闭主机名校验。
 * 默认缺省 false，走 JVM 标准校验，行为与此前完全一致。</p>
 */
public final class RunnerHttp {

    /** WS 建连（TCP + 升级响应全程）超时：跨网段握手正常 1 秒内，15s 足够宽容 */
    public static final long WS_CONNECT_TIMEOUT_MS = 15_000;

    private RunnerHttp() {
    }

    /**
     * 带超时的 WS 建连。裸 {@code buildAsync().join()} 无超时——服务端重启窗口内 TCP 已
     * accept 但升级响应永远不来（或半开连接被中间设备黑洞）时永久挂死，此时保活循环尚未
     * 启动盖不到，通道永不自愈（2026-11-12 实锤：194 重启后隧道建连挂死，主通道在线、
     * 隧道离线 10 分钟+，只能重启 runner 恢复）。本方法对建连全程兜底，超时抛
     * {@link IOException} 让调用方走常规断线重连退避。
     *
     * <p>超时后给 listener 打放弃标并 cancel 未来：JDK 的 cancel 不保证中止在飞的升级
     * 交换，若其迟到完成，{@link AbandonGuard} 会在首个回调处 abort，杜绝幽灵连接驱动
     * 业务帧（隧道 OPEN 拨号、launch 指令等）。</p>
     */
    public static WebSocket connectWebSocket(HttpClient client, URI uri, WebSocket.Listener listener,
                                             long timeoutMs) throws Exception {
        AbandonGuard guard = new AbandonGuard(listener);
        CompletableFuture<WebSocket> pending = client.newWebSocketBuilder().buildAsync(uri, guard);
        try {
            return pending.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            guard.abandon();
            pending.cancel(true);
            throw new IOException("WS 建连超时（" + timeoutMs + "ms）: " + uri);
        } catch (ExecutionException e) {
            // 拆包保持与旧 join() 时代一致的失败日志口径（直接呈现根因类型）
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    /**
     * 放弃守门：被超时放弃的建连若迟到完成，首个回调即 abort 且不delegate业务 listener。
     * 包内可见供测试直驱。
     */
    static final class AbandonGuard implements WebSocket.Listener {
        private final WebSocket.Listener delegate;
        private volatile boolean abandoned;

        AbandonGuard(WebSocket.Listener delegate) {
            this.delegate = delegate;
        }

        void abandon() {
            abandoned = true;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (abandoned) {
                webSocket.abort();
                return;
            }
            delegate.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (abandoned) {
                webSocket.abort();
                return null;
            }
            return delegate.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
            if (abandoned) {
                webSocket.abort();
                return null;
            }
            return delegate.onBinary(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, java.nio.ByteBuffer message) {
            if (abandoned) {
                webSocket.abort();
                return null;
            }
            return delegate.onPing(webSocket, message);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, java.nio.ByteBuffer message) {
            return abandoned ? null : delegate.onPong(webSocket, message);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // 连接本就在关闭，无需 abort；也不惊动已放弃attempt的业务 latch
            return abandoned ? null : delegate.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!abandoned) {
                delegate.onError(webSocket, error);
            }
        }
    }

    /** 按 runner 配置构造；insecure=false 时等同 {@code HttpClient.newHttpClient()}。 */
    public static HttpClient newClient(RunnerConfig config) {
        return newClient(config.insecure());
    }

    /** 测试可见的显式开关版。 */
    static HttpClient newClient(boolean insecure) {
        return insecure ? InsecureHolder.CLIENT : HttpClient.newHttpClient();
    }

    /**
     * CAP-58 FR-06：git 代理上行客户端工厂——版本钉死 HTTP/1.1（JDK HttpClient 默认对
     * 明文 http 发 Upgrade: h2c，uvicorn 类上游会丢请求体；GitLab smart-HTTP 也走 1.1 语义），
     * 可选叠加 CAP-43 节点外网代理（git scope）与 insecure 信任一切证书（自签 GitLab）。
     *
     * @param httpProxyUrl CAP-43 节点代理 URL（http://host:port），null/空 = 直连
     */
    public static HttpClient newProxyUpstreamClient(RunnerConfig config, String httpProxyUrl) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(15));
        if (config.insecure()) {
            disableHostnameVerificationJvmWide(); // 必须先于任何 HttpClient 使用（幂等）
            b.sslContext(trustAllContext());
        }
        if (httpProxyUrl != null && !httpProxyUrl.isBlank()) {
            java.net.URI uri = java.net.URI.create(httpProxyUrl.trim());
            b.proxy(java.net.ProxySelector.of(
                    new java.net.InetSocketAddress(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 8080)));
        }
        return b.build();
    }

    /** 信任一切证书的 SSLContext（insecure 部署用）。 */
    private static SSLContext trustAllContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("insecure SSLContext 初始化失败", e);
        }
    }

    /** 信任一切证书的 X509TrustManager。 */
    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    /**
     * JDK HttpClient 对 https/wss 恒强制 HTTPS 主机名校验（AbstractAsyncSSLConnection
     * 无条件覆盖 SSLParameters，实例级无法关），唯一开关是内部系统属性
     * {@code jdk.internal.httpclient.disableHostnameVerification}（JDK 11 起存在），
     * 且在 java.net.http 内部类首次初始化时一次性读定——实测连「先建 HttpRequest 再设属性」
     * 都已太晚，故真正的生效点是 {@code AgentRunnerMain.main()} 加载配置后的第一时间；
     * 此处再设一次仅为 RunnerHttp 被率先触达时的兜底。该属性 JVM 全局生效：runner 是瘦进程、
     * HttpClient 只连本平台服务端，影响面可控。
     */
    static void disableHostnameVerificationJvmWide() {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    /** 惰性单例：WS 断线重连高频调用，避免每次重建 SSLContext。 */
    private static final class InsecureHolder {
        private static final HttpClient CLIENT = build();

        private static HttpClient build() {
            disableHostnameVerificationJvmWide(); // 必须先于任何 HttpClient/内部类初始化
            return HttpClient.newBuilder().sslContext(trustAllContext()).build();
        }
    }
}
