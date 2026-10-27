package com.devmind.worklog.tool;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.worklog.dto.DailyReportView;
import com.devmind.worklog.dto.UpdateDailyRequest;
import com.devmind.worklog.dto.UpdateWeeklyRequest;
import com.devmind.worklog.dto.WeeklyReportView;
import com.devmind.worklog.service.ReportService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CAP-76 M9 报告工具：空结果口径、周报周一归一、更新自动补空白与参数校验。 */
class WorklogReportToolsTest {

    private static final String ME = "alice";

    private ReportService reports;
    private WorklogReportTools tools;

    @BeforeEach
    void setUp() {
        reports = mock(ReportService.class);
        IdentityService identity = mock(IdentityService.class);
        when(identity.currentActor()).thenReturn(ME);
        tools = new WorklogReportTools(reports, identity);
    }

    private static ToolCall call(Map<String, Object> args) {
        return new ToolCall("c1", "x", args);
    }

    @Test
    void getDailyMissingIsOkNotError() {
        when(reports.getDaily(eq(ME), any())).thenReturn(null);
        ToolResult r = tools.getDailyReportTool().execute(call(Map.of("date", "2026-10-26")), null);
        assertTrue(r.success());
        assertTrue(r.summary().contains("还没有日报"));
        assertEquals(Boolean.FALSE, r.data().get("exists"));
    }

    @Test
    void getDailyReturnsContent() {
        when(reports.getDaily(ME, LocalDate.of(2026, 10, 26))).thenReturn(new DailyReportView(
                true, LocalDate.of(2026, 10, 26), "## 今天\n做了工具", null, Instant.now(), Instant.now()));
        ToolResult r = tools.getDailyReportTool().execute(call(Map.of("date", "2026-10-26")), null);
        assertTrue(r.success());
        assertTrue(r.summary().contains("做了工具"));
        assertEquals("## 今天\n做了工具", r.data().get("contentMd"));
    }

    @Test
    void getWeeklyNormalizesToMonday() {
        when(reports.getWeekly(eq(ME), any())).thenReturn(null);
        ToolResult r = tools.getWeeklyReportTool().execute(call(Map.of("weekStart", "2026-10-28")), null); // 周三
        assertTrue(r.success());
        verify(reports).getWeekly(ME, LocalDate.of(2026, 10, 26));
        assertEquals("2026-10-26", r.data().get("weekStart"));
    }

    @Test
    void updateDailyCreatesBlankThenWrites() {
        when(reports.updateDaily(eq(ME), any(), any())).thenReturn(new DailyReportView(
                true, LocalDate.of(2026, 10, 26), "正文", null, Instant.now(), Instant.now()));
        ToolResult r = tools.updateDailyReportTool().execute(
                call(Map.of("date", "2026-10-26", "contentMd", "正文")), null);
        assertTrue(r.success());
        verify(reports).createDaily(ME, LocalDate.of(2026, 10, 26));
        ArgumentCaptor<UpdateDailyRequest> cap = ArgumentCaptor.forClass(UpdateDailyRequest.class);
        verify(reports).updateDaily(eq(ME), eq(LocalDate.of(2026, 10, 26)), cap.capture());
        assertEquals("正文", cap.getValue().contentMd());
        assertNull(cap.getValue().status());
    }

    @Test
    void updateDailyRejectsBlankContent() {
        ToolResult r = tools.updateDailyReportTool().execute(call(Map.of("contentMd", "  ")), null);
        assertFalse(r.success());
        verify(reports, never()).createDaily(any(), any());
    }

    @Test
    void updateWeeklyPartialSectionKeepsOther() {
        when(reports.updateWeekly(eq(ME), any(), any())).thenReturn(new WeeklyReportView(
                true, LocalDate.of(2026, 10, 26), null, "下周计划正文", null, Instant.now(), Instant.now()));
        Map<String, Object> args = new HashMap<>();
        args.put("weekStart", "2026-10-30");   // 周五 → 归一 2026-10-26
        args.put("nextPlanMd", "下周计划正文");
        ToolResult r = tools.updateWeeklyReportTool().execute(call(args), null);
        assertTrue(r.success());
        verify(reports).createWeekly(ME, LocalDate.of(2026, 10, 26));
        ArgumentCaptor<UpdateWeeklyRequest> cap = ArgumentCaptor.forClass(UpdateWeeklyRequest.class);
        verify(reports).updateWeekly(eq(ME), eq(LocalDate.of(2026, 10, 26)), cap.capture());
        assertNull(cap.getValue().summaryMd());          // 未传的节保持 null（Service 侧保留现状）
        assertEquals("下周计划正文", cap.getValue().nextPlanMd());
    }

    @Test
    void updateWeeklyRejectsNoSection() {
        ToolResult r = tools.updateWeeklyReportTool().execute(call(Map.of()), null);
        assertFalse(r.success());
        assertEquals(ToolResult.INVALID_ARGUMENTS, r.errorCode());
    }
}
