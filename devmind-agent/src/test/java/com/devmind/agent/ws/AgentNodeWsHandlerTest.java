package com.devmind.agent.ws;

import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.registry.AgentConnectionRegistry;
import com.devmind.agent.service.AgentNodeService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** upgrade_ack 帧路由：参数正确转发到 registry。 */
class AgentNodeWsHandlerTest {

    @Test
    void routesUpgradeAckToRegistry() throws Exception {
        AgentNodeService nodeService = mock(AgentNodeService.class);
        AgentConnectionRegistry registry = mock(AgentConnectionRegistry.class);
        AgentNodeWsHandler handler = new AgentNodeWsHandler(nodeService, registry,
                JsonMapper.builder().build());

        AgentNodeEntity node = new AgentNodeEntity();
        node.setId(7L);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("agentNode", node);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(attrs);

        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"upgrade_ack\",\"ok\":false,\"reason\":\"busy\",\"activeSessions\":3}"));

        verify(registry).onUpgradeAck("7", false, "busy", 3);
    }
}
