package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * CAP-34 FR-08 协议版本门控：hello 携带 protocolVersion 登记 → supports 生效；
 * 未上报的老 runner 按 v1 对待（supports(2)=false、supports(1)=true）。
 */
class AgentConnectionRegistryProtocolTest {

    @SuppressWarnings("unchecked")
    private static AgentConnectionRegistry newRegistry() {
        return new AgentConnectionRegistry(mock(AgentNodeService.class), mock(AgentProperties.class),
                JsonMapper.builder().build(), mock(ObjectProvider.class), mock(AgentConnLogService.class));
    }

    private static AgentNodeEntity node(long id) {
        AgentNodeEntity e = new AgentNodeEntity();
        e.setId(id);
        return e;
    }

    private static AgentHelloMeta meta(Integer protocolVersion) {
        return new AgentHelloMeta("Windows 11 / amd64", "claude", "20260908.1",
                null, protocolVersion, null, null);
    }

    @Test
    void hello上报v2后supports命中() {
        AgentConnectionRegistry registry = newRegistry();
        registry.onHello(node(1L), meta(2), List.of());

        assertTrue(registry.supports("1", 2));
        assertTrue(registry.supports("1", 1));
        assertFalse(registry.supports("1", 3));
    }

    @Test
    void 无版本记录按v1对待() {
        AgentConnectionRegistry registry = newRegistry();

        assertTrue(registry.supports("9", 1));
        assertFalse(registry.supports("9", 2));

        // 老 runner 的 hello（无 protocolVersion）登记后仍按 v1，且清掉旧记录
        registry.onHello(node(9L), meta(2), List.of());
        registry.onHello(node(9L), meta(null), List.of());
        assertFalse(registry.supports("9", 2));
    }

    @Test
    void 断连清除版本记录() {
        AgentConnectionRegistry registry = newRegistry();
        AgentNodeEntity node = node(2L);
        registry.onHello(node, meta(2), List.of());
        assertTrue(registry.supports("2", 2));

        // 只清自己这条连接：未经 onConnect 的陌生连接关闭不清记录
        registry.onDisconnect(node, mock(org.springframework.web.socket.WebSocketSession.class));
        assertTrue(registry.supports("2", 2), "陌生连接关闭不应清记录");
    }
}
