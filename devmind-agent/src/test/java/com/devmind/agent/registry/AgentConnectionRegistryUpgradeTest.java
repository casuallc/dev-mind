package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** sendUpgrade/onUpgradeAck：帧形状、ack 完成、并发互斥、断线收尾、超时。 */
class AgentConnectionRegistryUpgradeTest {

    private AgentConnectionRegistry registry;
    private WebSocketSession ws;
    private AgentProperties props;

    @BeforeEach
    void setUp() throws Exception {
        AgentNodeService nodeService = mock(AgentNodeService.class);
        props = new AgentProperties();
        @SuppressWarnings("unchecked")
        ObjectProvider<com.devmind.common.agent.AgentEventListener> listenerProvider =
                mock(ObjectProvider.class);
        registry = new AgentConnectionRegistry(nodeService, props, JsonMapper.builder().build(),
                listenerProvider);

        ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        AgentNodeEntity node = new AgentNodeEntity();
        node.setId(7L);
        node.setName("n7");
        registry.onConnect(node, ws);
    }

    private String lastSentPayload() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, atLeastOnce()).sendMessage(captor.capture());
        return captor.getValue().getPayload();
    }

    /** 后台线程模拟 runner 回 ack。 */
    private Thread ackLater(long delayMs, boolean ok, String reason, int active) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
            }
            registry.onUpgradeAck("7", ok, reason, active);
        });
        t.start();
        return t;
    }

    @Test
    void sendsUpgradeFrameAndReturnsAck() throws Exception {
        ackLater(50, true, null, 0);
        AgentConnectionRegistry.UpgradeAck ack = registry.sendUpgrade(7L, "0.2.0", "ab".repeat(32), 123);

        assertTrue(ack.ok());
        String payload = lastSentPayload();
        assertTrue(payload.contains("\"type\":\"upgrade\""), payload);
        assertTrue(payload.contains("\"version\":\"0.2.0\""), payload);
        assertTrue(payload.contains("\"sha256\":\"" + "ab".repeat(32) + "\""), payload);
        assertTrue(payload.contains("\"sizeBytes\":123"), payload);
    }

    @Test
    void busyAckCarriesActiveSessions() throws Exception {
        ackLater(50, false, "busy", 2);
        AgentConnectionRegistry.UpgradeAck ack = registry.sendUpgrade(7L, "0.2.0", "x", 1);
        assertFalse(ack.ok());
        assertEquals("busy", ack.reason());
        assertEquals(2, ack.activeSessions());
    }

    @Test
    void concurrentUpgradeOnSameNodeRejected() throws Exception {
        props.setUpgradeAckTimeoutMs(5_000);
        Thread slow = new Thread(() -> {
            try {
                registry.sendUpgrade(7L, "0.2.0", "x", 1);
            } catch (DevMindException ignored) {
                // 无人回 ack，超时预期内
            }
        });
        slow.start();
        Thread.sleep(100); // 等先者占坑
        assertThrows(DevMindException.class, () -> registry.sendUpgrade(7L, "0.2.0", "x", 1));
        slow.join(10_000);
    }

    @Test
    void timeoutWithoutAckThrowsConflict() {
        props.setUpgradeAckTimeoutMs(50);
        DevMindException e = assertThrows(DevMindException.class,
                () -> registry.sendUpgrade(7L, "0.2.0", "x", 1));
        assertEquals(409, e.getErrorCode().getStatus());
    }

    @Test
    void disconnectCompletesPendingUpgrade() throws Exception {
        Thread t = new Thread(() -> {
            try {
                registry.sendUpgrade(7L, "0.2.0", "x", 1);
            } catch (DevMindException ignored) {
            }
        });
        t.start();
        Thread.sleep(100);
        AgentNodeEntity node = new AgentNodeEntity();
        node.setId(7L);
        node.setName("n7");
        registry.onDisconnect(node, ws);
        t.join(10_000);
        assertFalse(t.isAlive(), "断线后 sendUpgrade 应立即返回而非等满超时");
    }

    @Test
    void offlineNodeRejected() {
        registry.evict(7L);
        assertThrows(DevMindException.class, () -> registry.sendUpgrade(7L, "0.2.0", "x", 1));
    }
}
