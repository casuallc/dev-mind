package com.devmind.worklog.dto;

import com.devmind.worklog.model.DailyReportEntity;

import java.time.Instant;
import java.time.LocalDate;

public record DailyReportView(Long id, LocalDate workDate, String contentMd, String status,
                              String sessionId, Instant createdAt, Instant updatedAt) {

    public static DailyReportView of(DailyReportEntity e) {
        return new DailyReportView(e.getId(), e.getWorkDate(), e.getContentMd(), e.getStatus(),
                e.getSessionId(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
