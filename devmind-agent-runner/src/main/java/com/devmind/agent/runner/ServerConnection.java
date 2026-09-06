package com.devmind.agent.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 服务端 WS 长连接（JDK 内置 HttpClient WebSocket，无第三方依赖）。
 * 断线指数退避重连（普通断线 1s→30s 封顶）；连接期间心跳由外部调度器调 {@link #send} 发 heartbeat 帧。
 * 断线期间上行帧丢弃（重连后 hello 对账兜底状态）。
 *
 * <p>认证拒绝（服务端关闭码 1008，token 无效或节点已禁用）走独立的长退避（30s→5min 封顶）：
 * 服务端是先完成 WS 握手再在 afterConnectionEstablished 里拒绝，握手成功不等于接入成功，
 * 不能在握手时复位退避——只有连接存活 ≥10s（证明认证通过）才复位。</p>
 */
public class ServerConnection {

    private static final Logger log = LoggerFactory.getLogger(ServerConnection.class);

    /** 服务端拒绝接入的关闭码（POLICY_VIOLATION：token 无效或节点已禁用） */
    static final int CLOSE_AUTH_REJECTED = 1008;
    /** 连接存活超过此时长视为健康（认证通过），复位退避 */
    static final long MIN_HEALTHY_MS = 10_000;

    private final RunnerConfig config;
    private final ObjectMapper mapper;
    private final Consumer<JsonNode> onFrame;
    /** 连接建立回调（发 hello） */
    private final Runnable onOpen;
    private final AtomicReference<WebSocket> current = new AtomicReference<>();
    private volatile boolean running = true;

    public ServerConnection(RunnerConfig config, ObjectMapper mapper,
                            Consumer<JsonNode> onFrame, Runnable onOpen) {
        this.config = config;
        this.mapper = mapper;
        this.onFrame = onFrame;
        this.onOpen = onOpen;
    }

    /** 阻塞式连接循环：断线自动重连（指数退避），{@link #shutdown()} 后退出。 */
    public void run() throws InterruptedException {
        Backoff backoff = new Backoff();
        while (running) {
            CountDownLatch closed = new CountDownLatch(1);
            Listener listener = new Listener(closed);
            boolean healthy = false;
            try {
                URI uri = URI.create(config.serverUrl() + "?token=" + config.token());
                log.info("连接服务端: {}", config.serverUrl());
                HttpClient client = HttpClient.newHttpClient();
                WebSocket ws = client.newWebSocketBuilder()
                        .buildAsync(uri, listener)
                        .join();
                long connectedAt = System.currentTimeMillis();
                current.set(ws);
                onOpen.run();
                closed.await();
                // 握手成功≠接入成功：服务端可能在 afterConnectionEstablished 立即 1008 拒绝
                healthy = System.currentTimeMillis() - connectedAt >= MIN_HEALTHY_MS;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log.warn("连接失败: {}", e.getMessage());
            } finally {
                current.set(null);
            }
            if (!running) {
                break;
            }
            long sleepMs = backoff.next(listener.closeStatus == CLOSE_AUTH_REJECTED, healthy);
            if (listener.closeStatus == CLOSE_AUTH_REJECTED) {
                log.error("接入被拒绝（token 无效或节点已禁用），{}s 后重试——请到 Agent 节点页核对节点状态/token",
                        sleepMs / 1000);
            } else {
                log.info("{}ms 后重连", sleepMs);
            }
            Thread.sleep(sleepMs);
        }
    }

    /**
     * 重连退避：普通断线 1s→30s 封顶；认证拒绝 30s→5min 封顶（永久性失败，低频重试等人工处理，
     * 节点被重新启用后能自行恢复）。健康连接（存活 ≥{@value #MIN_HEALTHY_MS}ms）复位两者。
     */
    static class Backoff {
        static final long MAX_NORMAL_MS = 30_000;
        static final long MAX_AUTH_MS = 300_000;

        private long normalMs = 1000;
        private long authMs = 30_000;

        long next(boolean authRejected, boolean healthy) {
            if (healthy) {
                normalMs = 1000;
                authMs = 30_000;
            }
            if (authRejected) {
                long sleep = authMs;
                authMs = Math.min(authMs * 2, MAX_AUTH_MS);
                return sleep;
            }
            long sleep = normalMs;
            normalMs = Math.min(normalMs * 2, MAX_NORMAL_MS);
            return sleep;
        }
    }

    /** 发送一帧（未连接时丢弃并告警）。 */
    public void send(Map<String, Object> frame) {
        WebSocket ws = current.get();
        if (ws == null) {
            log.debug("未连接，帧丢弃: {}", frame.get("type"));
            return;
        }
        String json = mapper.writeValueAsString(frame);
        ws.sendText(json, true).exceptionally(e -> {
            log.warn("发送失败: {}", e.getMessage());
            return null;
        });
    }

    /**
     * 发送并同步等写完（exit 前最后一帧用，如 upgrade ack——fire-and-forget 紧接着
     * System.exit 会丢帧）。返回是否成功落线。
     */
    public boolean sendAndWait(Map<String, Object> frame, long timeoutMs) {
        WebSocket ws = current.get();
        if (ws == null) {
            return false;
        }
        try {
            ws.sendText(mapper.writeValueAsString(frame), true)
                    .get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            log.warn("同步发送失败: {}", e.getMessage());
            return false;
        }
    }

    public void shutdown() {
        running = false;
        WebSocket ws = current.get();
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "runner shutdown");
        }
        // 唤醒 run() 的 await
        WebSocket w = current.getAndSet(null);
        if (w != null) {
            w.abort();
        }
    }

    private class Listener implements WebSocket.Listener {
        private final CountDownLatch closed;
        private final StringBuilder partial = new StringBuilder();
        /** 服务端关闭码（-1 = 未走正常关闭，如传输异常） */
        volatile int closeStatus = -1;

        Listener(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("已连接服务端");
            webSocket.request(16);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                try {
                    onFrame.accept(mapper.readTree(text));
                } catch (Exception e) {
                    log.warn("帧处理失败: {} payload={}", e.getMessage(),
                            text.length() > 200 ? text.substring(0, 200) + "..." : text);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeStatus = statusCode;
            log.warn("连接关闭: {} {}", statusCode, reason);
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("连接异常: {}", error.getMessage());
            closed.countDown();
        }
    }
}
