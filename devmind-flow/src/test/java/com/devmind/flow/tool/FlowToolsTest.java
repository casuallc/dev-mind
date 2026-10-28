package com.devmind.flow.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.flow.RequirementFlowService;
import com.devmind.project.DesignService;
import com.devmind.project.RequirementService;
import com.devmind.project.WorkItemService;
import com.devmind.project.dto.DesignView;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.session.dto.SessionView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-78 FR-01~03 流程工具单测：寻址解析、PROJECT_REQUIRED/INVALID_ARGUMENTS、
 * Service 异常透传（网关统一映射）、首规划/重新规划预览、风险登记。
 * 依赖全部子类 fake（与 RequirementFlowServiceTest 同姿势，无 Spring 上下文）。
 */
class FlowToolsTest {

    private FakeRequirementService requirements;
    private FakeWorkItemService workItems;
    private FakeDesignService designs;
    private FakeFlowService flow;
    private FlowTools tools;

    private static ToolContext ctx(String projectId) {
        return new ToolContext("t1", null, "alice", "DEVELOPER", "feishu",
                "chat-1", "sender-1", "alice", projectId, null, Instant.now());
    }

    @BeforeEach
    void setUp() {
        requirements = new FakeRequirementService();
        workItems = new FakeWorkItemService();
        designs = new FakeDesignService();
        flow = new FakeFlowService();
        tools = new FlowTools(flow, requirements, workItems, designs);
        requirements.add("r1", "p1", 1L, "接入飞书机器人", RequirementEntity.STATUS_DRAFT);
    }

    // ---------------- start_requirement_plan ----------------

    @Test
    void planHappyPathResolvesReqNumber() {
        PlatformTool tool = tools.startRequirementPlanTool();
        ToolResult r = tool.execute(new ToolCall("c1", "start_requirement_plan",
                Map.of("requirementId", "REQ-1")), ctx("p1"));
        assertTrue(r.success(), r.errorMessage());
        assertEquals("p1", flow.planProjectId);
        assertEquals("r1", flow.planRequirementId);
        assertTrue(r.summary().contains("REQ-1"));
        assertTrue(r.summary().contains("规划会话"));
        assertEquals("r1", r.data().get("requirementId"));
    }

    @Test
    void planMissingProjectContext() {
        PlatformTool tool = tools.startRequirementPlanTool();
        ToolResult r = tool.execute(new ToolCall("c1", "start_requirement_plan",
                Map.of("requirementId", "REQ-1")), ctx(null));
        assertFalse(r.success());
        assertEquals(ToolResult.PROJECT_REQUIRED, r.errorCode());
    }

    @Test
    void planMissingRequirementRef() {
        PlatformTool tool = tools.startRequirementPlanTool();
        ToolResult r = tool.execute(new ToolCall("c1", "start_requirement_plan",
                Map.of()), ctx("p1"));
        assertFalse(r.success());
        assertEquals(ToolResult.INVALID_ARGUMENTS, r.errorCode());
    }

    @Test
    void planTerminalRequirementConflictPropagates() {
        requirements.add("r9", "p1", 9L, "已完结", RequirementEntity.STATUS_DONE);
        flow.planError = new DevMindException(ErrorCode.CONFLICT, "需求已 DONE，不能发起规划");
        PlatformTool tool = tools.startRequirementPlanTool();
        DevMindException e = assertThrows(DevMindException.class, () -> tool.execute(
                new ToolCall("c1", "start_requirement_plan", Map.of("requirementId", "REQ-9")), ctx("p1")));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
    }

    @Test
    void planPreviewFirstPlanVsReplan() {
        PlatformTool tool = tools.startRequirementPlanTool();
        ToolCall call = new ToolCall("c1", "start_requirement_plan", Map.of("requirementId", "REQ-1"));
        String first = tool.preview(call, ctx("p1"));
        assertTrue(first.contains("开启 AI 规划"));
        assertFalse(first.contains("整体重跑"));

        workItems.add("w1", "r1", WorkItemEntity.STATUS_TODO);
        String replan = tool.preview(call, ctx("p1"));
        assertTrue(replan.contains("重新规划"));
        assertTrue(replan.contains("整体重跑"));
        assertTrue(replan.contains("保留"));
    }

    // ---------------- start_requirement_dev ----------------

    @Test
    void devHappyPath() {
        workItems.add("w1", "r1", WorkItemEntity.STATUS_TODO);
        PlatformTool tool = tools.startRequirementDevTool();
        ToolResult r = tool.execute(new ToolCall("c1", "start_requirement_dev",
                Map.of("requirementId", "1")), ctx("p1"));
        assertTrue(r.success(), r.errorMessage());
        assertEquals("r1", flow.devRequirementId);
        assertTrue(r.summary().contains("开发会话"));
    }

    @Test
    void devNoWorkItemsConflictPropagates() {
        flow.devError = new DevMindException(ErrorCode.CONFLICT,
                "该需求还没有工作单元，请先「开启 AI 规划」");
        PlatformTool tool = tools.startRequirementDevTool();
        DevMindException e = assertThrows(DevMindException.class, () -> tool.execute(
                new ToolCall("c1", "start_requirement_dev", Map.of("requirementId", "REQ-1")), ctx("p1")));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
        assertTrue(e.getMessage().contains("开启 AI 规划"));
    }

    // ---------------- start_work_item_session ----------------

    @Test
    void workItemSessionHappyPath() {
        workItems.add("w2", "r1", WorkItemEntity.STATUS_TODO);
        PlatformTool tool = tools.startWorkItemSessionTool();
        ToolResult r = tool.execute(new ToolCall("c1", "start_work_item_session",
                Map.of("workItemId", "WI-2")), ctx("p1"));
        assertTrue(r.success(), r.errorMessage());
        assertEquals("w2", flow.wiSessionWorkItemId);
        assertEquals("p1", flow.wiSessionProjectId);
        assertTrue(r.summary().contains("WI-2"));
    }

    @Test
    void workItemSessionMissingSpecPropagatesBadRequest() {
        workItems.add("w2", "r1", WorkItemEntity.STATUS_TODO);
        flow.wiSessionError = new DevMindException(ErrorCode.BAD_REQUEST,
                "工作单元缺少 spec（执行说明），请先补充再派发");
        PlatformTool tool = tools.startWorkItemSessionTool();
        DevMindException e = assertThrows(DevMindException.class, () -> tool.execute(
                new ToolCall("c1", "start_work_item_session", Map.of("workItemId", "WI-2")), ctx("p1")));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    // ---------------- 风险登记 ----------------

    @Test
    void riskRegistration() {
        for (PlatformTool t : List.of(tools.startRequirementPlanTool(),
                tools.startRequirementDevTool(), tools.startWorkItemSessionTool())) {
            assertEquals(ToolRisk.WRITE, t.spec().risk(), t.spec().name());
            assertEquals("DEVELOPER", t.spec().minRole(), t.spec().name());
            assertTrue(t.spec().requiresProject(), t.spec().name());
            assertEquals("devmind-flow", t.spec().ownerModule(), t.spec().name());
        }
    }

    // ---------------- fakes ----------------

    /** 按 id / REQ-<seq> / 纯数字 seq 寻址（对齐 RequirementService.resolveRef 三形态）。 */
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
                        .filter(e -> projectId.equals(e.getProjectId()) && e.getSeq() == n)
                        .findFirst()
                        .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                                "项目内不存在需求 REQ-" + n));
            }
            throw new DevMindException(ErrorCode.NOT_FOUND, "需求不存在: " + requirementId);
        }
    }

    static class FakeWorkItemService extends WorkItemService {
        final Map<String, WorkItemEntity> store = new LinkedHashMap<>();

        FakeWorkItemService() {
            super(null, null, null, null, null, null, null);
        }

        WorkItemEntity add(String id, String requirementId, String status) {
            WorkItemEntity e = new WorkItemEntity();
            e.setId(id);
            e.setProjectId("p1");
            e.setRequirementId(requirementId);
            e.setSeq(2L);
            e.setType(WorkItemEntity.TYPE_DEVELOPMENT);
            e.setTitle("工作单元 " + id);
            e.setSpec("spec of " + id);
            e.setStatus(status);
            store.put(id, e);
            return e;
        }

        @Override
        public List<WorkItemView> list(String projectId, String requirementId) {
            return store.values().stream()
                    .filter(e -> requirementId.equals(e.getRequirementId()))
                    .map(e -> new WorkItemView(e.getId(), e.getProjectId(), e.getRequirementId(),
                            null, e.getSeq(), "WI-" + e.getSeq(), e.getType(), e.getTitle(),
                            e.getSpec(), e.getStatus(), null, null, "test",
                            Instant.now(), Instant.now()))
                    .toList();
        }

        @Override
        public WorkItemEntity resolveRef(String projectId, String workItemId) {
            WorkItemEntity byId = store.get(workItemId);
            if (byId != null) {
                return byId;
            }
            String digits = workItemId == null ? ""
                    : workItemId.trim().replaceFirst("(?i)^WI-?", "");
            if (digits.matches("\\d+")) {
                long n = Long.parseLong(digits);
                return store.values().stream()
                        .filter(e -> projectId.equals(e.getProjectId()) && e.getSeq() == n)
                        .findFirst()
                        .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                                "项目内不存在工作单元 WI-" + n));
            }
            throw new DevMindException(ErrorCode.NOT_FOUND, "工作单元不存在: " + workItemId);
        }
    }

    static class FakeDesignService extends DesignService {
        final List<DesignView> designs = new ArrayList<>();

        FakeDesignService() {
            super(null, null, null);
        }

        @Override
        public List<DesignView> list(String projectId, String requirementId) {
            return designs;
        }
    }

    /** 只记录入参/可注入异常的流程服务（真服务的状态机与互斥由 RequirementFlowServiceTest 覆盖）。 */
    static class FakeFlowService extends RequirementFlowService {
        String planProjectId;
        String planRequirementId;
        DevMindException planError;
        String devProjectId;
        String devRequirementId;
        DevMindException devError;
        String wiSessionProjectId;
        String wiSessionWorkItemId;
        DevMindException wiSessionError;
        private int seq = 0;

        FakeFlowService() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public SessionView startPlan(String projectId, String requirementId) {
            if (planError != null) {
                throw planError;
            }
            planProjectId = projectId;
            planRequirementId = requirementId;
            return view(requirementId);
        }

        @Override
        public SessionView startDev(String projectId, String requirementId) {
            if (devError != null) {
                throw devError;
            }
            devProjectId = projectId;
            devRequirementId = requirementId;
            return view(requirementId);
        }

        @Override
        public SessionView startWorkItemSession(String projectId, String workItemId) {
            if (wiSessionError != null) {
                throw wiSessionError;
            }
            wiSessionProjectId = projectId;
            wiSessionWorkItemId = workItemId;
            return view(null);
        }

        private SessionView view(String requirementId) {
            return new SessionView("sess-fake-" + (++seq) + "abcdefgh", "p1", null, requirementId,
                    "[flow:plan] 任务", "RUNNING", null, null, null, null, null, "node-1",
                    null, "alice", "OPEN", Instant.now(), Instant.now(), null,
                    null, null, null, null, null, null);
        }
    }
}
