package com.devmind.agent.runner;

import com.devmind.common.egress.TunnelFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-70 FR-01/04 runner 侧：OPEN 白名单二次校验（不命中 RST）、拨号失败 OPEN_ACK 带错、
 * 命中 → OPEN_ACK + 真 loopback echo 双向 relay + WINDOW credit + CLOSE 半关。
 * 假 WebSocket（java.lang.reflect.Proxy，runner 模块无 Mockito 先例见 ServerConnectionEgressTest）
 * 内存捕获 sendBinary 上行帧。
 */
class TunnelConnectionTest {

    private TunnelConnection tunnel;
    /** 上行帧（已解码，sendBinary 顺序即帧序） */
    private final List<TunnelFrame> uplink = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger abortCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private ServerSocket echoServer;

    @BeforeEach
    void setUp() {
        RunnerConfig config = new RunnerConfig("ws://127.0.0.1:8080/ws/agent", "t", "",
                "acceptEdits", Path.of("."), Map.of(), 4, "fake", Path.of("./workspaces"));
        tunnel = new TunnelConnection(config, tools.jackson.databind.json.JsonMapper.builder().build());
        tunnel.current.set(fakeWs());
        EgressHostAllowlist.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        tunnel.shutdown();
        EgressHostAllowlist.clear();
        if (echoServer != null) {
            echoServer.close();
        }
    }

    private WebSocket fakeWs() {
        return (WebSocket) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WebSocket.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "sendBinary" -> {
                        ByteBuffer buf = ((ByteBuffer) args[0]).duplicate();
                        byte[] bytes = new byte[buf.remaining()];
                        buf.get(bytes);
                        uplink.add(TunnelFrame.decode(bytes));
                        yield CompletableFuture.completedFuture(proxy);
                    }
                    case "sendPing" -> CompletableFuture.completedFuture(proxy); // 不再主动 ping，防御性兼容
                    case "abort" -> {
                        abortCount.incrementAndGet();
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "fakeTunnelWs";
                    default -> null;
                });
    }

    /** 轮询上行帧（写线程异步落线），直到命中类型 */
    private TunnelFrame awaitUplink(int type, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (TunnelFrame f : uplink) {
                if (f.type() == type) {
                    return f;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("未等到上行帧类型 " + type + "，已收到: "
                + uplink.stream().map(f -> String.valueOf(f.type())).toList());
    }

    /** 启动真 loopback echo 服务（读到 EOF 后关连接），返回端口 */
    private int startEchoServer() throws Exception {
        echoServer = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        int port = echoServer.getLocalPort();
        Thread t = new Thread(() -> {
            try {
                Socket s = echoServer.accept();
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
                s.close();
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return port;
    }

    @Test
    void tunnelUrlDerivesFromServerUrl() {
        assertEquals("ws://h:8080/ws/agent-tunnel",
                TunnelConnection.tunnelUrl("ws://h:8080/ws/agent"));
        assertEquals("wss://h/ws/agent-tunnel",
                TunnelConnection.tunnelUrl("wss://h/ws/agent"));
    }

    @Test
    void silenceAfterHeartbeatAbortsAndReleasesClosedLatch() throws Exception {
        // 收到过服务端心跳的连接：下行全静默超阈值 → abort + 放行 closed 闩锁（run() 走重连）
        tunnel.keepaliveIntervalMs = 40;
        tunnel.keepaliveTimeoutMs = 200;
        tunnel.lastInboundAt.set(System.currentTimeMillis());
        tunnel.heartbeatSeen = true;
        CountDownLatch closed = new CountDownLatch(1);
        Thread t = Thread.ofVirtual().start(() -> tunnel.keepaliveLoop(tunnel.current.get(), closed));
        awaitTrue(() -> abortCount.get() >= 1, 5000, "静默超阈值应 abort");
        assertTrue(closed.await(2, TimeUnit.SECONDS),
                "abort 后必须放行 closed 闩锁，否则 run() 挂在 await 上永不重连");
        t.join(5000);
    }

    @Test
    void silenceWithoutHeartbeatNeverAborts() throws Exception {
        // 老服务端无心跳（兼容门）：静默判定不启用，健康隧道不被误杀
        tunnel.keepaliveIntervalMs = 40;
        tunnel.keepaliveTimeoutMs = 120;
        tunnel.lastInboundAt.set(System.currentTimeMillis());
        tunnel.heartbeatSeen = false;
        Thread t = Thread.ofVirtual().start(() ->
                tunnel.keepaliveLoop(tunnel.current.get(), new CountDownLatch(1)));
        Thread.sleep(500); // 远超 timeout，若误判必已 abort
        assertEquals(0, abortCount.get(), "未收到过心跳不得启用静默断链判定");
        tunnel.shutdown();
        t.join(5000);
    }

    @Test
    void keepaliveDoesNotAbortWhileInboundAlive() throws Exception {
        tunnel.keepaliveIntervalMs = 40;
        tunnel.keepaliveTimeoutMs = 250;
        tunnel.heartbeatSeen = true;
        Thread t = Thread.ofVirtual().start(() ->
                tunnel.keepaliveLoop(tunnel.current.get(), new CountDownLatch(1)));
        // 模拟持续有下行（心跳/业务帧）：800ms 内不断刷新 lastInboundAt
        for (int i = 0; i < 20; i++) {
            tunnel.lastInboundAt.set(System.currentTimeMillis());
            Thread.sleep(40);
        }
        assertEquals(0, abortCount.get(), "下行新鲜不得 abort");
        tunnel.shutdown(); // 停循环
        t.join(5000);
    }

    private void awaitTrue(java.util.function.BooleanSupplier cond, long timeoutMs, String what)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("未等到: " + what);
    }

    @Test
    void openNotInAllowlistRst() throws Exception {
        tunnel.onTunnelFrame(TunnelFrame.open(1, "evil.com", 443));
        TunnelFrame rst = awaitUplink(TunnelFrame.TYPE_RST, 5000);
        assertEquals(1, rst.streamId());
        assertTrue(rst.error().contains("白名单"), rst.error());
        assertEquals(0, tunnel.streamCount());
    }

    @Test
    void openDialFailureOpenAckError() throws Exception {
        EgressHostAllowlist.set(List.of("127.0.0.1"));
        tunnel.onTunnelFrame(TunnelFrame.open(2, "127.0.0.1", 1)); // 端口 1 必拒连
        TunnelFrame ack = awaitUplink(TunnelFrame.TYPE_OPEN_ACK, 15000);
        assertEquals(2, ack.streamId());
        assertTrue(!ack.isOk(), "拨号失败应 OPEN_ACK 带错");
        assertEquals(0, tunnel.streamCount());
    }

    @Test
    void openEchoRoundTripAndHalfClose() throws Exception {
        int port = startEchoServer();
        EgressHostAllowlist.set(List.of("127.0.0.1"));
        tunnel.onTunnelFrame(TunnelFrame.open(3, "127.0.0.1", port));

        TunnelFrame ack = awaitUplink(TunnelFrame.TYPE_OPEN_ACK, 5000);
        assertEquals(3, ack.streamId());
        assertTrue(ack.isOk(), "OPEN_ACK 应成功: " + ack.error());
        assertEquals(1, tunnel.streamCount());

        // 服务端→目标：DATA 应到 echo 服务并原路弹回成上行 DATA
        tunnel.onTunnelFrame(TunnelFrame.data(3, "ping".getBytes(StandardCharsets.UTF_8)));
        TunnelFrame echo = awaitUplink(TunnelFrame.TYPE_DATA, 5000);
        assertEquals(3, echo.streamId());
        assertArrayEquals("ping".getBytes(StandardCharsets.UTF_8), echo.payload());
        // runner 消费下行数据后应回 WINDOW credit
        TunnelFrame win = awaitUplink(TunnelFrame.TYPE_WINDOW, 5000);
        assertEquals(4, win.windowBytes());

        // 服务端半关 → runner 关向目标的输出，echo 服务读到 EOF 关连接 → runner 回 CLOSE
        tunnel.onTunnelFrame(TunnelFrame.close(3));
        TunnelFrame close = awaitUplink(TunnelFrame.TYPE_CLOSE, 5000);
        assertEquals(3, close.streamId());
    }
}
