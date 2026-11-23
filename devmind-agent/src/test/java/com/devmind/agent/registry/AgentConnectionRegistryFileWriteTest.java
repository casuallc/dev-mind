package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentAuditLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentProtocol;
import com.devmind.common.agent.WorkspaceFileWriteResult;
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
 * CAP-115 M2 workspace_file_write 帧链路：组帧形状（sessionId=workspaceKey 伪会话、
 * expectedSha 可选）→ ack 完成等待（ok/conflict/failed 三分支）→ 协议 v26 门控
 * （老 runner 静默忽略未知帧，必须 409 而不是挂到超时）→ 断连批量失败。
 */
class AgentConnectionRegistryFileWriteTest {

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
                mock(AgentAuditLogService.class));
        ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        node = new AgentNodeEntity();
        node.setId(7L);
        node.setName("n7");
        registry.onConnect(node, ws);
        // v26 runner（hello 上报协议版本）
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.2.0", null,
                AgentProtocol.CURRENT, null, null), List.of());
    }

    private record Call(AtomicReference<WorkspaceFileWriteResult> result,
                        AtomicReference<Throwable> error, Thread thread, String payload) {
    }

    private Call startWrite(String path, String expectedSha) throws Exception {
        AtomicReference<WorkspaceFileWriteResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                result.set(registry.workspaceFileWrite("7", "term-abc12345", "p1", "u1",
                        path, "aGk=", expectedSha));
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
    void frameShapeAndOkAck() throws Exception {
        Call call = startWrite("src/A.java", "sha-old");
        assertTrue(call.payload().contains("\"type\":\"workspace_file_write\""), call.payload());
        assertTrue(call.payload().contains("\"sessionId\":\"term-abc12345\""), call.payload());
        assertTrue(call.payload().contains("\"workspaceKey\":\"term-abc12345\""), call.payload());
        assertTrue(call.payload().contains("\"path\":\"src/A.java\""), call.payload());
        assertTrue(call.payload().contains("\"contentBase64\":\"aGk=\""), call.payload());
        assertTrue(call.payload().contains("\"expectedSha\":\"sha-old\""), call.payload());
        assertTrue(requestIdOf(call.payload()).startsWith("wfw-"), call.payload());

        registry.onWorkspaceFileWriteAck("7", requestIdOf(call.payload()), true,
                "src/A.java", "sha-new", false, null);
        call.thread().join(10_000);
        WorkspaceFileWriteResult r = call.result().get();
        assertTrue(r != null && r.ok(), String.valueOf(call.error().get()));
        assertEquals("sha-new", r.sha());
        assertEquals("src/A.java", r.path());
    }

    @Test
    void expectedShaOmittedWhenBlank() throws Exception {
        Call call = startWrite("a.txt", null);
        assertFalse(call.payload().contains("\"expectedSha\""), call.payload());
        registry.onWorkspaceFileWriteAck("7", requestIdOf(call.payload()), true, "a.txt", "s",
                false, null);
        call.thread().join(10_000);
        assertTrue(call.result().get() != null && call.result().get().ok());
    }

    @Test
    void conflictAckCompletesWithConflict() throws Exception {
        Call call = startWrite("a.txt", "sha-stale");
        registry.onWorkspaceFileWriteAck("7", requestIdOf(call.payload()), false, null,
                "sha-current", true, "文件已被修改");
        call.thread().join(10_000);
        WorkspaceFileWriteResult r = call.result().get();
        assertTrue(r != null && !r.ok() && r.conflict());
        assertEquals("sha-current", r.sha());
    }

    @Test
    void errorAckCompletesWithFailure() throws Exception {
        Call call = startWrite("../x", null);
        registry.onWorkspaceFileWriteAck("7", requestIdOf(call.payload()), false, null, null,
                false, "路径越界");
        call.thread().join(10_000);
        WorkspaceFileWriteResult r = call.result().get();
        assertTrue(r != null && !r.ok() && !r.conflict());
        assertEquals("路径越界", r.error());
    }

    @Test
    void unknownRequestIdIgnored() {
        // 迟到的 ack（等待已超时/断连清理后）不炸不攒
        registry.onWorkspaceFileWriteAck("7", "wfw-nonexistent", true, "a", "s", false, null);
    }

    @Test
    void legacyRunnerIsRejectedByVersionGate() {
        // v25 runner 不认识 workspace_file_write → 409 门控，不静默下发挂到超时
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.2.0", null, 25, null, null),
                List.of());
        var e = assertThrows(DevMindException.class,
                () -> registry.workspaceFileWrite("7", "term-abc12345", "p1", "u1", "a", "aGk=",
                        null));
        assertTrue(e.getMessage().contains("v26"), e.getMessage());
    }

    @Test
    void disconnectFailsPendingWrites() throws Exception {
        Call call = startWrite("a.txt", null);
        registry.onDisconnect(node, ws);
        call.thread().join(10_000);
        WorkspaceFileWriteResult r = call.result().get();
        assertTrue(r != null && !r.ok(), String.valueOf(call.error().get()));
        assertTrue(r.error() != null && !r.error().isBlank());
    }
}
