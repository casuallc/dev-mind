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
 * 断线指数退避重连（1s→30s 封顶）；连接期间心跳由外部调度器调 {@link #send} 发 heartbeat 帧。
 * 断线期间上行帧丢弃（重连后 hello 对账兜底状态）。
 */
public class ServerConnection {

    private static final Logger log = LoggerFactory.getLogger(ServerConnection.class);

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
        long backoffMs = 1000;
        while (running) {
            CountDownLatch closed = new CountDownLatch(1);
            try {
                URI uri = URI.create(config.serverUrl() + "?token=" + config.token());
                log.info("连接服务端: {}", config.serverUrl());
                HttpClient client = HttpClient.newHttpClient();
                WebSocket ws = client.newWebSocketBuilder()
                        .buildAsync(uri, new Listener(closed))
                        .join();
                current.set(ws);
                onOpen.run();
                backoffMs = 1000; // 连上即复位退避
                closed.await();
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
            log.info("{}ms 后重连", backoffMs);
            Thread.sleep(backoffMs);
            backoffMs = Math.min(backoffMs * 2, 30_000);
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
