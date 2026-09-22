package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.WorkspaceQueryResult;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-54 workspace_query 帧链路：组帧形状（action/repo/path 可选字段）→ ack 完成等待
 * → 协议 v11 门控（老 runner 静默忽略未知帧，必须 409 而不是挂到超时）→ 断连批量失败。
 */
class AgentConnectionRegistryWorkspaceQueryTest {

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
                JsonMapper.builder().build(), listenerProvider, mock(ObjectProvider.class),
                mock(AgentConnLogService.class));
        ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        node = new AgentNodeEntity();
        node.setId(7L);
        node.setName("n7");
        registry.onConnect(node, ws);
        // v11 runner（hello 上报协议版本）
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.2.0", null, 11, null, null),
                List.of());
    }

    private record Call(AtomicReference<WorkspaceQueryResult> result, AtomicReference<Throwable> error,
                        Thread thread, String payload) {
    }

    private Call startQuery(String action, String repo, String path) throws Exception {
        AtomicReference<WorkspaceQueryResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                result.set(registry.workspaceQuery("7", "s1", action, repo, path));
            } catch (Throwable e) {
                error.set(e);
            }
        });
        // 清掉此前帧的调用记录：否则 timeout 校验会被「上一次已经发过帧」直接满足
        org.mockito.Mockito.clearInvocations(ws);
        t.start();
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, timeout(5000).atLeastOnce()).sendMessage(captor.capture());
        return new Call(result, error, t, captor.getValue().getPayload());
    }

    private static String requestIdOf(String payload) {
        String marker = "\"requestId\":\"";
        int i = payload.indexOf(marker);
        return payload.substring(i + marker.length(), payload.indexOf('"', i + marker.length()));
    }

    @Test
    void frameShapeAndAckRouting() throws Exception {
        Call call = startQuery("file", "backend", "src/A.java");
        assertTrue(call.payload().contains("\"type\":\"workspace_query\""), call.payload());
        assertTrue(call.payload().contains("\"sessionId\":\"s1\""), call.payload());
        assertTrue(call.payload().contains("\"action\":\"file\""), call.payload());
        assertTrue(call.payload().contains("\"repo\":\"backend\""), call.payload());
        assertTrue(call.payload().contains("\"path\":\"src/A.java\""), call.payload());
        assertTrue(requestIdOf(call.payload()).startsWith("wq-"), call.payload());

        registry.onWorkspaceQueryAck("7", requestIdOf(call.payload()), true,
                Map.of("content", "hello", "size", 5), null);
        call.thread().join(10_000);
        WorkspaceQueryResult r = call.result().get();
        assertTrue(r != null && r.ok(), String.valueOf(call.error().get()));
        assertEquals("hello", r.payload().get("content"));
    }

    @Test
    void optionalFieldsOmittedWhenBlank() throws Exception {
        Call call = startQuery("tree", null, null);
        assertFalse(call.payload().contains("\"repo\""), call.payload());
        assertFalse(call.payload().contains("\"path\""), call.payload());
        registry.onWorkspaceQueryAck("7", requestIdOf(call.payload()), true, Map.of(), null);
        call.thread().join(10_000);
        assertTrue(call.result().get() != null && call.result().get().ok());
    }

    @Test
    void errorAckCompletesWithFailure() throws Exception {
        Call call = startQuery("file", null, "a/b.txt");
        registry.onWorkspaceQueryAck("7", requestIdOf(call.payload()), false, null, "路径越界");
        call.thread().join(10_000);
        WorkspaceQueryResult r = call.result().get();
        assertTrue(r != null && !r.ok());
        assertEquals("路径越界", r.error());
    }

    @Test
    void unknownRequestIdIgnored() {
        // 迟到的 ack（等待已超时/断连清理后）不炸不攒
        registry.onWorkspaceQueryAck("7", "wq-nonexistent", true, Map.of(), null);
    }

    @Test
    void legacyRunnerIsRejectedByVersionGate() {
        // v10 runner 不认识 workspace_query → 409 门控，不静默下发挂到超时
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.2.0", null, 10, null, null),
                List.of());
        var e = assertThrows(DevMindException.class,
                () -> registry.workspaceQuery("7", "s1", "status", null, null));
        assertTrue(e.getMessage().contains("v11"), e.getMessage());
    }

    @Test
    void disconnectFailsPendingQueries() throws Exception {
        Call call = startQuery("status", null, null);
        registry.onDisconnect(node, ws);
        call.thread().join(10_000);
        // 断连清理完成一个失败结果（不抛异常）：REST 层把 error 透传为 409 文案
        WorkspaceQueryResult r = call.result().get();
        assertTrue(r != null && !r.ok(), String.valueOf(call.error().get()));
        assertTrue(r.error() != null && !r.error().isBlank());
    }
}
