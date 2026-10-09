package com.devmind.agent.tunnel;

import com.devmind.agent.config.EgressProperties;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.EgressRuleService;
import com.devmind.common.agent.AgentProtocol;
import com.devmind.common.egress.TunnelFrame;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-70 FR-01/04：隧道注册表——tunnel_hello 快照下发与重推、OPEN/OPEN_ACK waiter、
 * 离线/被拒/超时 fail-visible、伪造帧断连自保、新连接顶旧连接。
 */
class AgentTunnelRegistryTest {

    private EgressRuleService ruleService;
    private EgressProperties props;
    private AgentTunnelRegistry registry;
    private WebSocketSession ws;
    private AgentNodeEntity node;

    @BeforeEach
    void setUp() {
        ruleService = mock(EgressRuleService.class);
        props = new EgressProperties();
        props.setOpenTimeoutMs(800);
        registry = new AgentTunnelRegistry(ruleService, props, JsonMapper.builder().build());
        when(ruleService.allowedHostsFor(7L)).thenReturn(List.of("*.corp.com", "gitlab.intra"));
        ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        node = new AgentNodeEntity();
        node.setId(7L);
        node.setName("n7");
        registry.onConnect(node, ws);
    }

    /** 抓下一帧二进制流帧（清掉 tunnel_hello 等既有调用记录后调用） */
    private TunnelFrame nextBinaryFrame() throws Exception {
        clearInvocations(ws);
        return framesSinceLastClear().stream().findFirst()
                .orElseThrow(() -> new AssertionError("未捕获到二进制帧"));
    }

    /** 抓自上次 clearInvocations 以来的全部二进制流帧（不清记录——帧可能已先于调用发出） */
    private java.util.List<TunnelFrame> framesSinceLastClear() throws Exception {
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(ws, timeout(5000).atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream()
                .filter(m -> m instanceof BinaryMessage)
                .map(m -> {
                    // duplicate：同一帧可能被多次捕获/重复读，不消费原 buffer 位置
                    java.nio.ByteBuffer buf = ((BinaryMessage) m).getPayload().duplicate();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    return TunnelFrame.decode(bytes);
                })
                .toList();
    }

    @Test
    void connectPushesTunnelHelloSnapshot() throws Exception {
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(ws, timeout(5000).atLeastOnce()).sendMessage(captor.capture());
        TextMessage hello = captor.getAllValues().stream()
                .filter(m -> m instanceof TextMessage)
                .map(m -> (TextMessage) m).findFirst()
                .orElseThrow(() -> new AssertionError("未下发 tunnel_hello"));
        String json = hello.getPayload();
        assertTrue(json.contains("\"type\":\"tunnel_hello\""), json);
        assertTrue(json.contains("\"protocolVersion\":" + AgentProtocol.EGRESS_TUNNEL), json);
        assertTrue(json.contains("*.corp.com"), json);
        assertTrue(json.contains("gitlab.intra"), json);
    }

    @Test
    void rulesChangedRePushesSnapshot() throws Exception {
        clearInvocations(ws);
        registry.onRulesChanged(new EgressRuleService.EgressRulesChangedEvent());
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(ws, timeout(5000).atLeastOnce()).sendMessage(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(m -> m instanceof TextMessage
                && ((TextMessage) m).getPayload().contains("tunnel_hello")));
    }

    @Test
    void openStreamSendsOpenAndUnblocksOnAck() throws Exception {
        AtomicReference<ServerTunnelStream> opened = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        try (Socket socket = new Socket()) {
            Thread t = new Thread(() -> {
                try {
                    opened.set(registry.openStream(7L, "gitlab.corp.com", 443, socket));
                } catch (Throwable e) {
                    error.set(e);
                }
            });
            t.start();
            TunnelFrame open = nextBinaryFrame();
            assertEquals(TunnelFrame.TYPE_OPEN, open.type());
            assertEquals("gitlab.corp.com", open.host());
            assertEquals(443, open.port());
            registry.onFrame(7L, ws, TunnelFrame.openAck(open.streamId(), null));
            t.join(5000);
            assertEquals(null, error.get());
            assertNotNull(opened.get());
            assertEquals(open.streamId(), opened.get().streamId());
            assertTrue(registry.tunnelOnline(7L));
        }
    }

    @Test
    void openStreamRejectedByRunnerFailsVisible() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        try (Socket socket = new Socket()) {
            Thread t = new Thread(() -> {
                try {
                    registry.openStream(7L, "evil.corp.com", 443, socket);
                } catch (Throwable e) {
                    error.set(e);
                }
            });
            t.start();
            TunnelFrame open = nextBinaryFrame();
            registry.onFrame(7L, ws, TunnelFrame.openAck(open.streamId(), "目标不在白名单"));
            t.join(5000);
            DevMindException ex = assertInstanceOf(DevMindException.class, error.get());
            assertTrue(ex.getMessage().contains("目标不在白名单"), ex.getMessage());
        }
    }

    @Test
    void openStreamTimeoutRstsStreamAndFails() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        try (Socket socket = new Socket()) {
            Thread t = new Thread(() -> {
                try {
                    registry.openStream(7L, "slow.corp.com", 443, socket);
                } catch (Throwable e) {
                    error.set(e);
                }
            });
            t.start();
            TunnelFrame open = nextBinaryFrame();
            t.join(5000);
            DevMindException ex = assertInstanceOf(DevMindException.class, error.get());
            assertTrue(ex.getMessage().contains("超时"), ex.getMessage());
            // 超时后补发 RST 通知 runner 丢弃该流（RST 在 join 前已发出，用不清记录的抓帧并滤掉 OPEN）
            TunnelFrame rst = framesSinceLastClear().stream()
                    .filter(f -> f.type() == TunnelFrame.TYPE_RST).findFirst()
                    .orElseThrow(() -> new AssertionError("超时未补发 RST"));
            assertEquals(TunnelFrame.TYPE_RST, rst.type());
            assertEquals(open.streamId(), rst.streamId());
        }
    }

    @Test
    void openStreamTimeoutDropsSilentTunnel() throws Exception {
        // OPEN_ACK 全静默超时 = 静默断链判定：隧道摘除（后续请求快速失败）+ 会话关闭等 runner 重连
        AtomicReference<Throwable> error = new AtomicReference<>();
        try (Socket socket = new Socket()) {
            Thread t = new Thread(() -> {
                try {
                    registry.openStream(7L, "slow.corp.com", 443, socket);
                } catch (Throwable e) {
                    error.set(e);
                }
            });
            t.start();
            nextBinaryFrame();
            t.join(5000);
            assertInstanceOf(DevMindException.class, error.get());
            assertTrue(!registry.tunnelOnline(7L), "超时静默应摘掉隧道");
            verify(ws, timeout(5000)).close();
            // 摘除后新请求走「隧道未连接」快速失败，不再各挂一个 OPEN 超时
            try (Socket socket2 = new Socket()) {
                DevMindException ex = assertThrows(DevMindException.class,
                        () -> registry.openStream(7L, "x.corp.com", 443, socket2));
                assertTrue(ex.getMessage().contains("隧道未连接"), ex.getMessage());
            }
        }
    }

    @Test
    void openStreamWithoutTunnelFailsFast() {
        try (Socket socket = new Socket()) {
            DevMindException ex = assertThrows(DevMindException.class,
                    () -> registry.openStream(99L, "x.corp.com", 443, socket));
            assertTrue(ex.getMessage().contains("隧道未连接"), ex.getMessage());
        } catch (java.io.IOException ignored) {
        }
    }

    @Test
    void forgedOpenFrameClosesTunnel() throws Exception {
        // OPEN 只应由服务端发起；runner 发来即伪造帧 → 断隧道自保
        registry.onFrame(7L, ws, TunnelFrame.open(1, "x", 80));
        verify(ws, timeout(5000)).close();
    }

    @Test
    void reconnectReplacesOldTunnel() {
        WebSocketSession ws2 = mock(WebSocketSession.class);
        when(ws2.isOpen()).thenReturn(true);
        registry.onConnect(node, ws2);
        // 旧连接的迟到断连事件不得摘掉新连接
        registry.onDisconnect(7L, ws);
        assertTrue(registry.tunnelOnline(7L));
        registry.onDisconnect(7L, ws2);
        assertTrue(!registry.tunnelOnline(7L));
        verify(ruleService, timeout(5000).atLeastOnce()).allowedHostsFor(7L);
    }

    @Test
    void disconnectAbortsPendingOpen() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        Socket socket = new Socket();
        Thread t = new Thread(() -> {
            try {
                registry.openStream(7L, "pending.corp.com", 443, socket);
            } catch (Throwable e) {
                error.set(e);
            }
        });
        t.start();
        nextBinaryFrame();
        registry.onDisconnect(7L, ws);
        t.join(5000);
        assertNotNull(error.get());
        assertTrue(socket.isClosed());
    }
}
