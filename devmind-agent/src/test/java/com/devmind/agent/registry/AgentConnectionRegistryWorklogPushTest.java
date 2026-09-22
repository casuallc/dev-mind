package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.WorklogPushResult;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CAP-41 M3 worklog_push 帧：下行帧形状（token 随帧）、版本门控（v6+）、ack 收口、断连失败。 */
class AgentConnectionRegistryWorklogPushTest {

    private AgentConnectionRegistry registry;
    private WebSocketSession ws;
    private AgentNodeEntity node;
    private AgentNodeService nodeService;

    @BeforeEach
    void setUp() {
        nodeService = mock(AgentNodeService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.devmind.common.agent.AgentEventListener> listenerProvider =
                mock(ObjectProvider.class);
        registry = new AgentConnectionRegistry(nodeService, new AgentProperties(),
                JsonMapper.builder().build(), listenerProvider, mock(ObjectProvider.class), mock(AgentConnLogService.class));

        ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        node = new AgentNodeEntity();
        node.setId(7L);
        node.setName("n7");
        registry.onConnect(node, ws);
    }

    private void helloWithProtocol(Integer version) {
        registry.onHello(node, new AgentHelloMeta("os", "claude", "1.0", null, version, null, null),
                java.util.List.of());
    }

    @Test
    void sendsPushFrameAndCompletesOnAck() throws Exception {
        helloWithProtocol(6);
        // 两段式：另一线程发帧并阻塞等 ack；本线程抓到下行帧里的 requestId 后回 ack
        Thread caller = new Thread(() ->
                registry.pushWorklog("7", "alice", "https://git.example.com/u/worklog.git", "main", "tok123"));
        caller.start();
        Thread.sleep(100);
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, atLeastOnce()).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertTrue(payload.contains("\"type\":\"worklog_push\""), payload);
        assertTrue(payload.contains("\"worklogOwner\":\"alice\""), payload);
        assertTrue(payload.contains("\"remoteUrl\":\"https://git.example.com/u/worklog.git\""), payload);
        assertTrue(payload.contains("\"branch\":\"main\""), payload);
        assertTrue(payload.contains("\"token\":\"tok123\""), payload);
        String requestId = payload.replaceAll(".*\"requestId\":\"([^\"]+)\".*", "$1");
        registry.onWorklogPushAck("7", requestId, true, "分支 main：Everything up-to-date", null);
        caller.join(10_000);
        assertFalse(caller.isAlive(), "ack 后 pushWorklog 应立即返回");
        // CAP-43：节点未配代理（require 未 stub → null）→ 帧不携带 proxy 字段
        assertFalse(payload.contains("\"proxy\""), payload);
    }

    /** CAP-43：配了代理的节点（协议 v8+）帧携带 proxy 块。 */
    private AgentNodeEntity proxiedNode(String scopes) {
        AgentNodeEntity e = new AgentNodeEntity();
        e.setId(7L);
        e.setProxyUrl("http://127.0.0.1:8443");
        e.setProxyScopes(scopes);
        return e;
    }

    @Test
    void serializesProxyWhenNodeConfigured() throws Exception {
        helloWithProtocol(8);
        when(nodeService.require(7L)).thenReturn(proxiedNode("git,claude"));
        Thread caller = new Thread(() ->
                registry.pushWorklog("7", "alice", "https://git.example.com/u/worklog.git", "main", null));
        caller.start();
        Thread.sleep(100);
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, atLeastOnce()).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertTrue(payload.contains("\"proxy\":{"), payload);
        assertTrue(payload.contains("\"url\":\"http://127.0.0.1:8443\""), payload);
        assertTrue(payload.contains("\"scopes\":[\"git\",\"claude\"]"), payload);
        String requestId = payload.replaceAll(".*\"requestId\":\"([^\"]+)\".*", "$1");
        registry.onWorklogPushAck("7", requestId, true, "ok", null);
        caller.join(10_000);
        assertFalse(caller.isAlive());
    }

    @Test
    void proxyGateRejectsOldRunner() {
        // 节点配了代理但 runner 协议 <8（不认识 proxy 字段会静默直连失败）→ 明示升级
        helloWithProtocol(7);
        when(nodeService.require(7L)).thenReturn(proxiedNode("git"));
        DevMindException e = assertThrows(DevMindException.class,
                () -> registry.pushWorklog("7", "alice", "https://x/y.git", "main", null));
        assertTrue(e.getMessage().contains("代理"), e.getMessage());
        assertTrue(e.getMessage().contains("升级"), e.getMessage());
    }

    @Test
    void emptyProxySentToV8RunnerWhenUnconfigured() throws Exception {
        // CAP-43 修复回归：v8+ runner 未配置代理时也必须带显式空 proxy——runner 侧
        // 「字段缺席 = 不动 holder」，缺席会让「先配后清」清不掉 runner 已生效的代理
        helloWithProtocol(8);
        Thread caller = new Thread(() ->
                registry.pushWorklog("7", "alice", "https://git.example.com/u/worklog.git", "main", null));
        caller.start();
        Thread.sleep(100);
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, atLeastOnce()).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertTrue(payload.contains("\"proxy\":{\"url\":\"\",\"scopes\":[]}"), payload);
        String requestId = payload.replaceAll(".*\"requestId\":\"([^\"]+)\".*", "$1");
        registry.onWorklogPushAck("7", requestId, true, "ok", null);
        caller.join(10_000);
        assertFalse(caller.isAlive());
    }

    @Test
    void ackErrorPropagates() {
        helloWithProtocol(6);
        Thread caller = new Thread(() ->
                registry.pushWorklog("7", "alice", "https://git.example.com/u/worklog.git", "main", null));
        caller.start();
        try {
            Thread.sleep(100);
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(ws, atLeastOnce()).sendMessage(captor.capture());
            String requestId = captor.getValue().getPayload()
                    .replaceAll(".*\"requestId\":\"([^\"]+)\".*", "$1");
            registry.onWorklogPushAck("7", requestId, false, null, "git push 失败: 鉴权失败");
            caller.join(10_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertFalse(caller.isAlive());
    }

    @Test
    void rejectsOldRunnerByProtocolGate() {
        // 无 hello 版本记录按 v1 对待
        DevMindException e = assertThrows(DevMindException.class,
                () -> registry.pushWorklog("7", "alice", "https://x/y.git", "main", null));
        assertTrue(e.getMessage().contains("升级"), e.getMessage());
        // hello 报 v5（worklog 会话时代 runner）同样被拒
        helloWithProtocol(5);
        assertThrows(DevMindException.class,
                () -> registry.pushWorklog("7", "alice", "https://x/y.git", "main", null));
    }

    @Test
    void disconnectFailsPendingPush() {
        helloWithProtocol(6);
        Thread caller = new Thread(() -> {
            try {
                registry.pushWorklog("7", "alice", "https://x/y.git", "main", null);
            } catch (DevMindException ignored) {
                // 断连 ack(ok=false) 不抛，这里只兜异常路径
            }
        });
        caller.start();
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
        registry.onDisconnect(node, ws);
        try {
            caller.join(10_000);
        } catch (InterruptedException ignored) {
        }
        assertFalse(caller.isAlive(), "断连后 pushWorklog 应立即返回，不得挂死");
    }
}
