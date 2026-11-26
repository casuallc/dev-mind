package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentAuditLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * tunnel_reset 帧（协议 v27 隧道人工重建）：帧型钉死防「新指令拼帧静默丢失」同类事故
 * （launch 帧手工拼帧三次事故教训）；离线节点 409 不放帧。
 */
class AgentConnectionRegistryTunnelResetTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private AgentConnectionRegistry registry;
    private WebSocketSession ws;

    @BeforeEach
    void setUp() {
        AgentNodeService nodeService = mock(AgentNodeService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.devmind.common.agent.AgentEventListener> listenerProvider =
                mock(ObjectProvider.class);
        registry = new AgentConnectionRegistry(nodeService, new AgentProperties(),
                MAPPER, listenerProvider, mock(ObjectProvider.class), mock(AgentAuditLogService.class));
        ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        AgentNodeEntity node = new AgentNodeEntity();
        node.setId(7L);
        node.setName("n7");
        registry.onConnect(node, ws);
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.2.0", null,
                com.devmind.common.agent.AgentProtocol.CURRENT, null, null), List.of());
    }

    @Test
    void tunnelResetFrameCarriesTypeOnly() throws Exception {
        registry.sendTunnelReset("7");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        JsonNode frame = MAPPER.readTree(captor.getValue().getPayload());
        assertEquals("tunnel_reset", frame.path("type").asText());
        assertFalse(frame.has("sessionId"), "隧道重建是节点级指令，不带 sessionId");
    }

    @Test
    void tunnelResetOfflineNodeRejects() {
        assertThrows(DevMindException.class, () -> registry.sendTunnelReset("99"),
                "离线节点必须 409 而不是静默放帧");
    }
}
