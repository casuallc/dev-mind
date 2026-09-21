package com.devmind.agent.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p>CAP-50：所有上行帧统一入 {@link #outbound 队列}，由**单条写线程**顺序落线（见该字段注释）。</p>
 */
public class ServerConnection {

    private static final Logger log = LoggerFactory.getLogger(ServerConnection.class);

    /** 服务端拒绝接入的关闭码（POLICY_VIOLATION：token 无效或节点已禁用） */
    static final int CLOSE_AUTH_REJECTED = 1008;
    /** 同节点重复接入被踢的关闭码（与服务端 AgentConnectionRegistry.CLOSE_DUPLICATE 约定） */
    static final int CLOSE_DUPLICATE = 4000;
    /** 连接存活超过此时长视为健康（认证通过），复位退避 */
    static final long MIN_HEALTHY_MS = 10_000;
    /** 出口队列上限（兜底阀：写线程被慢连接拖住时防无界堆积；正常帧率远够不到）。 */
    static final int SEND_QUEUE_CAPACITY = 10_000;
    /** 单帧写完超时。 */
    static final long SEND_TIMEOUT_MS = 10_000;

    private final RunnerConfig config;
    private final ObjectMapper mapper;
    private final Consumer<JsonNode> onFrame;
    /** 连接建立回调（发 hello） */
    private final Runnable onOpen;
    /** 当前连接；包内可见，供 `ServerConnectionEgressTest` 塞入假 WebSocket 做出口串行化回归。 */
    final AtomicReference<WebSocket> current = new AtomicReference<>();
    private volatile boolean running = true;

    /**
     * 出口串行化（CAP-50）：JDK 的 {@code WebSocket.sendText} 在**上一次发送尚未写完**时直接返回
     * {@code failedFuture("Send pending")}，并发的 {@code sendText} 会让帧被静默丢掉。开流式之前
     * 一回合没几帧、间隔以秒计，撞不上；开了 partial messages 后每会话有 stdout 循环、stderr 循环、
     * 心跳线程三条并发生产者，帧率上一个量级——丢 text_delta 会被后续全量 assistant 自愈，但丢
     * tool_use / result / **exit** 不会，丢一个 exit 帧就是会话永久卡在 RUNNING（服务端唯一兜底是
     * 重连时的 hello 对账）。故所有上行帧统一入队，由单条写线程顺序发。
     */
    private final BlockingQueue<Outbound> outbound = new LinkedBlockingQueue<>(SEND_QUEUE_CAPACITY);
    private final Thread writer;
    /** 队列打满丢弃计数（仅在连接被拖住的病态场景增长），用于节流告警。 */
    private final AtomicLong droppedFrames = new AtomicLong();

    /** 一帧待发内容：带上它所属的连接（连接已换/已断即丢弃），以及可选的同步等待信号。 */
    private record Outbound(WebSocket ws, String json, CompletableFuture<Boolean> done) { }

    public ServerConnection(RunnerConfig config, ObjectMapper mapper,
                            Consumer<JsonNode> onFrame, Runnable onOpen) {
        this.config = config;
        this.mapper = mapper;
        this.onFrame = onFrame;
        this.onOpen = onOpen;
        // 虚拟线程随 JVM 退出，不需要 join；shutdown() 会打断它的 poll
        this.writer = Thread.ofVirtual().name("ws-send").start(this::writeLoop);
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
            int close = listener.closeStatus;
            long sleepMs = backoff.next(close == CLOSE_AUTH_REJECTED, close == CLOSE_DUPLICATE, healthy);
            if (close == CLOSE_AUTH_REJECTED) {
                log.error("接入被拒绝（token 无效或节点已禁用），{}s 后重试——请到 Agent 节点页核对节点状态/token",
                        sleepMs / 1000);
            } else if (close == CLOSE_DUPLICATE) {
                // 本机有另一份 runner 实例占了座位：长退避静观其变，不抢（抢则双实例互踢风暴，
                // 每次重连对账还会误杀对方刚起的会话）。对方退出后本实例在退避窗口内自行接管。
                log.error("被判定为重复实例（同节点已有另一连接），{}s 后重试——请检查本机是否跑了多份 runner 进程",
                        sleepMs / 1000);
            } else {
                log.info("{}ms 后重连", sleepMs);
            }
            Thread.sleep(sleepMs);
        }
    }

    /**
     * 重连退避：普通断线 1s→30s 封顶；认证拒绝 30s→5min 封顶（永久性失败，低频重试等人工处理，
     * 节点被重新启用后能自行恢复）；重复实例被踢 60s→10min 封顶（座位已被占，静等对方退出自行接管）。
     * 健康连接（存活 ≥{@value #MIN_HEALTHY_MS}ms）复位三者。
     */
    static class Backoff {
        static final long MAX_NORMAL_MS = 30_000;
        static final long MAX_AUTH_MS = 300_000;
        static final long MAX_DUP_MS = 600_000;

        private long normalMs = 1000;
        private long authMs = 30_000;
        private long dupMs = 60_000;

        long next(boolean authRejected, boolean duplicate, boolean healthy) {
            if (healthy) {
                normalMs = 1000;
                authMs = 30_000;
                dupMs = 60_000;
            }
            if (authRejected) {
                long sleep = authMs;
                authMs = Math.min(authMs * 2, MAX_AUTH_MS);
                return sleep;
            }
            if (duplicate) {
                long sleep = dupMs;
                dupMs = Math.min(dupMs * 2, MAX_DUP_MS);
                return sleep;
            }
            long sleep = normalMs;
            normalMs = Math.min(normalMs * 2, MAX_NORMAL_MS);
            return sleep;
        }
    }

    /** 发送一帧（未连接时丢弃并告警）；入队即返回，由写线程顺序落线。 */
    public void send(Map<String, Object> frame) {
        enqueue(frame, null);
    }

    /**
     * 发送并同步等写完（exit 前最后一帧用，如 upgrade ack——fire-and-forget 紧接着
     * System.exit 会丢帧）。返回是否成功落线。走同一出口队列，顺序不受影响。
     */
    public boolean sendAndWait(Map<String, Object> frame, long timeoutMs) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        enqueue(frame, done);
        try {
            return Boolean.TRUE.equals(done.get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            log.warn("同步发送失败: {}", e.getMessage());
            return false;
        }
    }

    private void enqueue(Map<String, Object> frame, CompletableFuture<Boolean> done) {
        WebSocket ws = current.get();
        if (ws == null) {
            log.debug("未连接，帧丢弃: {}", frame.get("type"));
            if (done != null) {
                done.complete(false);
            }
            return;
        }
        Outbound item = new Outbound(ws, mapper.writeValueAsString(frame), done);
        if (outbound.offer(item)) {
            return;
        }
        long n = droppedFrames.incrementAndGet();
        if (n == 1 || n % 100 == 0) {
            log.warn("发送队列已满，累计丢弃 {} 帧（最近一帧 {}）——连接可能被拖住", n, frame.get("type"));
        }
        if (done != null) {
            done.complete(false);
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
            boolean ok = write(o);
            if (o.done() != null) {
                o.done().complete(ok);
            }
        }
    }

    /** 真正落线：连接已换或已断即丢弃，与「断线期间上行帧丢弃」的既有语义一致。 */
    private boolean write(Outbound o) {
        if (o.ws() != current.get()) {
            return false;
        }
        try {
            o.ws().sendText(o.json(), true).get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            log.warn("发送失败: {}", e.getMessage());
            return false;
        }
    }

    public void shutdown() {
        running = false;
        writer.interrupt();
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
