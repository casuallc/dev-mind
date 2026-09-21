package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-50：{@link ServerConnection} 出口串行化回归。
 *
 * <p>假 WebSocket **忠实复刻 JDK 行为**：上一次 {@code sendText} 未写完时返回
 * {@code failedFuture("Send pending")}。若调用方绕过出口队列、直接在生产者线程发送，帧就会被
 * 静默丢掉——这正是本改动要修的老 bug（丢一个 exit 帧 = 会话永久卡 RUNNING）。</p>
 */
class ServerConnectionEgressTest {

    @TempDir
    Path tmp;

    /** 记录收到的帧体。 */
    private final List<String> received = new ArrayList<>();
    /** 模拟「上一次发送尚未完成」。 */
    private final AtomicBoolean inFlight = new AtomicBoolean();
    /** 撞车次数——非 0 即说明有帧被静默丢弃。 */
    private final AtomicInteger pendingRejections = new AtomicInteger();

    /** 假连接：sendText 期间置 inFlight，撞车即返回失败 future（JDK 真实语义）。 */
    private WebSocket fakeWs() {
        return fakeWs(null, null);
    }

    /**
     * @param enteredSend 非空时，进入 sendText 前 countDown（用于确定性地把写线程卡在某帧上）
     * @param releaseSend 非空时，sendText 内阻塞等待它放行
     */
    private WebSocket fakeWs(CountDownLatch enteredSend, CountDownLatch releaseSend) {
        return (WebSocket) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "sendText" -> {
                            if (!inFlight.compareAndSet(false, true)) {
                                pendingRejections.incrementAndGet();
                                return CompletableFuture.failedFuture(
                                        new IllegalStateException("Send pending"));
                            }
                            try {
                                if (enteredSend != null) {
                                    enteredSend.countDown();
                                }
                                if (releaseSend != null) {
                                    releaseSend.await(30, TimeUnit.SECONDS);
                                }
                                Thread.sleep(1); // 放大写入窗口，让并发生产者有时间撞上来
                                synchronized (received) {
                                    received.add((String) args[0]);
                                }
                                return CompletableFuture.completedFuture(proxy);
                            } finally {
                                inFlight.set(false);
                            }
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        case "toString" -> {
                            return "fakeWs";
                        }
                        default -> {
                            return null; // sendClose/abort/request/getSubprotocol/... 均无需行为
                        }
                    }
                });
    }

    private ServerConnection connection() {
        return new ServerConnection(
                new RunnerConfig("ws://localhost", "t", "", "acceptEdits",
                        tmp, Map.of(), 2, "fake", tmp.resolve("ws"), 14, 360, 10, List.of(),
                        List.of(), "bash", 24),
                new ObjectMapper(), frame -> { }, () -> { });
    }

    /** 等到收到至少 n 帧（或超时）。 */
    private int awaitReceived(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (received) {
                if (received.size() >= n) {
                    return received.size();
                }
            }
            Thread.sleep(20);
        }
        synchronized (received) {
            return received.size();
        }
    }

    @Test
    void 并发生产者不丢帧且各自顺序不乱() throws Exception {
        ServerConnection conn = connection();
        try {
            conn.current.set(fakeWs());

            int threads = 4;
            int perThread = 50;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                int id = t;
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            conn.send(Map.of("type", "event", "n", id + "-" + i));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));

            assertEquals(threads * perThread, awaitReceived(threads * perThread), "一帧都不能丢");
            assertEquals(0, pendingRejections.get(), "并发 sendText 撞车 = 帧被静默丢弃");
            synchronized (received) {
                // 单写线程 → 同一生产者投喂的帧必须保持相对顺序
                for (int t = 0; t < threads; t++) {
                    int id = t; // lambda 捕获需 effectively final
                    List<String> mine = received.stream()
                            .filter(s -> s.contains("\"n\":\"" + id + "-"))
                            .toList();
                    assertEquals(perThread, mine.size());
                    for (int i = 0; i < perThread; i++) {
                        assertTrue(mine.get(i).contains("\"n\":\"" + id + "-" + i + "\""),
                                "第 " + id + " 号生产者的帧序被打乱");
                    }
                }
            }
        } finally {
            conn.shutdown();
        }
    }

    @Test
    void 未连接时丢帧且sendAndWait不傻等满超时() {
        ServerConnection conn = connection();
        try {
            conn.send(Map.of("type", "heartbeat"));
            long t0 = System.currentTimeMillis();
            assertFalse(conn.sendAndWait(Map.of("type", "exit"), 5_000));
            assertTrue(System.currentTimeMillis() - t0 < 2_000, "未连接时不该等满超时");
        } finally {
            conn.shutdown();
        }
    }

    @Test
    void sendAndWait等到真正落线() {
        ServerConnection conn = connection();
        try {
            conn.current.set(fakeWs());
            assertTrue(conn.sendAndWait(Map.of("type", "upgrade_ack"), 5_000));
            synchronized (received) {
                assertEquals(1, received.size());
                assertTrue(received.getFirst().contains("upgrade_ack"));
            }
        } finally {
            conn.shutdown();
        }
    }

    @Test
    void 连接更替后旧连接上的积压帧被丢弃() throws Exception {
        ServerConnection conn = connection();
        try {
            // 确定性地制造积压：先让写线程卡在第一帧的 sendText 里，第二帧只能排队
            CountDownLatch enteredSend = new CountDownLatch(1);
            CountDownLatch releaseSend = new CountDownLatch(1);
            conn.current.set(fakeWs(enteredSend, releaseSend));
            conn.send(Map.of("type", "event", "n", "old-inflight"));
            assertTrue(enteredSend.await(10, TimeUnit.SECONDS), "写线程未进入 sendText");
            conn.send(Map.of("type", "event", "n", "old-queued"));

            // 换连接：旧连接上排队的帧不能再发给新连接（同「断线期间上行帧丢弃」语义）
            conn.current.set(fakeWs());
            conn.send(Map.of("type", "event", "n", "new-1"));
            releaseSend.countDown();

            assertEquals(2, awaitReceived(2), "在飞的那帧与新连接上那帧该落线");
            synchronized (received) {
                assertTrue(received.stream().anyMatch(s -> s.contains("old-inflight")));
                assertTrue(received.stream().anyMatch(s -> s.contains("new-1")));
                assertFalse(received.stream().anyMatch(s -> s.contains("old-queued")),
                        "换连接后旧连接的积压帧必须丢弃");
            }
        } finally {
            conn.shutdown();
        }
    }
}
