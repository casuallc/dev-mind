package com.devmind.worklog.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
