package com.devmind.worklog.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    @Test
    void splitWeeklySplitsByPlanHeading() {
        String body = ReportService.SUMMARY_HEADING + "\n- 完成 A\n- 推进 B\n\n"
                + ReportService.PLAN_HEADING + "\n- 继续 B\n";
        String[] parts = ReportService.splitWeekly(body);
        assertEquals("- 完成 A\n- 推进 B", parts[0]);
        assertEquals("- 继续 B", parts[1]);
    }

    @Test
    void splitWeeklyFallbackAllToSummary() {
        String[] parts = ReportService.splitWeekly("- 完成 A");
        assertEquals("- 完成 A", parts[0]);
        assertEquals("", parts[1]);
        assertEquals("", ReportService.splitWeekly(null)[0]);
    }

    @Test
    void splitWeekly去掉孤立总结标题() {
        // CAP-74 迁移往返命根子：正文只有总结节标题时也要剥掉，否则 reassemble 后标题翻倍
        String[] parts = ReportService.splitWeekly(ReportService.SUMMARY_HEADING + "\n\n- 完成 A\n");
        assertEquals("- 完成 A", parts[0]);
        assertEquals("", parts[1]);
    }

    @Test
    void assembleWeekly与splitWeekly往返一致() {
        String assembled = ReportService.assembleWeekly("- 完成 A", "- 继续 B");
        String[] parts = ReportService.splitWeekly(assembled);
        assertEquals("- 完成 A", parts[0]);
        assertEquals("- 继续 B", parts[1]);
        // plan 为空省略尾部，且再拆不丢内容
        String noPlan = ReportService.assembleWeekly("- 完成 A", "");
        String[] p2 = ReportService.splitWeekly(noPlan);
        assertEquals("- 完成 A", p2[0]);
        assertEquals("", p2[1]);
    }

    @Test
    void 上一周计划从上周周报拆出() {
        WorklogRepoService repo = mock(WorklogRepoService.class);
        when(repo.readReport("alice", WorklogRepoService.ReportKind.WEEKLY, LocalDate.of(2026, 10, 12)))
                .thenReturn(Optional.of(new WorklogRepoService.ReportContent(
                        "personal-alice", "alice/weekly/2026-W42.md",
                        ReportService.assembleWeekly("- 完成 A", "- 完成 B\n- 推进 C"),
                        null, null, "ai", null)));
        when(repo.readReport("alice", WorklogRepoService.ReportKind.WEEKLY, LocalDate.of(2026, 10, 5)))
                .thenReturn(Optional.of(new WorklogRepoService.ReportContent(
                        "personal-alice", "alice/weekly/2026-W41.md",
                        ReportService.assembleWeekly("只有总结", ""),
                        null, null, "ai", null)));

        ReportService service = new ReportService(null, null, null, null, null, repo);

        assertEquals("- 完成 B\n- 推进 C", service.lastWeekPlan("alice", LocalDate.of(2026, 10, 19)));
        // 上周周报没有计划时返回空串，模板据此省略跟进块
        assertEquals("", service.lastWeekPlan("alice", LocalDate.of(2026, 10, 12)));
    }
}
