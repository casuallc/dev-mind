package com.devmind.worklog.tool;

import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.worklog.dto.GoalRequest;
import com.devmind.worklog.dto.GoalTreeView;
import com.devmind.worklog.dto.GoalView;
import com.devmind.worklog.service.WorklogGoalService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CAP-76 M9 目标工具：目标树渲染、创建参数映射、更新读-合并-写与参数校验。 */
class WorklogGoalToolsTest {

    private WorklogGoalService goals;
    private WorklogGoalTools tools;

    @BeforeEach
    void setUp() {
        goals = mock(WorklogGoalService.class);
        tools = new WorklogGoalTools(goals);
    }

    private static GoalView goal(long id, String scope, String period, String kind, Long parentId,
                                 String title, int progress) {
        return new GoalView(id, scope, period, kind, "OKR", parentId, null, title, null, null,
                progress, "ACTIVE", null, 0, null, null, null, null, null, null);
    }

    private static ToolCall call(Map<String, Object> args) {
        return new ToolCall("c1", "x", args);
    }

    @Test
    void treeRendersYearAndQuarterSections() {
        GoalView yearO = goal(1L, "YEAR", "2026", "OBJECTIVE", null, "平台化", 30);
        GoalView quarterO = goal(2L, "QUARTER", "2026-Q4", "OBJECTIVE", 1L, "Q4 收口", 10);
        GoalView kr = new GoalView(3L, "QUARTER", "2026-Q4", "KEY_RESULT", "OKR", 2L, null,
                "工具覆盖工作日志", null, 50, 40, "ACTIVE", null, 0, null, null,
                5, 2, 3.5, 40);
        when(goals.tree(2026)).thenReturn(new GoalTreeView(
                List.of(new GoalTreeView.GoalNode(yearO, List.of())),
                List.of(new GoalTreeView.QuarterSection("2026-Q1", List.of()),
                        new GoalTreeView.QuarterSection("2026-Q2", List.of()),
                        new GoalTreeView.QuarterSection("2026-Q3", List.of()),
                        new GoalTreeView.QuarterSection("2026-Q4",
                                List.of(new GoalTreeView.GoalNode(quarterO, List.of(kr)))))));
        ToolResult r = tools.listWorklogGoalsTool().execute(call(Map.of("year", 2026)), null);
        assertTrue(r.success());
        assertTrue(r.summary().contains("#1 [ACTIVE] 平台化 · 进度 30%"));
        assertTrue(r.summary().contains("KR #3 [ACTIVE] 工具覆盖工作日志 · 进度 40%（计划完成度 40%，已投入 3.5h）"));
        assertTrue(r.summary().contains("【2026-Q1】（无）"));
        assertEquals(3, ((List<?>) r.data().get("goals")).size());
    }

    @Test
    void createMapsRequest() {
        when(goals.create(any())).thenReturn(goal(9L, "QUARTER", "2026-Q4", "KEY_RESULT", 2L, "新 KR", 0));
        Map<String, Object> args = new HashMap<>();
        args.put("scope", "QUARTER");
        args.put("period", "2026-Q4");
        args.put("kind", "KEY_RESULT");
        args.put("parentId", 2);
        args.put("title", "新 KR");
        args.put("weight", 30);
        ToolResult r = tools.createWorklogGoalTool().execute(call(args), null);
        assertTrue(r.success());
        ArgumentCaptor<GoalRequest> cap = ArgumentCaptor.forClass(GoalRequest.class);
        verify(goals).create(cap.capture());
        assertEquals(2L, cap.getValue().parentId());
        assertEquals(30, cap.getValue().weight());
        assertNull(cap.getValue().status());       // 创建恒 ACTIVE，由 Service 钉死
        assertEquals(9L, r.data().get("id"));
    }

    @Test
    void createRejectsMissingFields() {
        ToolResult r = tools.createWorklogGoalTool().execute(call(Map.of("title", "只有标题")), null);
        assertFalse(r.success());
        assertEquals(ToolResult.INVALID_ARGUMENTS, r.errorCode());
    }

    @Test
    void updateMergesAndKeepsIdentityAttrs() {
        when(goals.get(3L)).thenReturn(goal(3L, "QUARTER", "2026-Q4", "KEY_RESULT", 2L, "旧 KR", 10));
        when(goals.update(eq(3L), any())).thenReturn(goal(3L, "QUARTER", "2026-Q4", "KEY_RESULT", 2L, "旧 KR", 60));
        ToolResult r = tools.updateWorklogGoalTool().execute(call(Map.of("id", 3, "progress", 60)), null);
        assertTrue(r.success());
        ArgumentCaptor<GoalRequest> cap = ArgumentCaptor.forClass(GoalRequest.class);
        verify(goals).update(eq(3L), cap.capture());
        assertEquals("QUARTER", cap.getValue().scope());   // 身份属性取自现状
        assertEquals("2026-Q4", cap.getValue().period());
        assertEquals("旧 KR", cap.getValue().title());     // 未传字段保持
        assertEquals(60, cap.getValue().progress());
    }

    @Test
    void updateRejectsMissingIdOrNoFields() {
        assertFalse(tools.updateWorklogGoalTool().execute(call(Map.of("progress", 60)), null).success());
        ToolResult r = tools.updateWorklogGoalTool().execute(call(Map.of("id", 3)), null);
        assertFalse(r.success());
        assertEquals(ToolResult.INVALID_ARGUMENTS, r.errorCode());
    }
}
