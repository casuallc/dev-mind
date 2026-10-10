package com.devmind.agent.tunnel;

import com.devmind.agent.config.EgressProperties;
import com.devmind.agent.model.EgressRuleEntity;
import com.devmind.agent.service.EgressRuleService;
import com.devmind.common.egress.EgressHostMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * CAP-70 FR-02/FR-06：内嵌 HTTP 代理端点——<b>只绑 127.0.0.1</b>、无认证（消费方全是同机
 * JDK HttpClient 系连接器）。支持两种形态：
 * <ul>
 *   <li>{@code CONNECT host:port}（https 隧道）：200 后双向 relay；</li>
 *   <li>absolute-form（{@code GET http://host/path HTTP/1.1}，http 明文经代理）：重写为
 *   origin-form、剥掉 Proxy-* 头后经隧道转发。</li>
 * </ul>
 *
 * <p><b>为什么需要它（2026-10-10 实锤）</b>：JDK HttpClient 只认 {@code Proxy.Type.HTTP}——
 * {@code HttpRequestImpl.retrieveProxy} 对 ProxySelector 返回的 SOCKS 代理<b>静默丢弃</b>
 * 直连（java.net.http 模块源码零 socks 处理，SOCKS 仅存在于 java.base 的 SocksSocketImpl）。
 * 194 事故：Jira 连接器挂了 SOCKS selector 却整片直连超时。CONNECT 语义与 socks5h 等价：
 * 目标主机名写进 CONNECT 行由隧道对端（runner）解析，服务端本机无需解析内网域名。</p>
 *
 * <p>白名单语义与 {@link Socks5Server} 一致：未命中规则 403、命中但不可用 502，
 * 不放行任何未配规则的目标出隧道。</p>
 */
@Component
public class HttpConnectProxyServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HttpConnectProxyServer.class);

    /** 请求头块上限（防内存放大；正常浏览器/HttpClient 头块远小于此） */
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    /** 读请求头块超时（防慢速占连接） */
    private static final int HEADER_READ_TIMEOUT_MS = 30_000;

    private final EgressProperties props;
    private final EgressRuleService ruleService;
    private final AgentTunnelRegistry tunnelRegistry;

    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public HttpConnectProxyServer(EgressProperties props, EgressRuleService ruleService,
                                  AgentTunnelRegistry tunnelRegistry) {
        this.props = props;
        this.ruleService = ruleService;
        this.tunnelRegistry = tunnelRegistry;
    }

    @Override
    public void start() {
        if (!props.isEnabled()) {
            return; // 与 Socks5Server 同开关，禁用时它已打过日志
        }
        running = true;
        Thread.ofVirtual().name("egress-http-accept").start(this::acceptLoop);
    }

    private void acceptLoop() {
        try {
            // 只绑 127.0.0.1（无认证，绝不对外暴露）
            serverSocket = new ServerSocket(props.getHttpPort(), 50, InetAddress.getByName("127.0.0.1"));
            log.info("CAP-70 出口隧道 HTTP 代理端点已监听 127.0.0.1:{}", props.getHttpPort());
            while (running) {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().name("egress-http-conn").start(() -> handle(client));
            }
        } catch (IOException e) {
            if (running) {
                log.error("HTTP 代理端点异常退出: {}", e.toString());
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(HEADER_READ_TIMEOUT_MS);
            byte[] block = readHeaderBlock(client.getInputStream());
            if (block == null) {
                closeQuietly(client);
                return;
            }
            int headerEnd = headerEnd(block);
            if (headerEnd < 0) {
                respond(client, 400, "Bad Request");
                closeQuietly(client);
                return;
            }
            String head = new String(block, 0, headerEnd, StandardCharsets.ISO_8859_1);
            byte[] leftover = new byte[block.length - headerEnd - 4];
            System.arraycopy(block, headerEnd + 4, leftover, 0, leftover.length);

            String[] lines = head.split("\r\n");
            String[] requestLine = lines[0].split(" ", 3);
            if (requestLine.length < 3) {
                respond(client, 400, "Bad Request");
                closeQuietly(client);
                return;
            }
            String method = requestLine[0].toUpperCase(Locale.ROOT);
            Request target = "CONNECT".equals(method)
                    ? parseConnectTarget(requestLine[1])
                    : parseAbsoluteTarget(method, requestLine[1], requestLine[2], lines);
            if (target == null) {
                respond(client, 400, "Bad Request");
                closeQuietly(client);
                return;
            }

            Optional<EgressRuleEntity> route = ruleService.findRoute(target.host());
            if (route.isEmpty()) {
                // 白名单语义：未命中不放行（同 Socks5Server rep=2）
                respond(client, 403, "Forbidden");
                log.info("HTTP 代理拒绝（未命中出口规则）: {}:{}", target.host(), target.port());
                closeQuietly(client);
                return;
            }
            EgressRuleEntity rule = route.get();
            try {
                ServerTunnelStream stream =
                        tunnelRegistry.openStream(rule.getNodeId(), target.host(), target.port(), client);
                if (target.connect()) {
                    // CONNECT：200 即开始双向 relay（TLS 握手字节走 leftover/后续流）
                    client.getOutputStream().write(
                            "HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    client.getOutputStream().flush();
                    stream.injectOutbound(leftover);
                } else {
                    // absolute-form：重写后的请求块 + 已读出的 body 开头一并注入
                    byte[] rewritten = target.rewrittenHead().getBytes(StandardCharsets.ISO_8859_1);
                    byte[] initial = new byte[rewritten.length + leftover.length];
                    System.arraycopy(rewritten, 0, initial, 0, rewritten.length);
                    System.arraycopy(leftover, 0, initial, rewritten.length, leftover.length);
                    stream.injectOutbound(initial);
                }
                client.setSoTimeout(0); // 进入 relay 后取消读超时
                stream.start();
                // 此后 socket 归流所有，流负责关闭
            } catch (Exception e) {
                // FR-07：命中但不可用 = 快速失败（不挂起等待、不静默直连）
                respond(client, 502, "Bad Gateway");
                log.warn("HTTP 代理拒绝（规则「{}」命中但出口不可用）: {}:{} — {}",
                        rule.getHostPattern(), target.host(), target.port(), e.getMessage());
                closeQuietly(client);
            }
        } catch (Exception e) {
            log.debug("HTTP 代理连接处理失败: {}", e.toString());
            closeQuietly(client);
        }
    }

    /** 解析 CONNECT authority（host:port，缺省 443） */
    private Request parseConnectTarget(String authority) {
        int colon = authority.lastIndexOf(':');
        if (colon <= 0) {
            return null;
        }
        String host = EgressHostMatcher.normalizeHost(authority.substring(0, colon));
        int port;
        try {
            port = Integer.parseInt(authority.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        return host.isEmpty() ? null : new Request(host, port, true, null);
    }

    /** 解析 absolute-form（http://host[:port]/path），重写为 origin-form 请求块 */
    private Request parseAbsoluteTarget(String method, String target, String version, String[] lines) {
        URI uri;
        try {
            uri = URI.create(target);
        } catch (Exception e) {
            return null;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            return null; // https 必走 CONNECT，absolute-form 只认 http
        }
        String host = EgressHostMatcher.normalizeHost(uri.getHost());
        if (host.isEmpty()) {
            return null;
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        StringBuilder rewritten = new StringBuilder(method).append(' ').append(path).append(' ')
                .append(version).append("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String name = lines[i];
            int colon = name.indexOf(':');
            String headerName = colon > 0 ? name.substring(0, colon).trim() : name;
            // 代理专有头不转发给目标
            if (headerName.equalsIgnoreCase("Proxy-Connection")
                    || headerName.equalsIgnoreCase("Proxy-Authorization")) {
                continue;
            }
            rewritten.append(name).append("\r\n");
        }
        rewritten.append("\r\n");
        return new Request(host, port, false, rewritten.toString());
    }

    /** 读请求头块（至 \r\n\r\n 或上限/超时），返回含尾随字节的整段；EOF 返回 null */
    private byte[] readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        int prev3 = -1, prev2 = -1, prev1 = -1, b;
        while (buf.size() < MAX_HEADER_BYTES && (b = in.read()) >= 0) {
            buf.write(b);
            if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') {
                return buf.toByteArray(); // 命中 \r\n\r\n，后面再无尾随（逐字节读无缓冲）
            }
            prev3 = prev2;
            prev2 = prev1;
            prev1 = b;
        }
        byte[] cur = buf.toByteArray();
        return cur.length == 0 ? null : cur;
    }

    /** \r\n\r\n 起始下标，未出现返回 -1 */
    private static int headerEnd(byte[] buf) {
        for (int i = 0; i + 3 < buf.length; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private void respond(Socket client, int code, String status) {
        try {
            OutputStream out = client.getOutputStream();
            out.write(("HTTP/1.1 " + code + " " + status
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    /** 一次请求的解析结果：connect=true 走 CONNECT 语义；否则 rewrittenHead 为重写后的请求块 */
    private record Request(String host, int port, boolean connect, String rewrittenHead) { }

    /** 实际监听端口（httpPort=0 ephemeral 时供测试取真端口） */
    int boundPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? -1 : ss.getLocalPort();
    }

    @Override
    public void stop() {
        running = false;
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE; // 同 Socks5Server
    }
}
