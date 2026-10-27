package com.devmind.worklog.tool;

import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.worklog.dto.PlanItemRequest;
import com.devmind.worklog.dto.PlanItemView;
import com.devmind.worklog.dto.PlanListView;
import com.devmind.worklog.service.WorklogPlanService;
import java.time.LocalDate;
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

/**
 * CAP-76 M9 周计划工具：列表渲染、新增参数映射、更新读-合并-写与参数校验。
 * Mockito 直桩 Service（同模块既有测试风格，不起 Spring 上下文）。
 */
class WorklogPlanToolsTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 26);   // 周一

    private WorklogPlanService plans;
    private WorklogPlanTools tools;

    @BeforeEach
    void setUp() {
        plans = mock(WorklogPlanService.class);
        tools = new WorklogPlanTools(plans);
    }

    private static PlanItemView item(long id, String title, String status, Long krId) {
        return new PlanItemView(id, "WEEK", MON, title, null, "DEV", 2.0, status, "MANUAL",
                null, null, krId, null, 0, null, null);
    }

    private static ToolCall call(Map<String, Object> args) {
        return new ToolCall("c1", "x", args);
    }

    @Test
    void listRendersItemsCarryoverAndKrTitles() {
        when(plans.list("WEEK", MON)).thenReturn(new PlanListView(
                List.of(item(1L, "做 CAP-76 工具", "DOING", 9L)),
                MON.minusWeeks(1), List.of(item(2L, "上周遗留", "TODO", null)),
                Map.of(9L, "网关工具覆盖工作日志")));
        ToolResult r = tools.listWorklogPlansTool().execute(call(Map.of("weekStart", "2026-10-26")), null);
        assertTrue(r.success());
        assertTrue(r.summary().contains("#1 [DOING] 做 CAP-76 工具"));
        assertTrue(r.summary().contains("KR=网关工具覆盖工作日志"));
        assertTrue(r.summary().contains("上周（2026-10-19）未完成 1 项"));
        assertEquals(MON.toString(), r.data().get("weekStart"));
        assertEquals(1, ((List<?>) r.data().get("carryoverItems")).size());
    }

    @Test
    void listEmptyWeek() {
        when(plans.list(eq("WEEK"), any())).thenReturn(
                new PlanListView(List.of(), MON.minusWeeks(1), List.of(), Map.of()));
        ToolResult r = tools.listWorklogPlansTool().execute(call(Map.of("weekStart", "2026-10-26")), null);
        assertTrue(r.success());
        assertTrue(r.summary().contains("还没有计划项"));
    }

    @Test
    void createMapsRequestAndDefaultsToWeek() {
        when(plans.create(any())).thenReturn(item(5L, "新计划", "TODO", null));
        Map<String, Object> args = new HashMap<>();
        args.put("title", "新计划");
        args.put("weekStart", "2026-10-28");   // 周三 → Service 归一到周一
        args.put("planType", "RESEARCH");
        args.put("hours", 1.5);
        ToolResult r = tools.createWorklogPlanItemTool().execute(call(args), null);
        assertTrue(r.success());
        ArgumentCaptor<PlanItemRequest> cap = ArgumentCaptor.forClass(PlanItemRequest.class);
        verify(plans).create(cap.capture());
        assertEquals("WEEK", cap.getValue().scope());
        assertEquals(LocalDate.of(2026, 10, 28), cap.getValue().date());
        assertEquals("RESEARCH", cap.getValue().planType());
        assertEquals(1.5, cap.getValue().hours());
        assertNull(cap.getValue().status());
        assertEquals(5L, r.data().get("id"));
    }

    @Test
    void createRejectsMissingTitle() {
        ToolResult r = tools.createWorklogPlanItemTool().execute(call(Map.of()), null);
        assertFalse(r.success());
        assertEquals(ToolResult.INVALID_ARGUMENTS, r.errorCode());
    }

    @Test
    void updateMergesUnchangedFields() {
        when(plans.get(7L)).thenReturn(item(7L, "旧标题", "TODO", 3L));
        when(plans.update(eq(7L), any())).thenReturn(item(7L, "旧标题", "DONE", 3L));
        ToolResult r = tools.updateWorklogPlanItemTool().execute(
                call(Map.of("id", 7, "status", "DONE")), null);
        assertTrue(r.success());
        ArgumentCaptor<PlanItemRequest> cap = ArgumentCaptor.forClass(PlanItemRequest.class);
        verify(plans).update(eq(7L), cap.capture());
        assertEquals("旧标题", cap.getValue().title());       // 未传字段保持
        assertEquals(3L, cap.getValue().krId());
        assertEquals("DONE", cap.getValue().status());      // 传入字段生效
        assertEquals(MON, cap.getValue().date());            // 归一日期沿用原值
    }

    @Test
    void updateRejectsMissingIdOrNoFields() {
        assertFalse(tools.updateWorklogPlanItemTool().execute(call(Map.of("status", "DONE")), null).success());
        ToolResult r = tools.updateWorklogPlanItemTool().execute(call(Map.of("id", 7)), null);
        assertFalse(r.success());
        assertEquals(ToolResult.INVALID_ARGUMENTS, r.errorCode());
    }
}
