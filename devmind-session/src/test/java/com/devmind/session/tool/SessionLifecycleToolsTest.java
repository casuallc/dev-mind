package com.devmind.session.tool;

import com.devmind.common.agent.FinalizeResult;
import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.RequirementService;
import com.devmind.project.model.RequirementEntity;
import com.devmind.session.dto.SessionView;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.service.SessionManagerService;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-78 FR-04/05 会话生命周期与收口工具单测：REQ 编号→最近会话解析（活跃优先/终态兜底/
 * 解析不到报错）、预览点名 sid 与状态、收口参数默认值透传、风险登记（需求级 DANGEROUS）。
 * 依赖全部子类 fake / 接口代理（无 Spring 上下文）。
 */
class SessionLifecycleToolsTest {

    private Map<String, SessionEntity> store;
    private FakeSessionManager sessions;
    private FakeRequirementService requirements;
    private SessionLifecycleTools tools;

    private static ToolContext ctx(String projectId) {
        return new ToolContext("t1", null, "alice", "DEVELOPER", "feishu",
                "chat-1", "sender-1", "alice", projectId, null, Instant.now());
    }

    @BeforeEach
    void setUp() {
        store = new LinkedHashMap<>();
        sessions = new FakeSessionManager();
        requirements = new FakeRequirementService();
        requirements.add("r1", "p1", 3L, "接入飞书机器人", RequirementEntity.STATUS_IN_PROGRESS);
        tools = new SessionLifecycleTools(sessions, repoProxy(store), requirements);
    }

    private SessionEntity addSession(String id, String status, String requirementId, long createdEpochSec) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setProjectId("p1");
        e.setRequirementId(requirementId);
        e.setStatus(status);
        e.setTaskSpec("任务 of " + id);
        e.setAgentNodeId("node-1");
        e.setCreatedAt(Instant.ofEpochSecond(createdEpochSec));
        store.put(id, e);
        return e;
    }

    // ---------------- REQ 编号解析（FR-04） ----------------

    @Test
    void finishBySessionId() {
        addSession("s1234567890abcde", "RUNNING", "r1", 100);
        ToolResult r = tools.finishSessionTool().execute(new ToolCall("c1", "finish_session",
                Map.of("sessionId", "s1234567890abcde")), ctx("p1"));
        assertTrue(r.success(), r.errorMessage());
        assertEquals(List.of("s1234567890abcde"), sessions.finished);
        assertTrue(r.summary().contains("s1234567"));
    }

    @Test
    void finishByReqNumberResolvesActiveSession() {
        addSession("s-done-newer1111", "DONE", "r1", 200);
        addSession("s-run-older11111", "RUNNING", "r1", 100);
        PlatformTool tool = tools.finishSessionTool();
        ToolResult r = tool.execute(new ToolCall("c1", "finish_session",
                Map.of("sessionId", "REQ-3")), ctx("p1"));
        assertTrue(r.success(), r.errorMessage());
        // 活跃优先：即使 RUNNING 会话创建更早也选中它（FR-04/R2）
        assertEquals(List.of("s-run-older11111"), sessions.finished);
        String preview = tool.preview(new ToolCall("c1", "finish_session",
                Map.of("sessionId", "REQ-3")), ctx("p1"));
        assertTrue(preview.contains("s-run-ol"), preview);
        assertTrue(preview.contains("RUNNING"), preview);
    }

    @Test
    void resumeByReqNumberFallsBackToLatestTerminal() {
        addSession("s-done-older1111", "DONE", "r1", 100);
        addSession("s-done-newer1111", "DONE", "r1", 200);
        ToolResult r = tools.resumeSessionTool().execute(new ToolCall("c1", "resume_session",
                Map.of("sessionId", "REQ-3")), ctx("p1"));
        assertTrue(r.success(), r.errorMessage());
        // 无活跃/非终态会话时取最近一条（resume 的目标恒为终态会话）
        assertEquals(List.of("s-done-newer1111"), sessions.resumed);
    }

    @Test
    void unknownSessionRefNotFound() {
        DevMindException e = assertThrows(DevMindException.class, () ->
                tools.finishSessionTool().execute(new ToolCall("c1", "finish_session",
                        Map.of("sessionId", "no-such")), ctx("p1")));
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
    }

    @Test
    void requirementWithoutSessionsNotFound() {
        DevMindException e = assertThrows(DevMindException.class, () ->
                tools.finishSessionTool().execute(new ToolCall("c1", "finish_session",
                        Map.of("sessionId", "REQ-3")), ctx("p1")));
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
        assertTrue(e.getMessage().contains("没有会话"));
    }

    @Test
    void suspendHappyPath() {
        addSession("s1234567890abcde", "RUNNING", "r1", 100);
        ToolResult r = tools.suspendSessionTool().execute(new ToolCall("c1", "suspend_session",
                Map.of("sessionId", "s1234567890abcde")), ctx("p1"));
        assertTrue(r.success(), r.errorMessage());
        assertEquals(List.of("s1234567890abcde"), sessions.suspended);
        assertTrue(r.summary().contains("SUSPENDED"));
    }

    // ---------------- 收口（FR-05） ----------------

    @Test
    void finalizeSessionDefaultsPassThrough() {
        addSession("s1234567890abcde", "DONE", "r1", 100);
        ToolResult r = tools.finalizeSessionWorkspaceTool().execute(new ToolCall("c1",
                "finalize_session_workspace", Map.of("sessionId", "s1234567890abcde")), ctx("p1"));
        assertTrue(r.success(), r.errorMessage());
        assertEquals("s1234567890abcde", sessions.finalizeSessionId);
        assertFalse(sessions.finalizeDiscard);   // discardChanges 默认 false
        assertTrue(sessions.finalizeDelRemote);  // deleteRemoteBranch 会话级默认 true
    }

    @Test
    void finalizeRequirementHappyPathAndDefaults() {
        addSession("s1234567890abcde", "DONE", "r1", 100);
        ToolResult r = tools.finalizeRequirementWorkspaceTool().execute(new ToolCall("c1",
                "finalize_requirement_workspace", Map.of("requirementId", "REQ-3")), ctx("p1"));
        assertTrue(r.success(), r.errorMessage());
        assertEquals("p1", sessions.finalizeReqProjectId);
        assertEquals("r1", sessions.finalizeReqRequirementId);
        assertFalse(sessions.finalizeDiscard);
        assertFalse(sessions.finalizeDelRemote); // 需求级默认保留远端分支（CAP-51 保留工作树与分支）
        assertTrue(r.summary().contains("REQ-3"));
    }

    @Test
    void finalizeRequirementMissingProject() {
        ToolResult r = tools.finalizeRequirementWorkspaceTool().execute(new ToolCall("c1",
                "finalize_requirement_workspace", Map.of("requirementId", "REQ-3")), ctx(null));
        assertFalse(r.success());
        assertEquals(ToolResult.PROJECT_REQUIRED, r.errorCode());
    }

    @Test
    void finalizeRequirementPreviewShowsDangerElements() {
        SessionEntity s = addSession("s1234567890abcde", "DONE", "r1", 100);
        s.setWorkspaceState(SessionEntity.WORKSPACE_OPEN);
        s.setBaseBranch("master");
        PlatformTool tool = tools.finalizeRequirementWorkspaceTool();

        String preview = tool.preview(new ToolCall("c1", "finalize_requirement_workspace",
                Map.of("requirementId", "REQ-3")), ctx("p1"));
        assertTrue(preview.contains("危险操作"), preview);
        assertTrue(preview.contains("REQ-3"), preview);
        assertTrue(preview.contains("接入飞书机器人"), preview);
        assertTrue(preview.contains("master"), preview);
        assertTrue(preview.contains("demo-repo"), preview);
        assertFalse(preview.contains("丢弃"), preview);

        Map<String, Object> discardArgs = new LinkedHashMap<>();
        discardArgs.put("requirementId", "REQ-3");
        discardArgs.put("discardChanges", true);
        String discardPreview = tool.preview(new ToolCall("c1",
                "finalize_requirement_workspace", discardArgs), ctx("p1"));
        assertTrue(discardPreview.contains("丢弃"), discardPreview);
    }

    @Test
    void riskRegistration() {
        assertEquals(ToolRisk.WRITE, tools.finishSessionTool().spec().risk());
        assertEquals(ToolRisk.WRITE, tools.suspendSessionTool().spec().risk());
        assertEquals(ToolRisk.WRITE, tools.resumeSessionTool().spec().risk());
        assertEquals(ToolRisk.WRITE, tools.finalizeSessionWorkspaceTool().spec().risk());
        assertEquals(ToolRisk.DANGEROUS, tools.finalizeRequirementWorkspaceTool().spec().risk());
        assertEquals(90, tools.finalizeSessionWorkspaceTool().spec().timeoutSeconds());
        assertEquals(90, tools.finalizeRequirementWorkspaceTool().spec().timeoutSeconds());
        for (PlatformTool t : List.of(tools.finishSessionTool(), tools.suspendSessionTool(),
                tools.resumeSessionTool(), tools.finalizeSessionWorkspaceTool(),
                tools.finalizeRequirementWorkspaceTool())) {
            assertEquals("DEVELOPER", t.spec().minRole(), t.spec().name());
            assertEquals("devmind-session", t.spec().ownerModule(), t.spec().name());
        }
    }

    // ---------------- fakes ----------------

    @SuppressWarnings("unchecked")
    private static SessionRepository repoProxy(Map<String, SessionEntity> store) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "findById" -> Optional.ofNullable(store.get((String) args[0]));
            case "findByRequirementIdOrderByCreatedAtDesc" -> store.values().stream()
                    .filter(e -> args[0].equals(e.getRequirementId()))
                    .sorted(Comparator.comparing(SessionEntity::getCreatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (SessionRepository) Proxy.newProxyInstance(
                SessionRepository.class.getClassLoader(), new Class<?>[]{SessionRepository.class},
                handler);
    }

    static class FakeRequirementService extends RequirementService {
        final Map<String, RequirementEntity> store = new LinkedHashMap<>();

        FakeRequirementService() {
            super(null, null, null, null, null, null, null, null, null, null);
        }

        RequirementEntity add(String id, String projectId, long seq, String title, String status) {
            RequirementEntity e = new RequirementEntity();
            e.setId(id);
            e.setProjectId(projectId);
            e.setSeq(seq);
            e.setTitle(title);
            e.setStatus(status);
            store.put(id, e);
            return e;
        }

        @Override
        public RequirementEntity resolveRef(String projectId, String requirementId, Long seq) {
            RequirementEntity byId = store.get(requirementId);
            if (byId != null) {
                return byId;
            }
            String digits = requirementId == null ? ""
                    : requirementId.trim().replaceFirst("(?i)^REQ-?", "");
            if (digits.matches("\\d+")) {
                long n = Long.parseLong(digits);
                return store.values().stream()
                        .filter(e -> projectId != null && projectId.equals(e.getProjectId())
                                && e.getSeq() == n)
                        .findFirst()
                        .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                                "项目内不存在需求 REQ-" + n));
            }
            throw new DevMindException(ErrorCode.NOT_FOUND, "需求不存在: " + requirementId);
        }
    }

    static class FakeSessionManager extends SessionManagerService {
        final List<String> finished = new ArrayList<>();
        final List<String> suspended = new ArrayList<>();
        final List<String> resumed = new ArrayList<>();
        String finalizeSessionId;
        boolean finalizeDiscard;
        boolean finalizeDelRemote;
        String finalizeReqProjectId;
        String finalizeReqRequirementId;

        FakeSessionManager() {
            super(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null);
        }

        @Override
        public void finish(String id) {
            finished.add(id);
        }

        @Override
        public SessionView suspend(String id) {
            suspended.add(id);
            return null;
        }

        @Override
        public SessionView resume(String id) {
            resumed.add(id);
            return null;
        }

        @Override
        public FinalizeResult finalizeWorkspace(String id, boolean discardChanges,
                                                boolean deleteRemoteBranch) {
            finalizeSessionId = id;
            finalizeDiscard = discardChanges;
            finalizeDelRemote = deleteRemoteBranch;
            return FinalizeResult.ok("merged");
        }

        @Override
        public FinalizeResult finalizeRequirementWorkspace(String projectId, String requirementId,
                                                           boolean discardChanges,
                                                           boolean deleteRemoteBranch) {
            finalizeReqProjectId = projectId;
            finalizeReqRequirementId = requirementId;
            finalizeDiscard = discardChanges;
            finalizeDelRemote = deleteRemoteBranch;
            return FinalizeResult.ok("merged");
        }

        @Override
        public SessionView get(String id) {
            return new SessionView(id, "p1", null, "r1", "任务", "DONE", null, null, null, null,
                    null, "node-1", List.of("demo-repo"), "alice", "OPEN",
                    Instant.now(), Instant.now(), null, null, null, null, null, null, null);
        }
    }
}
