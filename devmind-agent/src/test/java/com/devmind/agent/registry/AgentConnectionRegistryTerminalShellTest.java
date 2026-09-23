package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.TerminalCompleteResult;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-59 terminal_complete / terminal_cancel 帧链路：组帧形状（红线：字段一个不能少）→
 * ack 完成等待 → 协议 v17 门控（老 runner 静默忽略未知帧，必须 409 而不是挂到超时/无效）→
 * 断连批量失败。
 */
class AgentConnectionRegistryTerminalShellTest {

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
        // v17 runner（hello 上报协议版本）
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.2.0", null, 17, null, null),
                List.of());
    }

    private record Call(AtomicReference<TerminalCompleteResult> result, AtomicReference<Throwable> error,
                        Thread thread, String payload) {
    }

    private Call startComplete(String input, String cwd) throws Exception {
        AtomicReference<TerminalCompleteResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                result.set(registry.terminalComplete("7", "s1", input, cwd));
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
    void completeFrameShapeAndAckRouting() throws Exception {
        Call call = startComplete("git sta", "frontend");
        // 红线：组帧五个字段一个不能少（同类静默丢失已三次事故）
        assertTrue(call.payload().contains("\"type\":\"terminal_complete\""), call.payload());
        assertTrue(call.payload().contains("\"sessionId\":\"s1\""), call.payload());
        assertTrue(call.payload().contains("\"input\":\"git sta\""), call.payload());
        assertTrue(call.payload().contains("\"cwd\":\"frontend\""), call.payload());
        assertTrue(requestIdOf(call.payload()).startsWith("tc-"), call.payload());

        registry.onTerminalCompleteAck("7", requestIdOf(call.payload()), true, "sta",
                List.of("stash", "status"), null);
        call.thread().join(10_000);
        TerminalCompleteResult r = call.result().get();
        assertTrue(r != null && r.ok(), String.valueOf(call.error().get()));
        assertEquals("sta", r.word());
        assertEquals(List.of("stash", "status"), r.candidates());
    }

    @Test
    void completeErrorAckCompletesWithFailure() throws Exception {
        Call call = startComplete("ls /", "");
        registry.onTerminalCompleteAck("7", requestIdOf(call.payload()), false, "",
                List.of(), "会话不在本节点运行或工作区已释放");
        call.thread().join(10_000);
        TerminalCompleteResult r = call.result().get();
        assertTrue(r != null && !r.ok());
        assertEquals("会话不在本节点运行或工作区已释放", r.error());
    }

    @Test
    void cancelFrameShape() throws Exception {
        org.mockito.Mockito.clearInvocations(ws);
        registry.terminalCancel("7", "s1");
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, timeout(5000).atLeastOnce()).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        // 红线：type + sessionId 一个不能少（fire-and-forget 无 requestId）
        assertTrue(payload.contains("\"type\":\"terminal_cancel\""), payload);
        assertTrue(payload.contains("\"sessionId\":\"s1\""), payload);
    }

    @Test
    void legacyRunnerRejectedByVersionGate() {
        // v16 runner 不认识 complete/cancel 帧 → 409 门控
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.2.0", null, 16, null, null),
                List.of());
        var e1 = assertThrows(DevMindException.class,
                () -> registry.terminalComplete("7", "s1", "ls", ""));
        assertTrue(e1.getMessage().contains("v17"), e1.getMessage());
        var e2 = assertThrows(DevMindException.class,
                () -> registry.terminalCancel("7", "s1"));
        assertTrue(e2.getMessage().contains("v17"), e2.getMessage());
    }

    @Test
    void disconnectFailsPendingCompletes() throws Exception {
        Call call = startComplete("git", "");
        registry.onDisconnect(node, ws);
        call.thread().join(10_000);
        TerminalCompleteResult r = call.result().get();
        assertTrue(r != null && !r.ok(), String.valueOf(call.error().get()));
        assertTrue(r.error() != null && !r.error().isBlank());
    }
}
