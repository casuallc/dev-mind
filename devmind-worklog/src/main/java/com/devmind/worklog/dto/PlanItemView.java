package com.devmind.worklog.dto;

import com.devmind.worklog.model.WorklogPlanItemEntity;

import java.time.Instant;
import java.time.LocalDate;

/** 计划项视图：内部分钟 → 对外 estimateHours（null=未预估）。 */
public record PlanItemView(Long id, String scope, LocalDate planDate, String title, String content,
                           String planType, Double estimateHours, String status, String source,
                           String requirementId, String jiraIssueKey, Long carriedFromId,
                           Integer sortOrder, Instant createdAt, Instant updatedAt) {

    public static PlanItemView of(WorklogPlanItemEntity e) {
        return new PlanItemView(e.getId(), e.getScope(), e.getPlanDate(), e.getTitle(), e.getContent(),
                e.getPlanType(), e.getEstimateMinutes() == null ? null : e.getEstimateMinutes() / 60.0,
                e.getStatus(), e.getSource(), e.getRequirementId(), e.getJiraIssueKey(),
                e.getCarriedFromId(), e.getSortOrder(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
