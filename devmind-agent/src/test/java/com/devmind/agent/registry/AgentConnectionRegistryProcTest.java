package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentProcCommand;
import com.devmind.common.agent.AgentProcResult;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-57 proc 帧链路：<b>钉死完整组帧</b>（AgentProcCommand 全部字段——漏 put 一行 runner 就
 * 拿到空值，本仓库 launch 帧三次同类事故）→ proc_ack 完成等待 → 协议 v15 门控（老 runner
 * 静默忽略 proc 帧，必须 409 而不是挂到超时）→ 断连批量失败。
 */
class AgentConnectionRegistryProcTest {

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
        // v15 runner（hello 上报协议版本）
        registry.onHello(node, new AgentHelloMeta("linux", "", "0.3.0", null, 15, null, null),
                List.of());
    }

    private record Call(AtomicReference<AgentProcResult> result, AtomicReference<Throwable> error,
                        Thread thread, String payload) {
    }

    private Call startProc(AgentProcCommand cmd) throws Exception {
        AtomicReference<AgentProcResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                result.set(registry.proc("7", cmd));
            } catch (Throwable e) {
                error.set(e);
            }
        });
        org.mockito.Mockito.clearInvocations(ws);
        t.start();
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, timeout(5000).atLeastOnce()).sendMessage(captor.capture());
        return new Call(result, error, t, captor.getValue().getPayload());
    }

    private static AgentProcCommand startCmd() {
        return new AgentProcCommand("proc-req-1", AgentProcCommand.ACTION_START, "3",
                List.of("python", "sidecar.py", "--port", "8377"), "python sidecar.py --port 8377",
                Map.of("LAYA_DEVICE", "cpu"), "packages/pkg-9", "run/inst-3/proc.pid", "logs/inst-3.log");
    }

    @Test
    void frameShapePutsAllFields() throws Exception {
        Call call = startProc(startCmd());
        String p = call.payload();
        assertTrue(p.contains("\"type\":\"proc\""), p);
        assertTrue(p.contains("\"requestId\":\"proc-req-1\""), p);
        assertTrue(p.contains("\"action\":\"start\""), p);
        assertTrue(p.contains("\"instanceId\":\"3\""), p);
        assertTrue(p.contains("\"argv\":[\"python\",\"sidecar.py\",\"--port\",\"8377\"]"), p);
        assertTrue(p.contains("\"command\":\"python sidecar.py --port 8377\""), p);
        assertTrue(p.contains("\"LAYA_DEVICE\":\"cpu\""), p);
        assertTrue(p.contains("\"workdir\":\"packages/pkg-9\""), p);
        assertTrue(p.contains("\"pidFile\":\"run/inst-3/proc.pid\""), p);
        assertTrue(p.contains("\"logFile\":\"logs/inst-3.log\""), p);

        registry.onProcAck("7", "proc-req-1", true, "start", "RUNNING", 4242L, null, "已拉起");
        call.thread().join(10_000);
        AgentProcResult r = call.result().get();
        assertTrue(r != null && r.ok(), String.valueOf(call.error().get()));
        assertEquals("RUNNING", r.status());
        assertEquals(4242L, r.pid());
        assertEquals("已拉起", r.detail());
    }

    @Test
    void errorAckCompletesWithFailure() throws Exception {
        Call call = startProc(startCmd());
        registry.onProcAck("7", "proc-req-1", false, "start", "UNKNOWN", null, "工作目录不存在", null);
        call.thread().join(10_000);
        AgentProcResult r = call.result().get();
        assertTrue(r != null && !r.ok());
        assertEquals("工作目录不存在", r.error());
    }

    @Test
    void legacyRunnerIsRejectedByVersionGate() {
        // v14 runner 不认识 proc 帧 → 409 门控，不静默下发挂到超时
        registry.onHello(node, new AgentHelloMeta("linux", "", "0.3.0", null, 14, null, null),
                List.of());
        var e = assertThrows(DevMindException.class, () -> registry.proc("7", startCmd()));
        assertTrue(e.getMessage().contains("v15"), e.getMessage());
    }

    @Test
    void disconnectFailsPendingProc() throws Exception {
        Call call = startProc(startCmd());
        registry.onDisconnect(node, ws);
        call.thread().join(10_000);
        AgentProcResult r = call.result().get();
        assertTrue(r != null && !r.ok(), String.valueOf(call.error().get()));
        assertTrue(r.error() != null && r.error().contains("断连"), r.error());
    }
}
