package com.devmind.agent.runner;

import com.devmind.common.egress.TunnelFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CAP-70 FR-01/04：出口隧道连接（runner 侧）。除 /ws/agent 控制通道外另起的专用隧道 WS
 * （/ws/agent-tunnel，二进制流帧），断线指数退避重连（复用 {@link ServerConnection.Backoff}），
 * token 认证同级（1008 拒绝走长退避）。
 *
 * <p>职责：
 * <ul>
 *   <li>收 tunnel_hello 文本帧 → 刷新 {@link EgressHostAllowlist}（FR-04 快照）；</li>
 *   <li>收 OPEN → 白名单二次校验（不命中 RST）→ 本机拨号目标 → OPEN_ACK → 双向 relay
 *   （{@link RunnerTunnelStream}，窗口流控与服务端镜像）；</li>
 *   <li>收到 OPEN_ACK 等只应下行的帧 = 伪造帧，断隧道自保（与服务端对称）。</li>
 * </ul>
 *
 * <p>出口纪律同控制通道（CAP-50）：所有上行帧入 {@link #outbound} 由单条写线程顺序落线，
 * listener 线程只分发，拨号/relay 等阻塞活给虚拟线程。</p>
 */
public class TunnelConnection {

    private static final Logger log = LoggerFactory.getLogger(TunnelConnection.class);

    /** 目标拨号超时（内网目标不可达须快速失败，OPEN_ACK 错误回服务端） */
    static final long DIAL_TIMEOUT_MS = 10_000;
    /** 出口队列上限（同 ServerConnection 兜底阀语义） */
    static final int SEND_QUEUE_CAPACITY = 10_000;
    /** 单帧写完超时 */
    static final long SEND_TIMEOUT_MS = 10_000;

    private final RunnerConfig config;
    private final ObjectMapper mapper;
    /** 当前隧道连接；包内可见供测试塞假 WebSocket */
    final AtomicReference<WebSocket> current = new AtomicReference<>();
    private final Map<Integer, RunnerTunnelStream> streams = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    /** 一帧待发二进制：带所属连接（连接已换/已断即丢弃） */
    private record Outbound(WebSocket ws, byte[] frame) { }

    private final BlockingQueue<Outbound> outbound = new LinkedBlockingQueue<>(SEND_QUEUE_CAPACITY);
    private final Thread writer;

    public TunnelConnection(RunnerConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.writer = Thread.ofVirtual().name("tunnel-ws-send").start(this::writeLoop);
    }

    /** serverUrl（…/ws/agent）→ 隧道 URL（…/ws/agent-tunnel）：替换最后一段路径 */
    static String tunnelUrl(String serverUrl) {
        int i = serverUrl.lastIndexOf('/');
        return (i < 0 ? serverUrl : serverUrl.substring(0, i)) + "/agent-tunnel";
    }

    /** 阻塞式连接循环：断线自动重连（指数退避），{@link #shutdown()} 后退出。 */
    public void run() throws InterruptedException {
        ServerConnection.Backoff backoff = new ServerConnection.Backoff();
        while (running) {
            CountDownLatch closed = new CountDownLatch(1);
            Listener listener = new Listener(closed);
            boolean healthy = false;
            try {
                URI uri = URI.create(tunnelUrl(config.serverUrl()) + "?token=" + config.token());
                log.info("连接出口隧道: {}", tunnelUrl(config.serverUrl()));
                WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                        .buildAsync(uri, listener)
                        .join();
                long connectedAt = System.currentTimeMillis();
                current.set(ws);
                closed.await();
                healthy = System.currentTimeMillis() - connectedAt >= ServerConnection.MIN_HEALTHY_MS;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log.warn("隧道连接失败: {}", e.getMessage());
            } finally {
                current.set(null);
                abortAllStreams("隧道断开");
            }
            if (!running) {
                break;
            }
            int close = listener.closeStatus;
            long sleepMs = backoff.next(close == ServerConnection.CLOSE_AUTH_REJECTED, false, healthy);
            if (close == ServerConnection.CLOSE_AUTH_REJECTED) {
                log.error("隧道接入被拒绝（token 无效或节点已禁用），{}s 后重试", sleepMs / 1000);
            } else {
                log.info("隧道 {}ms 后重连", sleepMs);
            }
            Thread.sleep(sleepMs);
        }
    }

    /** 上行一帧（未连接时丢弃——隧道断开的流已全部中止，残留帧本就该丢） */
    void send(TunnelFrame frame) {
        WebSocket ws = current.get();
        if (ws == null) {
            return;
        }
        if (!outbound.offer(new Outbound(ws, frame.encode()))) {
            log.warn("隧道发送队列已满，帧丢弃（流 {} 类型 {}）——连接可能被拖住", frame.streamId(), frame.type());
        }
    }

    private void writeLoop() {
        while (running) {
            Outbound o;
            try {
                o = outbound.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (o == null) {
                continue;
            }
            if (o.ws() != current.get()) {
                continue; // 连接已换/已断，丢弃
            }
            try {
                o.ws().sendBinary(ByteBuffer.wrap(o.frame()), true)
                        .get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("隧道发送失败: {}", e.getMessage());
            }
        }
    }

    /** 帧分发（listener 线程，只分发不阻塞；OPEN 拨号给虚拟线程）。包内可见供测试直驱。 */
    void onTunnelFrame(TunnelFrame frame) {
        switch (frame.type()) {
            case TunnelFrame.TYPE_OPEN ->
                    Thread.ofVirtual().name("tunnel-open-" + frame.streamId()).start(() -> handleOpen(frame));
            case TunnelFrame.TYPE_DATA -> {
                RunnerTunnelStream s = streams.get(frame.streamId());
                if (s != null) {
                    s.onData(frame.payload());
                }
            }
            case TunnelFrame.TYPE_CLOSE -> {
                RunnerTunnelStream s = streams.get(frame.streamId());
                if (s != null) {
                    s.onClose();
                }
            }
            case TunnelFrame.TYPE_RST -> {
                RunnerTunnelStream s = streams.get(frame.streamId());
                if (s != null) {
                    log.debug("隧道流 {} 被服务端 RST: {}", frame.streamId(), frame.error());
                    s.abort(frame.error());
                }
            }
            case TunnelFrame.TYPE_WINDOW -> {
                RunnerTunnelStream s = streams.get(frame.streamId());
                if (s != null) {
                    s.onWindow(frame.windowBytes());
                }
            }
            default -> {
                // OPEN_ACK 只应由 runner 上行；服务端下行即伪造帧（与服务端收 OPEN 对称），断隧道自保
                log.warn("隧道收到非法下行帧类型 {}，断开隧道", frame.type());
                WebSocket ws = current.get();
                if (ws != null) {
                    ws.abort();
                }
            }
        }
    }

    /** OPEN 处理：白名单二次校验（FR-04）→ 拨号 → OPEN_ACK → 起双向 relay */
    private void handleOpen(TunnelFrame frame) {
        if (!EgressHostAllowlist.allows(frame.host())) {
            log.warn("隧道 OPEN 目标不在出口白名单，RST: {}:{}", frame.host(), frame.port());
            send(TunnelFrame.rst(frame.streamId(), "目标不在出口白名单: " + frame.host()));
            return;
        }
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            // socks5h 语义：主机名在 runner 侧解析（InetSocketAddress 构造即解析）
            socket.connect(new InetSocketAddress(frame.host(), frame.port()), (int) DIAL_TIMEOUT_MS);
        } catch (Exception e) {
            log.warn("隧道 OPEN 拨号失败: {}:{} — {}", frame.host(), frame.port(), e.toString());
            send(TunnelFrame.openAck(frame.streamId(), "拨号失败: " + e.getMessage()));
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            return;
        }
        RunnerTunnelStream stream = new RunnerTunnelStream(frame.streamId(), socket, this::send,
                streams::remove, TunnelFrame.DEFAULT_WINDOW_BYTES);
        streams.put(frame.streamId(), stream);
        send(TunnelFrame.openAck(frame.streamId(), null));
        stream.start();
        log.debug("隧道流 {} 已接通 {}:{}", frame.streamId(), frame.host(), frame.port());
    }

    private void abortAllStreams(String reason) {
        streams.values().forEach(s -> s.abort(reason));
    }

    public void shutdown() {
        running = false;
        writer.interrupt();
        WebSocket ws = current.getAndSet(null);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "runner shutdown");
            } catch (Exception ignored) {
            }
            ws.abort();
        }
        abortAllStreams("runner shutdown");
    }

    /** 测试探针：在线流数 */
    int streamCount() {
        return streams.size();
    }

    private class Listener implements WebSocket.Listener {
        private final CountDownLatch closed;
        private final StringBuilder partialText = new StringBuilder();
        private final ByteArrayOutputStream partialBinary = new ByteArrayOutputStream();
        volatile int closeStatus = -1;

        Listener(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("出口隧道已连接");
            webSocket.request(16);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialText.append(data);
            if (last) {
                String text = partialText.toString();
                partialText.setLength(0);
                handleText(text);
            }
            webSocket.request(1);
            return null;
        }

        /** tunnel_hello（唯一文本下行帧）：刷新出口白名单快照 */
        private void handleText(String text) {
            try {
                JsonNode frame = mapper.readTree(text);
                if ("tunnel_hello".equals(frame.path("type").asText(""))) {
                    List<String> allowed = new ArrayList<>();
                    frame.path("allowedHosts").forEach(h -> allowed.add(h.asText("")));
                    EgressHostAllowlist.set(allowed);
                    log.info("出口白名单快照已刷新（{} 条）", allowed.size());
                } else {
                    log.debug("隧道未知文本帧: {}", text.length() > 200 ? text.substring(0, 200) : text);
                }
            } catch (Exception e) {
                log.warn("隧道文本帧处理失败: {}", e.getMessage());
            }
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            partialBinary.writeBytes(chunk);
            if (last) {
                byte[] bytes = partialBinary.toByteArray();
                partialBinary.reset();
                try {
                    onTunnelFrame(TunnelFrame.decode(bytes));
                } catch (Exception e) {
                    log.warn("隧道坏帧（{}），断开隧道自保", e.getMessage());
                    webSocket.abort();
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeStatus = statusCode;
            log.warn("隧道连接关闭: {} {}", statusCode, reason);
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("隧道连接异常: {}", error.getMessage());
            closed.countDown();
        }
    }
}
