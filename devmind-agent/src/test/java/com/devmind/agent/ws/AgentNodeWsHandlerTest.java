package com.devmind.agent.ws;

import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.registry.AgentConnectionRegistry;
import com.devmind.agent.service.AgentConnLogService;
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

/** upgrade_ack 帧路由：参数正确转发到 registry；无效 token 拒绝并落连接日志。 */
class AgentNodeWsHandlerTest {

    @Test
    void routesUpgradeAckToRegistry() throws Exception {
        AgentNodeService nodeService = mock(AgentNodeService.class);
        AgentConnectionRegistry registry = mock(AgentConnectionRegistry.class);
        AgentNodeWsHandler handler = new AgentNodeWsHandler(nodeService, registry,
                mock(AgentConnLogService.class), JsonMapper.builder().build());

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

    /** token 无效/节点禁用：拒绝并记录 REJECT 流水（节点为 null、带来源地址）。 */
    @Test
    void rejectsUnknownTokenAndLogs() throws Exception {
        AgentNodeService nodeService = mock(AgentNodeService.class);
        AgentConnectionRegistry registry = mock(AgentConnectionRegistry.class);
        AgentConnLogService connLogService = mock(AgentConnLogService.class);
        AgentNodeWsHandler handler = new AgentNodeWsHandler(nodeService, registry,
                connLogService, JsonMapper.builder().build());

        when(nodeService.resolveByToken("bad")).thenReturn(java.util.Optional.empty());
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(new java.net.URI("ws://localhost/ws/agent?token=bad"));
        when(session.getRemoteAddress()).thenReturn(new java.net.InetSocketAddress("172.21.61.60", 11422));

        handler.afterConnectionEstablished(session);

        verify(session).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
        verify(connLogService).record(com.devmind.agent.model.AgentConnLogEntity.EVENT_REJECT,
                null, "172.21.61.60:11422", "token 无效或节点已禁用");
    }
}
