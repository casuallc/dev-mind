package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.exec.ContextManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** launch 帧形状：repos 多库数组（CAP-31 缺帧修复）与 contextManifest（CAP-34 FR-03）序列化。 */
class AgentConnectionRegistryLaunchTest {

    private AgentConnectionRegistry registry;
    private WebSocketSession ws;

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
        AgentNodeEntity node = new AgentNodeEntity();
        node.setId(7L);
        node.setName("n7");
        registry.onConnect(node, ws);
    }

    private String launchAndCapture(AgentLaunchCommand cmd) throws Exception {
        Thread acker = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            registry.onLaunchAck(cmd.sessionId(), true, null);
        });
        acker.start();
        registry.launch("7", cmd);
        acker.join(10_000);
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, atLeastOnce()).sendMessage(captor.capture());
        return captor.getValue().getPayload();
    }

    @Test
    void serializesReposArrayAndContextManifest() throws Exception {
        AgentLaunchCommand.RepoSpec main = new AgentLaunchCommand.RepoSpec(
                "https://git/a.git", "main", "feature/s1", "tok-a", "backend");
        AgentLaunchCommand.RepoSpec web = new AgentLaunchCommand.RepoSpec(
                "https://git/b.git", "main", "feature/s1", "tok-b", "web");
        ContextManifest manifest = new ContextManifest(2, 1234, "ab".repeat(32));
        String payload = launchAndCapture(new AgentLaunchCommand(
                "s1", "proj1", "task", "model-x", "acceptEdits", Map.of("K", "V"),
                main, "session", List.of(main, web), manifest));

        assertTrue(payload.contains("\"type\":\"launch\""), payload);
        // repos 多库数组（此前缺帧 bug 的回归断言）
        assertTrue(payload.contains("\"repos\":["), payload);
        assertTrue(payload.contains("\"name\":\"backend\""), payload);
        assertTrue(payload.contains("\"name\":\"web\""), payload);
        assertTrue(payload.contains("\"token\":\"tok-b\""), payload);
        // contextManifest（CAP-34 FR-03）
        assertTrue(payload.contains("\"contextManifest\":{"), payload);
        assertTrue(payload.contains("\"entries\":2"), payload);
        assertTrue(payload.contains("\"totalBytes\":1234"), payload);
        assertTrue(payload.contains("\"sha256\":\"" + "ab".repeat(32) + "\""), payload);
        // 既有字段不回归
        assertTrue(payload.contains("\"kind\":\"session\""), payload);
        assertTrue(payload.contains("\"env\":{\"K\":\"V\"}"), payload);
    }

    @Test
    void omitsReposAndManifestWhenAbsent() throws Exception {
        AgentLaunchCommand.RepoSpec main = new AgentLaunchCommand.RepoSpec(
                "https://git/a.git", "main", "feature/s1", "tok-a");
        String payload = launchAndCapture(new AgentLaunchCommand(
                "s1", "proj1", "task", null, "acceptEdits", Map.of(),
                main, "session", null, null));
        assertTrue(payload.contains("\"repo\":{"), payload);
        assertFalse(payload.contains("\"repos\""), payload);
        assertFalse(payload.contains("contextManifest"), payload);
        assertFalse(payload.contains("resumeSessionId"), payload);
    }

    @Test
    void serializesResumeSessionId() throws Exception {
        String payload = launchAndCapture(new AgentLaunchCommand(
                "s1", null, "", null, "acceptEdits", Map.of(),
                null, "chat", null, null, "cli-abc-123"));
        assertTrue(payload.contains("\"resumeSessionId\":\"cli-abc-123\""), payload);
        // CAP-41：非 worklog 会话不带 worklogOwner
        assertFalse(payload.contains("worklogOwner"), payload);
    }

    @Test
    void serializesWorklogOwnerForWorklogKind() throws Exception {
        String payload = launchAndCapture(new AgentLaunchCommand(
                "s1", "proj-wl", "写日报", null, "acceptEdits", Map.of(),
                null, "worklog", null, null, null, "zhangsan"));
        assertTrue(payload.contains("\"kind\":\"worklog\""), payload);
        assertTrue(payload.contains("\"worklogOwner\":\"zhangsan\""), payload);
        // worklog 会话无仓库块
        assertFalse(payload.contains("\"repo\""), payload);
        assertFalse(payload.contains("\"repos\""), payload);
    }

    @Test
    void serializesWorkspaceOwnerForRepoSession() throws Exception {
        // CAP-42 红线回归：repo 会话 launch 帧必须带 workspaceOwner（runner 据此落
        // <projectId>/<owner>/{main,work} 固定布局；缺字段会静默跑偏旧布局）
        AgentLaunchCommand.RepoSpec main = new AgentLaunchCommand.RepoSpec(
                "https://git/a.git", "main", "feature/s1", "tok-a");
        String payload = launchAndCapture(new AgentLaunchCommand(
                "s1", "proj1", "task", null, "acceptEdits", Map.of(),
                main, "session", null, null, null, null, "alice"));
        assertTrue(payload.contains("\"workspaceOwner\":\"alice\""), payload);
        // chat 会话（无 repo 块、workspaceOwner=null）不带该字段
        String chatPayload = launchAndCapture(new AgentLaunchCommand(
                "s2", null, "", null, "acceptEdits", Map.of(),
                null, "chat", null, null, null, null, null));
        assertFalse(chatPayload.contains("workspaceOwner"), chatPayload);
    }
}
