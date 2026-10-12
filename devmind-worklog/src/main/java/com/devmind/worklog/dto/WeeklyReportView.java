package com.devmind.worklog.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * CAP-74：周报视图改服务端仓口径——无 DB id/status；summaryMd/nextPlanMd 由仓文件全文
 * 按分节标题拆分而来（{@code ReportService.splitWeekly}）。
 */
public record WeeklyReportView(boolean exists, LocalDate weekStart, String summaryMd, String nextPlanMd,
                               String sessionId, Instant createdAt, Instant updatedAt) {
}
