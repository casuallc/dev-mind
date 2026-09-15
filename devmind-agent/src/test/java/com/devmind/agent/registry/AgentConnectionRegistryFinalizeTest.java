package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.FinalizeResult;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
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
 * CAP-42 workspace_finalize 帧链路：组帧形状（repos 数组含 token）→ ack 完成等待 →
 * 断连批量失败 → 协议 v7 门控（老 runner 409）。
 */
class AgentConnectionRegistryFinalizeTest {

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
        // v7 runner（hello 上报协议版本）
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.2.0", null, 7, null, null),
                List.of());
    }

    private static List<AgentLaunchCommand.RepoSpec> specs() {
        return List.of(
                new AgentLaunchCommand.RepoSpec("https://git/a.git", "main", "feature/s1", "tok-a", "backend"),
                new AgentLaunchCommand.RepoSpec("https://git/b.git", "main", "feature/s1", "tok-b", "web"));
    }

    /** 另起线程跑 finalize（阻塞等 ack），返回帧 payload 与结果容器。 */
    private record Call(AtomicReference<FinalizeResult> result, AtomicReference<Throwable> error,
                        Thread thread, String payload) {
    }

    private Call startFinalize() throws Exception {
        AtomicReference<FinalizeResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                result.set(registry.finalizeWorkspace("7", "s1", "proj1", "alice", specs(), true));
            } catch (Throwable e) {
                error.set(e);
            }
        });
        t.start();
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, timeout(5000).atLeastOnce()).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        return new Call(result, error, t, payload);
    }

    private static String requestIdOf(String payload) {
        String marker = "\"requestId\":\"";
        int i = payload.indexOf(marker);
        return payload.substring(i + marker.length(), payload.indexOf('"', i + marker.length()));
    }

    @Test
    void serializesFinalizeFrameAndCompletesOnAck() throws Exception {
        Call call = startFinalize();
        String payload = call.payload();
        assertTrue(payload.contains("\"type\":\"workspace_finalize\""), payload);
        assertTrue(payload.contains("\"sessionId\":\"s1\""), payload);
        assertTrue(payload.contains("\"projectId\":\"proj1\""), payload);
        assertTrue(payload.contains("\"workspaceOwner\":\"alice\""), payload);
        assertTrue(payload.contains("\"discardChanges\":true"), payload);
        // repos 数组逐库带 name/baseBranch/branch/token（token 仅随帧传输）
        assertTrue(payload.contains("\"name\":\"backend\""), payload);
        assertTrue(payload.contains("\"name\":\"web\""), payload);
        assertTrue(payload.contains("\"token\":\"tok-b\""), payload);
        assertTrue(payload.contains("\"branch\":\"feature/s1\""), payload);

        registry.onWorkspaceFinalizeAck("7", requestIdOf(payload), true, "合并完成", null);
        call.thread().join(10_000);
        assertTrue(call.result().get() != null && call.result().get().ok(),
                String.valueOf(call.error().get()));
        assertEquals("合并完成", call.result().get().detail());
    }

    @Test
    void failedAckSurfacesError() throws Exception {
        Call call = startFinalize();
        registry.onWorkspaceFinalizeAck("7", requestIdOf(call.payload()), false, null, "合并冲突");
        call.thread().join(10_000);
        FinalizeResult r = call.result().get();
        assertFalse(r.ok());
        assertEquals("合并冲突", r.error());
    }

    @Test
    void disconnectFailsPendingFinalize() throws Exception {
        Call call = startFinalize();
        registry.onDisconnect(node, ws);
        call.thread().join(10_000);
        FinalizeResult r = call.result().get();
        assertTrue(r != null && !r.ok(), String.valueOf(call.error().get()));
        assertTrue(r.error().contains("断连"), r.error());
    }

    @Test
    void legacyRunnerIsRejectedByVersionGate() {
        // 无 hello 版本记录 = v1 老 runner → 409 门控（不下发帧）
        AgentConnectionRegistry fresh = new AgentConnectionRegistry(mock(AgentNodeService.class),
                new AgentProperties(), JsonMapper.builder().build(),
                mock(ObjectProvider.class), mock(AgentConnLogService.class));
        WebSocketSession ws2 = mock(WebSocketSession.class);
        when(ws2.isOpen()).thenReturn(true);
        AgentNodeEntity n2 = new AgentNodeEntity();
        n2.setId(8L);
        n2.setName("n8");
        fresh.onConnect(n2, ws2);
        var e = assertThrows(DevMindException.class,
                () -> fresh.finalizeWorkspace("8", "s1", "proj1", "alice", specs(), false));
        assertTrue(e.getMessage().contains("v7"), e.getMessage());
    }
}
