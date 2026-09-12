package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentCollectResult;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CAP-39 collect_output 帧：下行帧形状、版本门控（v4+）、ack 收口、断连失败。 */
class AgentConnectionRegistryCollectOutputTest {

    private AgentConnectionRegistry registry;
    private WebSocketSession ws;
    private AgentNodeEntity node;

    @BeforeEach
    void setUp() {
        AgentNodeService nodeService = mock(AgentNodeService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.devmind.common.agent.AgentEventListener> listenerProvider =
                mock(ObjectProvider.class);
        registry = new AgentConnectionRegistry(nodeService, new AgentProperties(),
                JsonMapper.builder().build(), listenerProvider, mock(AgentConnLogService.class));

        ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        node = new AgentNodeEntity();
        node.setId(7L);
        node.setName("n7");
        registry.onConnect(node, ws);
    }

    private void helloWithProtocol(Integer version) {
        registry.onHello(node, new AgentHelloMeta("os", "claude", "1.0", null, version, null, null),
                java.util.List.of());
    }

    @Test
    void sendsCollectFrameAndCompletesOnAck() throws Exception {
        helloWithProtocol(4);
        Thread acker = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            registry.onOutputCollectedAck("s1", true, null);
        });
        acker.start();
        AgentCollectResult result = registry.collectOutput("7", "s1");
        acker.join(10_000);

        assertTrue(result.ok());
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, atLeastOnce()).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertTrue(payload.contains("\"type\":\"collect_output\""), payload);
        assertTrue(payload.contains("\"sessionId\":\"s1\""), payload);
    }

    @Test
    void ackErrorPropagates() {
        helloWithProtocol(4);
        Thread acker = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            registry.onOutputCollectedAck("s1", false, "会话不在本节点运行");
        });
        acker.start();
        AgentCollectResult result = registry.collectOutput("7", "s1");
        assertFalse(result.ok());
        assertEquals("会话不在本节点运行", result.error());
    }

    @Test
    void rejectsOldRunnerByProtocolGate() {
        // 无 hello 版本记录按 v1 对待
        DevMindException e = assertThrows(DevMindException.class, () -> registry.collectOutput("7", "s1"));
        assertTrue(e.getMessage().contains("升级"), e.getMessage());
        // hello 报 v3（exec 时代 runner）同样被拒
        helloWithProtocol(3);
        assertThrows(DevMindException.class, () -> registry.collectOutput("7", "s1"));
    }

    @Test
    void disconnectFailsPendingCollect() {
        helloWithProtocol(4);
        Thread caller = new Thread(() -> {
            try {
                registry.collectOutput("7", "s1");
            } catch (DevMindException ignored) {
                // 断连 ack(ok=false) 不抛，这里只兜异常路径
            }
        });
        caller.start();
        // 等 caller 进入等待后断连
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
        registry.onDisconnect(node, ws);
        try {
            caller.join(10_000);
        } catch (InterruptedException ignored) {
        }
        assertFalse(caller.isAlive(), "断连后 collectOutput 应立即返回，不得挂死");
    }
}
