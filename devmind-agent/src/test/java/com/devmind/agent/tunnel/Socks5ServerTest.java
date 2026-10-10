package com.devmind.agent.tunnel;

import com.devmind.agent.config.EgressProperties;
import com.devmind.agent.model.AgentAuditLogEntity;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.model.EgressRuleEntity;
import com.devmind.agent.service.AgentAuditLogService;
import com.devmind.agent.service.EgressRuleService;
import com.devmind.common.egress.TunnelFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-70 FR-02：SOCKS5 端点端到端——只绑 127.0.0.1、未命中拒绝(rep=2)、命中不可用拒绝(rep=4)、
 * 命中在线隧道 → OPEN/OPEN_ACK 后双向 relay + 半关 CLOSE。
 */
class Socks5ServerTest {

    private EgressRuleService ruleService;
    private AgentAuditLogService auditLog;
    private AgentTunnelRegistry registry;
    private Socks5Server server;
    private WebSocketSession ws;

    @BeforeEach
    void setUp() {
        ruleService = mock(EgressRuleService.class);
        EgressProperties props = new EgressProperties();
        props.setSocksPort(0); // ephemeral，避免撞本机常驻 18089
        props.setOpenTimeoutMs(5000);
        auditLog = mock(AgentAuditLogService.class);
        registry = new AgentTunnelRegistry(ruleService, props, JsonMapper.builder().build(), mock(AgentAuditLogService.class));
        server = new Socks5Server(props, ruleService, registry, auditLog);
        when(ruleService.allowedHostsFor(anyLong())).thenReturn(List.of("*.corp.com"));
        ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        AgentNodeEntity node = new AgentNodeEntity();
        node.setId(7L);
        node.setName("n7");
        registry.onConnect(node, ws);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private int port() throws Exception {
        for (int i = 0; i < 50 && server.boundPort() <= 0; i++) {
            Thread.sleep(100);
        }
        assertTrue(server.boundPort() > 0, "SOCKS5 未就绪");
        return server.boundPort();
    }

    /** SOCKS5 握手（无认证 CONNECT 域名）；返回响应帧的 rep 字节，已读掉 10 字节响应 */
    private int handshake(Socket client, String host, int port) throws Exception {
        DataInputStream in = new DataInputStream(client.getInputStream());
        OutputStream out = client.getOutputStream();
        out.write(new byte[]{5, 1, 0});
        out.flush();
        assertEquals(5, in.readUnsignedByte());
        assertEquals(0, in.readUnsignedByte());
        byte[] h = host.getBytes(StandardCharsets.UTF_8);
        out.write(5);
        out.write(1); // CONNECT
        out.write(0);
        out.write(3); // ATYP=domain
        out.write(h.length);
        out.write(h);
        out.write((port >> 8) & 0xFF);
        out.write(port & 0xFF);
        out.flush();
        assertEquals(5, in.readUnsignedByte());
        int rep = in.readUnsignedByte();
        in.skipBytes(8); // RSV + ATYP + BND.ADDR + BND.PORT
        return rep;
    }

    /** 抓隧道下行二进制帧（服务端→runner 方向），可多帧 */
    private List<TunnelFrame> tunnelFrames() {
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        try {
            verify(ws, timeout(5000).atLeastOnce()).sendMessage(captor.capture());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        List<TunnelFrame> frames = new ArrayList<>();
        for (WebSocketMessage<?> m : captor.getAllValues()) {
            if (m instanceof BinaryMessage b) {
                // duplicate：同一帧可能被多次捕获/重复读，不消费原 buffer 位置
                java.nio.ByteBuffer buf = b.getPayload().duplicate();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                frames.add(TunnelFrame.decode(bytes));
            }
        }
        return frames;
    }

    /** 轮询等某类型帧出现（relay 线程异步发送，verify 只要任一帧存在即返回，必须轮询目标帧） */
    private TunnelFrame awaitFrame(int type, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var found = tunnelFrames().stream().filter(f -> f.type() == type).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("未等到帧类型 " + type);
    }

    @Test
    void unmatchedHostRejectedNotAllowed() throws Exception {
        when(ruleService.findRoute(anyString())).thenReturn(Optional.empty());
        try (Socket client = new Socket("127.0.0.1", port())) {
            int rep = handshake(client, "direct.example.com", 443);
            assertEquals(2, rep); // 规则表即白名单：未命中不放行
        }
        // CAP-71：未命中记 PROXY/REJECTED_NO_RULE（nodeId 空、result=REJECTED）
        ArgumentCaptor<AgentAuditLogService.Entry> cap =
                ArgumentCaptor.forClass(AgentAuditLogService.Entry.class);
        verify(auditLog, timeout(5000).atLeastOnce()).record(cap.capture());
        AgentAuditLogService.Entry e = cap.getAllValues().stream()
                .filter(x -> "REJECTED_NO_RULE".equals(x.event())).findFirst().orElseThrow();
        assertEquals(AgentAuditLogEntity.CAT_PROXY, e.category());
        assertEquals(AgentAuditLogEntity.RESULT_REJECTED, e.result());
        assertEquals("direct.example.com:443", e.target());
        assertTrue(e.detail().contains("socks5"));
    }

    @Test
    void matchedButTunnelBrokenRejectedUnreachable() throws Exception {
        EgressRuleEntity rule = new EgressRuleEntity();
        rule.setHostPattern("*.corp.com");
        rule.setNodeId(7L);
        when(ruleService.findRoute("dead.corp.com")).thenReturn(Optional.of(rule));
        registry.onDisconnect(7L, ws); // 隧道断开 → openStream 抛隧道未连接
        try (Socket client = new Socket("127.0.0.1", port())) {
            int rep = handshake(client, "dead.corp.com", 443);
            assertEquals(4, rep); // 命中但不可用：快速失败不挂起
        }
        // CAP-71：命中但不可用记 PROXY/FAIL_UNAVAILABLE（nodeId=7、result=FAIL）
        ArgumentCaptor<AgentAuditLogService.Entry> cap =
                ArgumentCaptor.forClass(AgentAuditLogService.Entry.class);
        verify(auditLog, timeout(5000).atLeastOnce()).record(cap.capture());
        AgentAuditLogService.Entry e = cap.getAllValues().stream()
                .filter(x -> "FAIL_UNAVAILABLE".equals(x.event())).findFirst().orElseThrow();
        assertEquals(AgentAuditLogEntity.CAT_PROXY, e.category());
        assertEquals(AgentAuditLogEntity.RESULT_FAIL, e.result());
        assertEquals(7L, e.nodeId());
        assertEquals("dead.corp.com:443", e.target());
    }

    /** 后台假 runner：等到 OPEN 帧后立即回 OPEN_ACK 成功，返回 OPEN 帧 */
    private Thread ackOnOpen(AtomicReference<TunnelFrame> openRef) {
        Thread acker = new Thread(() -> {
            try {
                while (openRef.get() == null) {
                    var frames = tunnelFrames();
                    clearInvocations(ws);
                    frames.stream().filter(f -> f.type() == TunnelFrame.TYPE_OPEN).findFirst()
                            .ifPresent(open -> {
                                openRef.set(open);
                                registry.onFrame(7L, ws, TunnelFrame.openAck(open.streamId(), null));
                            });
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        acker.setDaemon(true);
        acker.start();
        return acker;
    }

    @Test
    void fullRelayThroughTunnel() throws Exception {
        EgressRuleEntity rule = new EgressRuleEntity();
        rule.setHostPattern("*.corp.com");
        rule.setNodeId(7L);
        when(ruleService.findRoute("gitlab.corp.com")).thenReturn(Optional.of(rule));

        try (Socket client = new Socket("127.0.0.1", port())) {
            clearInvocations(ws);
            AtomicReference<TunnelFrame> openRef = new AtomicReference<>();
            Thread acker = ackOnOpen(openRef);
            int rep = handshake(client, "gitlab.corp.com", 443);
            acker.join(5000);
            assertEquals(0, rep);
            TunnelFrame open = openRef.get();
            assertEquals("gitlab.corp.com", open.host());
            assertEquals(443, open.port());
            assertTrue(open.streamId() > 0);
        }
    }

    @Test
    void dataRelayedBothDirectionsAndHalfClose() throws Exception {
        EgressRuleEntity rule = new EgressRuleEntity();
        rule.setHostPattern("*.corp.com");
        rule.setNodeId(7L);
        when(ruleService.findRoute("echo.corp.com")).thenReturn(Optional.of(rule));

        try (Socket client = new Socket("127.0.0.1", port())) {
            clearInvocations(ws);
            // 后台假 runner 等 OPEN 回 ACK，handshake 才能拿到 REP_OK
            AtomicReference<TunnelFrame> openRef = new AtomicReference<>();
            Thread acker = ackOnOpen(openRef);
            int rep = handshake(client, "echo.corp.com", 9);
            assertEquals(0, rep);
            acker.join(5000);
            int streamId = openRef.get().streamId();

            // 客户端 → 隧道：写 3 字节应出 DATA 帧
            clearInvocations(ws);
            client.getOutputStream().write("abc".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            TunnelFrame data = awaitFrame(TunnelFrame.TYPE_DATA, 5000);
            assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), data.payload());

            // 隧道 → 客户端：runner 侧 DATA 应落到 socket
            registry.onFrame(7L, ws, TunnelFrame.data(streamId,
                    "xyz".getBytes(StandardCharsets.UTF_8)));
            DataInputStream in = new DataInputStream(client.getInputStream());
            byte[] got = in.readNBytes(3);
            assertArrayEquals("xyz".getBytes(StandardCharsets.UTF_8), got);
            // 消费后应回 WINDOW credit（relay 线程异步发送，轮询等待）
            TunnelFrame win = awaitFrame(TunnelFrame.TYPE_WINDOW, 5000);
            assertEquals(3, win.windowBytes());

            // 客户端半关 → 服务端应发 CLOSE
            client.shutdownOutput();
            TunnelFrame close = awaitFrame(TunnelFrame.TYPE_CLOSE, 5000);
            assertEquals(streamId, close.streamId());
        }
    }

    /** CAP-71：正常双向收尾后恰好记一条 PROXY/OPEN（字节数=两方向实际搬运量、result=OK）。 */
    @Test
    void normalCloseAuditsExactlyOnceWithByteCounts() throws Exception {
        EgressRuleEntity rule = new EgressRuleEntity();
        rule.setHostPattern("*.corp.com");
        rule.setNodeId(7L);
        when(ruleService.findRoute("echo.corp.com")).thenReturn(Optional.of(rule));

        try (Socket client = new Socket("127.0.0.1", port())) {
            clearInvocations(ws);
            AtomicReference<TunnelFrame> openRef = new AtomicReference<>();
            Thread acker = ackOnOpen(openRef);
            assertEquals(0, handshake(client, "echo.corp.com", 9));
            acker.join(5000);
            int streamId = openRef.get().streamId();

            // 上行 3 字节 + 下行 3 字节
            client.getOutputStream().write("abc".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            awaitFrame(TunnelFrame.TYPE_DATA, 5000);
            registry.onFrame(7L, ws, TunnelFrame.data(streamId, "xyz".getBytes(StandardCharsets.UTF_8)));
            new DataInputStream(client.getInputStream()).readNBytes(3);

            // 双向收尾：本端 EOF → CLOSE；对端 CLOSE → POISON 排空 → terminate(null)
            client.shutdownOutput();
            awaitFrame(TunnelFrame.TYPE_CLOSE, 5000);
            registry.onFrame(7L, ws, TunnelFrame.close(streamId));

            ArgumentCaptor<AgentAuditLogService.Entry> cap =
                    ArgumentCaptor.forClass(AgentAuditLogService.Entry.class);
            verify(auditLog, timeout(5000).atLeastOnce()).record(cap.capture());
            List<AgentAuditLogService.Entry> opens = cap.getAllValues().stream()
                    .filter(x -> AgentAuditLogEntity.CAT_PROXY.equals(x.category())
                            && "OPEN".equals(x.event())).toList();
            assertEquals(1, opens.size(), "正常收尾应恰好记一条 PROXY/OPEN");
            AgentAuditLogService.Entry e = opens.get(0);
            assertEquals(AgentAuditLogEntity.RESULT_OK, e.result());
            assertEquals(7L, e.nodeId());
            assertEquals("echo.corp.com:9", e.target());
            assertEquals(3L, e.bytesUp());
            assertEquals(3L, e.bytesDown());
            assertTrue(e.detail().contains("socks5"));
        }
    }

    /** CAP-71：RST 中止也恰好记一条 PROXY/OPEN（result=FAIL、detail 带中止原因）。 */
    @Test
    void abortAuditsOnceWithFailResult() throws Exception {
        EgressRuleEntity rule = new EgressRuleEntity();
        rule.setHostPattern("*.corp.com");
        rule.setNodeId(7L);
        when(ruleService.findRoute("echo.corp.com")).thenReturn(Optional.of(rule));

        try (Socket client = new Socket("127.0.0.1", port())) {
            clearInvocations(ws);
            AtomicReference<TunnelFrame> openRef = new AtomicReference<>();
            Thread acker = ackOnOpen(openRef);
            assertEquals(0, handshake(client, "echo.corp.com", 9));
            acker.join(5000);
            int streamId = openRef.get().streamId();

            client.getOutputStream().write("ab".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            awaitFrame(TunnelFrame.TYPE_DATA, 5000);

            // runner 侧 RST → abort → 终结回调 FAIL
            registry.onFrame(7L, ws, TunnelFrame.rst(streamId, "目标不可达"));

            ArgumentCaptor<AgentAuditLogService.Entry> cap =
                    ArgumentCaptor.forClass(AgentAuditLogService.Entry.class);
            verify(auditLog, timeout(5000).atLeastOnce()).record(cap.capture());
            List<AgentAuditLogService.Entry> opens = cap.getAllValues().stream()
                    .filter(x -> AgentAuditLogEntity.CAT_PROXY.equals(x.category())
                            && "OPEN".equals(x.event())).toList();
            assertEquals(1, opens.size(), "RST 中止应恰好记一条 PROXY/OPEN");
            AgentAuditLogService.Entry e = opens.get(0);
            assertEquals(AgentAuditLogEntity.RESULT_FAIL, e.result());
            assertEquals(2L, e.bytesUp());
            assertTrue(e.detail() != null && e.detail().contains("socks5"));
        }
    }
}
