package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.WorkspaceReleaseResult;
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
 * CAP-42 workspace_release 帧链路（删除会话释放固定工作区）：组帧形状（repos 数组含 token）
 * → ack 完成等待 → 断连批量失败 → 协议 v9 门控（老 runner 409，绝不静默跳过——否则目录成孤儿）。
 */
class AgentConnectionRegistryReleaseTest {

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
        // v9 runner（hello 上报协议版本）
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.2.0", null, 9, null, null),
                List.of());
    }

    private static List<AgentLaunchCommand.RepoSpec> specs() {
        return List.of(
                new AgentLaunchCommand.RepoSpec("https://git/a.git", "main", "feature/s1", "tok-a", "backend"),
                new AgentLaunchCommand.RepoSpec("https://git/b.git", "main", "feature/s1", "tok-b", "web"));
    }

    private record Call(AtomicReference<WorkspaceReleaseResult> result, AtomicReference<Throwable> error,
                        Thread thread, String payload) {
    }

    private Call startRelease() throws Exception {
        return startRelease(null);
    }

    private Call startRelease(String workspaceKey) throws Exception {
        AtomicReference<WorkspaceReleaseResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                result.set(registry.releaseWorkspace("7", "s1", "proj1", "alice", specs(),
                        workspaceKey));
            } catch (Throwable e) {
                error.set(e);
            }
        });
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
    void serializesWorkspaceKeyOnlyWhenPresentAndGatesV10() throws Exception {
        // CAP-51：v9 runner 收到带 key 的释放 → 409（老 runner 会去释放 work/ 旧布局）
        var e = assertThrows(DevMindException.class, () -> registry.releaseWorkspace(
                "7", "s1", "proj1", "alice", specs(), "req-ab12cd34"));
        assertTrue(e.getMessage().contains("v10"), e.getMessage());

        // v10 runner：帧带 workspaceKey（需求粒度目录）；不带 key 的存量会话帧不带该字段
        registry.onHello(node, new AgentHelloMeta("windows", "", "0.2.0", null, 10, null, null),
                List.of());
        Call withKey = startRelease("req-ab12cd34");
        assertTrue(withKey.payload().contains("\"workspaceKey\":\"req-ab12cd34\""), withKey.payload());
        registry.onWorkspaceReleaseAck("7", requestIdOf(withKey.payload()), true, "ok", null);
        withKey.thread().join(10_000);
        assertTrue(withKey.result().get() != null && withKey.result().get().ok(),
                String.valueOf(withKey.error().get()));

        Call legacy = startRelease(null);
        assertFalse(legacy.payload().contains("workspaceKey"), legacy.payload());
        registry.onWorkspaceReleaseAck("7", requestIdOf(legacy.payload()), true, "ok", null);
        legacy.thread().join(10_000);
        assertTrue(legacy.result().get() != null && legacy.result().get().ok(),
                String.valueOf(legacy.error().get()));
    }

    @Test
    void serializesReleaseFrameAndCompletesOnAck() throws Exception {
        Call call = startRelease();
        String payload = call.payload();
        assertTrue(payload.contains("\"type\":\"workspace_release\""), payload);
        assertTrue(payload.contains("\"sessionId\":\"s1\""), payload);
        assertTrue(payload.contains("\"projectId\":\"proj1\""), payload);
        assertTrue(payload.contains("\"workspaceOwner\":\"alice\""), payload);
        // 释放无所谓 discardChanges（丢弃是既定语义）：帧里不带该字段
        assertFalse(payload.contains("discardChanges"), payload);
        assertTrue(payload.contains("\"name\":\"backend\""), payload);
        assertTrue(payload.contains("\"token\":\"tok-b\""), payload);
        assertTrue(payload.contains("\"branch\":\"feature/s1\""), payload);

        registry.onWorkspaceReleaseAck("7", requestIdOf(payload), true, "已释放固定 worktree", null);
        call.thread().join(10_000);
        assertTrue(call.result().get() != null && call.result().get().ok(),
                String.valueOf(call.error().get()));
        assertEquals("已释放固定 worktree", call.result().get().detail());
    }

    @Test
    void failedAckSurfacesError() throws Exception {
        Call call = startRelease();
        registry.onWorkspaceReleaseAck("7", requestIdOf(call.payload()), false, null, "目录被占用");
        call.thread().join(10_000);
        WorkspaceReleaseResult r = call.result().get();
        assertFalse(r.ok());
        assertEquals("目录被占用", r.error());
    }

    @Test
    void disconnectFailsPendingRelease() throws Exception {
        Call call = startRelease();
        registry.onDisconnect(node, ws);
        call.thread().join(10_000);
        WorkspaceReleaseResult r = call.result().get();
        assertTrue(r != null && !r.ok(), String.valueOf(call.error().get()));
        assertTrue(r.error().contains("断连"), r.error());
    }

    @Test
    void offlineNodeReportsOfflineNotVersionGate() {
        // 断连会清 protocolVersions（supports 按 v1 兜底）：若先判版本，离线节点会报
        // 「协议版本过低，请升级 runner」引导去升级——报错指向错误对象，正是本事故的翻版
        registry.onDisconnect(node, ws);
        var e = assertThrows(DevMindException.class,
                () -> registry.releaseWorkspace("7", "s1", "proj1", "alice", specs()));
        assertTrue(e.getMessage().contains("不在线"), e.getMessage());
        assertFalse(e.getMessage().contains("版本过低"), e.getMessage());
    }

    @Test
    void v8RunnerIsRejectedByVersionGate() {
        // v8（支持收口但不认识释放帧）→ 释放必须 409 门控：静默下发会被老 runner 忽略，
        // 目录留在磁盘成孤儿，该 (项目,用户) 之后所有会话 launch 都失败且无 UI 恢复入口
        AgentConnectionRegistry v8 = new AgentConnectionRegistry(mock(AgentNodeService.class),
                new AgentProperties(), JsonMapper.builder().build(),
                mock(ObjectProvider.class), mock(AgentConnLogService.class));
        WebSocketSession ws2 = mock(WebSocketSession.class);
        when(ws2.isOpen()).thenReturn(true);
        AgentNodeEntity n2 = new AgentNodeEntity();
        n2.setId(8L);
        n2.setName("n8");
        v8.onConnect(n2, ws2);
        v8.onHello(n2, new AgentHelloMeta("windows", "", "0.2.0", null, 8, null, null), List.of());
        // v8 能收口（v7+）
        assertTrue(v8.supports("8", com.devmind.common.agent.AgentProtocol.PER_USER_WORKSPACE));
        var e = assertThrows(DevMindException.class,
                () -> v8.releaseWorkspace("8", "s1", "proj1", "alice", specs()));
        assertTrue(e.getMessage().contains("v9"), e.getMessage());
    }
}
