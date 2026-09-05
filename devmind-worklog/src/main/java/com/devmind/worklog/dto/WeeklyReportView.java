package com.devmind.worklog.dto;

import com.devmind.worklog.model.WeeklyReportEntity;

import java.time.Instant;
import java.time.LocalDate;

public record WeeklyReportView(Long id, LocalDate weekStart, String summaryMd, String nextPlanMd,
                               String status, String sessionId, Instant createdAt, Instant updatedAt) {

    public static WeeklyReportView of(WeeklyReportEntity e) {
        return new WeeklyReportView(e.getId(), e.getWeekStart(), e.getSummaryMd(), e.getNextPlanMd(),
                e.getStatus(), e.getSessionId(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
