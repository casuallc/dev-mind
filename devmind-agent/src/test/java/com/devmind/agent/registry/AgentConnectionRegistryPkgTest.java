package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentPkgCommand;
import com.devmind.common.agent.AgentPkgResult;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-57 pkg 帧链路：<b>钉死完整组帧</b>（AgentPkgCommand 全部字段）→ pkg_ack 完成 future →
 * 协议 v15 门控 → 断连批量失败。下发是异步的（GB 下载不阻塞），测试直接拿 future 断言。
 */
class AgentConnectionRegistryPkgTest {

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
        registry.onHello(node, new AgentHelloMeta("linux", "", "0.3.0", null, 15, null, null),
                List.of());
    }

    private static AgentPkgCommand installCmd() {
        return new AgentPkgCommand("pkg-req-1", 9L, "a".repeat(64), 123_456_789L,
                "laya-sidecar-1.0.zip", "packages/pkg-9");
    }

    private String sendAndCapture(CompletableFuture<AgentPkgResult> ignored) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, timeout(5000).atLeastOnce()).sendMessage(captor.capture());
        return captor.getValue().getPayload();
    }

    @Test
    void frameShapePutsAllFields() throws Exception {
        CompletableFuture<AgentPkgResult> future = registry.pkgInstallAsync("7", installCmd());
        String p = sendAndCapture(future);
        assertTrue(p.contains("\"type\":\"pkg\""), p);
        assertTrue(p.contains("\"requestId\":\"pkg-req-1\""), p);
        assertTrue(p.contains("\"packageId\":9"), p);
        assertTrue(p.contains("\"sha256\":\"" + "a".repeat(64) + "\""), p);
        assertTrue(p.contains("\"sizeBytes\":123456789"), p);
        assertTrue(p.contains("\"fileName\":\"laya-sidecar-1.0.zip\""), p);
        assertTrue(p.contains("\"installDir\":\"packages/pkg-9\""), p);

        registry.onPkgAck("7", "pkg-req-1", true, "/opt/runner/classify/packages/pkg-9", null);
        AgentPkgResult r = future.get(10, TimeUnit.SECONDS);
        assertTrue(r.ok());
        assertEquals("/opt/runner/classify/packages/pkg-9", r.installDir());
    }

    @Test
    void errorAckCompletesWithFailure() throws Exception {
        CompletableFuture<AgentPkgResult> future = registry.pkgInstallAsync("7", installCmd());
        sendAndCapture(future);
        registry.onPkgAck("7", "pkg-req-1", false, "", "sha256 校验不符");
        AgentPkgResult r = future.get(10, TimeUnit.SECONDS);
        assertTrue(!r.ok());
        assertEquals("sha256 校验不符", r.error());
    }

    @Test
    void legacyRunnerIsRejectedByVersionGate() {
        registry.onHello(node, new AgentHelloMeta("linux", "", "0.3.0", null, 14, null, null),
                List.of());
        var e = assertThrows(DevMindException.class, () -> registry.pkgInstallAsync("7", installCmd()));
        assertTrue(e.getMessage().contains("v15"), e.getMessage());
    }

    @Test
    void disconnectFailsPendingPkg() throws Exception {
        CompletableFuture<AgentPkgResult> future = registry.pkgInstallAsync("7", installCmd());
        sendAndCapture(future);
        registry.onDisconnect(node, ws);
        AgentPkgResult r = future.get(10, TimeUnit.SECONDS);
        assertTrue(!r.ok());
        assertTrue(r.error().contains("断连"), r.error());
    }
}
