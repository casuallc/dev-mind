package com.devmind.agent.runner;

import com.devmind.common.agent.exec.GitProxyBroker;
import com.devmind.common.agent.exec.NodeProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * CAP-58 FR-06：localhost git smart-HTTP 代理——会话内本地 git 的凭据注入点。
 *
 * <p>会话里 agent 的 git 命令是 runner 主机本地进程执行（不过 WS 协议），worktree origin
 * 已被 {@code RunnerWorkspace.ensureProxyOrigin} 重写为
 * {@code http://127.0.0.1:<port>/git/<key><repo-path>}。本代理按 key 查
 * {@link GitProxyBroker} 取（upstreamOrigin, token），注入
 * {@code Authorization: Basic base64(oauth2:<token>)}（与 RunnerWorkspace.withToken 的
 * GitLab 约定一致）后透传转发真实远端，响应流式回写。token 全程只在内存与上游连接里，
 * agent 能读到的只有代理 URL。</p>
 *
 * <p>仅绑回环地址；smart-HTTP 纯透传（不解析协议）：GET /info/refs?service=* 与
 * POST /git-upload-pack|git-receive-pack 都按字节流转。未知/已释放 key → 404 清晰失败
 * （会话收口/释放后工作树里的代理 URL 即失效）。v1 不支持 LFS。</p>
 *
 * <p>上行客户端钉死 HTTP/1.1（JDK HttpClient 默认对明文 http 发 Upgrade: h2c，有丢
 * 请求体前科），并按 CAP-43 NodeProxy（git scope）叠加节点外网代理。</p>
 */
public class GitAuthProxy {

    private static final Logger log = LoggerFactory.getLogger(GitAuthProxy.class);
    /** 代理上下文前缀（防与未来其他回环端点撞路径） */
    public static final String CONTEXT = "/git";
    /** 逐跳头 + 长度头不回抄（响应统一 chunked 回写） */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade",
            "content-length", "proxy-authenticate", "proxy-authorization");
    /** 请求侧需透传的头（git 协议语义所在；其余丢弃） */
    private static final Set<String> REQUEST_PASS = Set.of(
            "content-type", "accept", "git-protocol", "content-encoding", "accept-encoding");

    private final GitProxyBroker broker;
    private final RunnerConfig config;
    /** 上行客户端缓存（key = insecure|nodeProxyUrl）：NodeProxy 由帧动态刷新，按值缓存重建 */
    private final ConcurrentHashMap<String, HttpClient> upstreamClients = new ConcurrentHashMap<>();
    private HttpServer server;

    public GitAuthProxy(GitProxyBroker broker, RunnerConfig config) {
        this.broker = broker;
        this.config = config;
    }

    /** 绑回环地址启动；端口被占抛 IOException（启动方转成清晰报错）。 */
    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 64);
        server.createContext(CONTEXT, this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log.info("git 代理已监听 http://127.0.0.1:{}{}（会话内本地 git 凭据注入）", port, CONTEXT);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 实际监听端口（测试传 0 拿临时端口后用）。 */
    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String raw = ex.getRequestURI().getRawPath(); // /git/<key>/<repo-path>/...
            String rel = raw.length() > CONTEXT.length() ? raw.substring(CONTEXT.length()) : "/";
            int keyEnd = rel.indexOf('/', 1);
            String key = keyEnd < 0 ? rel.substring(1) : rel.substring(1, keyEnd);
            String rest = keyEnd < 0 ? "" : rel.substring(keyEnd); // /<repo-path>/info/refs 等
            var entry = broker.lookup(key);
            if (entry.isEmpty()) {
                respond(ex, 404, "git 代理：未知或已释放的会话凭据（key 不在册——"
                        + "会话收口/释放后代理即失效，或 runner 重启后需 resume 会话重新登记）\n");
                return;
            }
            String query = ex.getRequestURI().getRawQuery();
            String upstream = entry.get().upstreamOrigin() + rest + (query == null ? "" : "?" + query);
            try {
                forward(ex, entry.get(), upstream);
            } catch (Exception e) {
                log.warn("git 代理转发失败: upstream={} err={}", stripQuery(upstream), e.toString());
                respond(ex, 502, "git 代理转发上游失败: " + e.getMessage() + "\n");
            }
        }
    }

    private void forward(HttpExchange ex, GitProxyBroker.CredEntry entry, String upstream) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(upstream));
        // 只透传协议语义头；Authorization 一律由代理按 key 注入（客户端带不带都覆盖）
        for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
            if (REQUEST_PASS.contains(h.getKey().toLowerCase())) {
                for (String v : h.getValue()) {
                    b.header(h.getKey(), v);
                }
            }
        }
        b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                ("oauth2:" + entry.token()).getBytes(StandardCharsets.UTF_8)));
        String method = ex.getRequestMethod().toUpperCase();
        if ("GET".equals(method) || "HEAD".equals(method)) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            // 流式上行（git push 可能 chunked 大 body）；HttpServer 侧 getRequestBody 已解 chunked。
            // git/curl 的 Expect: 100-continue 在 HttpServer 无自动应答，curl 等 1s 后照常发体，可接受
            b.method(method, HttpRequest.BodyPublishers.ofInputStream(ex::getRequestBody));
        }
        HttpResponse<InputStream> resp = upstreamClient().send(b.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        for (Map.Entry<String, List<String>> h : resp.headers().map().entrySet()) {
            if (!HOP_BY_HOP.contains(h.getKey().toLowerCase())) {
                ex.getResponseHeaders().put(h.getKey(), h.getValue());
            }
        }
        ex.sendResponseHeaders(resp.statusCode(), 0); // chunked
        try (InputStream in = resp.body(); OutputStream out = ex.getResponseBody()) {
            in.transferTo(out);
        }
    }

    /** 上行客户端：HTTP/1.1 + insecure + CAP-43 节点代理（git scope），按组合值缓存。 */
    private HttpClient upstreamClient() {
        String nodeProxy = NodeProxy.urlFor("git");
        String key = config.insecure() + "|" + (nodeProxy == null ? "" : nodeProxy);
        return upstreamClients.computeIfAbsent(key,
                k -> RunnerHttp.newProxyUpstreamClient(config, nodeProxy));
    }

    private static void respond(HttpExchange ex, int status, String msg) throws IOException {
        byte[] body = msg.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** 日志用 URL（去查询串，防 service 参数之外的意外泄漏）。 */
    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
