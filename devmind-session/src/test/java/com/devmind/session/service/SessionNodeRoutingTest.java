package com.devmind.session.service;

import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CAP-34 FR-07 + CAP-33 会话节点路由（{@link SessionManagerService#routeAgentNode}）：
 * 显式 > 场景预设 > 项目默认 > 平台默认；显式节点标签不符 409；默认链逐级门控；
 * 皆不符按标签在线兜底；仍无命中 409。
 */
class SessionNodeRoutingTest {

    @SuppressWarnings("unchecked")
    private static AgentNodeConnector connector(java.util.function.BiFunction<String, List<String>, Boolean> matches,
                                                java.util.function.Function<List<String>, String> picker) {
        InvocationHandler h = (p, m, args) -> switch (m.getName()) {
            case "nodeMatches" -> matches.apply((String) args[0], (List<String>) args[1]);
            case "pickNodeByLabels" -> picker.apply((List<String>) args[0]);
            default -> throw new UnsupportedOperationException(m.getName());
        };
        return (AgentNodeConnector) Proxy.newProxyInstance(
                AgentNodeConnector.class.getClassLoader(), new Class<?>[]{AgentNodeConnector.class}, h);
    }

    @Test
    void 显式节点标签不符409() {
        AgentNodeConnector c = connector((id, req) -> false, req -> null);
        DevMindException e = assertThrows(DevMindException.class, () ->
                SessionManagerService.routeAgentNode("7", null, null, null, List.of("mvn"), c, "mvn"));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("不满足标签要求"));
    }

    @Test
    void 显式节点标签匹配直取() {
        AgentNodeConnector c = connector((id, req) -> true, req -> null);
        assertEquals("7", SessionManagerService.routeAgentNode("7", "2", "3", "9", List.of("mvn"), c, "mvn"));
    }

    @Test
    void 场景预设夹在显式与项目默认之间() {
        AgentNodeConnector c = connector((id, req) -> "2".equals(id), req -> null);
        // 场景预设节点标签匹配 → 取场景预设，不落项目默认
        assertEquals("2", SessionManagerService.routeAgentNode(null, "2", "3", "9", List.of("mvn"), c, "mvn"));
    }

    @Test
    void 场景预设不符落项目默认() {
        AgentNodeConnector c = connector((id, req) -> "3".equals(id), req -> null);
        assertEquals("3", SessionManagerService.routeAgentNode(null, "2", "3", "9", List.of("mvn"), c, "mvn"));
    }

    @Test
    void 默认链逐级门控_项目默认不符落平台默认() {
        AgentNodeConnector c = connector((id, req) -> "9".equals(id), req -> null);
        assertEquals("9", SessionManagerService.routeAgentNode(null, "2", "3", "9", List.of("mvn"), c, "mvn"));
    }

    @Test
    void 默认链皆不符按标签在线兜底() {
        AgentNodeConnector c = connector((id, req) -> false, req -> "5");
        assertEquals("5", SessionManagerService.routeAgentNode(null, "2", "3", "9", List.of("mvn"), c, "mvn"));
    }

    @Test
    void 无标签要求不兜底_默认链空即409() {
        AgentNodeConnector c = connector((id, req) -> true, req -> {
            throw new IllegalStateException("无标签要求不应调 pickNodeByLabels");
        });
        assertThrows(DevMindException.class, () ->
                SessionManagerService.routeAgentNode(null, null, null, null, List.of(), c, null));
    }

    @Test
    void 有标签要求仍无命中409() {
        AgentNodeConnector c = connector((id, req) -> false, req -> null);
        DevMindException e = assertThrows(DevMindException.class, () ->
                SessionManagerService.routeAgentNode(null, null, null, "9", List.of("mvn"), c, "mvn"));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("无满足标签的在线节点"));
    }
}
