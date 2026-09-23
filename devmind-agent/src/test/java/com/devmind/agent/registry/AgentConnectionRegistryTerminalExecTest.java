package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.TerminalExecResult;
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
 * CAP-58 terminal_exec 帧链路：组帧形状（command/cwd 全字段钉死，防静默丢失事故）→
 * ack 完成等待 → 协议 v16 门控（老 runner 静默忽略未知帧，必须 409 而不是挂到超时）→
 * 断连批量失败。
 */
class AgentConnectionRegistryTerminalExecTest {

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
        // v16 runner（hello 上报协议版本）
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.2.0", null, 16, null, null),
                List.of());
    }

    private record Call(AtomicReference<TerminalExecResult> result, AtomicReference<Throwable> error,
                        Thread thread, String payload) {
    }

    private Call startExec(String command, String cwd) throws Exception {
        AtomicReference<TerminalExecResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                result.set(registry.terminalExec("7", "s1", command, cwd));
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
        Call call = startExec("git status", "frontend");
        // 红线：组帧五个字段一个不能少（静默丢失已三次事故）
        assertTrue(call.payload().contains("\"type\":\"terminal_exec\""), call.payload());
        assertTrue(call.payload().contains("\"sessionId\":\"s1\""), call.payload());
        assertTrue(call.payload().contains("\"command\":\"git status\""), call.payload());
        assertTrue(call.payload().contains("\"cwd\":\"frontend\""), call.payload());
        assertTrue(requestIdOf(call.payload()).startsWith("tx-"), call.payload());

        registry.onTerminalExecAck("7", requestIdOf(call.payload()), true, 0,
                "On branch main", "", "frontend", false, null);
        call.thread().join(10_000);
        TerminalExecResult r = call.result().get();
        assertTrue(r != null && r.ok(), String.valueOf(call.error().get()));
        assertEquals(0, r.exitCode());
        assertEquals("On branch main", r.stdout());
        assertEquals("frontend", r.cwd());
    }

    @Test
    void blankCwdSerializedAsEmpty() throws Exception {
        Call call = startExec("ls", null);
        assertTrue(call.payload().contains("\"cwd\":\"\""), call.payload());
        registry.onTerminalExecAck("7", requestIdOf(call.payload()), true, 0, "a", "", "", false, null);
        call.thread().join(10_000);
        assertTrue(call.result().get() != null && call.result().get().ok());
    }

    @Test
    void errorAckCompletesWithFailure() throws Exception {
        Call call = startExec("rm -rf .", "");
        registry.onTerminalExecAck("7", requestIdOf(call.payload()), false, -1, "", "", null,
                false, "命令不在 terminalAllowlist 白名单: rm");
        call.thread().join(10_000);
        TerminalExecResult r = call.result().get();
        assertTrue(r != null && !r.ok());
        assertEquals("命令不在 terminalAllowlist 白名单: rm", r.error());
    }

    @Test
    void unknownRequestIdIgnored() {
        // 迟到的 ack（等待已超时/断连清理后）不炸不攒
        registry.onTerminalExecAck("7", "tx-nonexistent", true, 0, "", "", "", false, null);
    }

    @Test
    void legacyRunnerIsRejectedByVersionGate() {
        // v15 runner 不认识 terminal_exec → 409 门控，不静默下发挂到超时
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.2.0", null, 15, null, null),
                List.of());
        var e = assertThrows(DevMindException.class,
                () -> registry.terminalExec("7", "s1", "ls", ""));
        assertTrue(e.getMessage().contains("v16"), e.getMessage());
    }

    @Test
    void disconnectFailsPendingExecs() throws Exception {
        Call call = startExec("ls", "");
        registry.onDisconnect(node, ws);
        call.thread().join(10_000);
        // 断连清理完成一个失败结果（不抛异常）：REST 层把 error 透传为 409 文案
        TerminalExecResult r = call.result().get();
        assertTrue(r != null && !r.ok(), String.valueOf(call.error().get()));
        assertTrue(r.error() != null && !r.error().isBlank());
    }
}
