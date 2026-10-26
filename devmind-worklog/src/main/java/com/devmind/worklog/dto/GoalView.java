package com.devmind.worklog.dto;

import com.devmind.worklog.model.WorklogGoalEntity;

import java.time.Instant;

/**
 * 目标视图（CAP-91 FR-02/07）。派生字段仅 KEY_RESULT 有意义（tree 视图计算，只展示不回写）：
 * linkedPlanTotal/Done = 关联周计划项 总数（非 DROPPED/CARRIED）/完成数；
 * linkedHours = 关联 DONE 条目工时合计（小时）；suggestedProgress = done/total×100（无关联项为 null）。
 */
public record GoalView(Long id, String scope, String period, String kind, Long parentId,
                       String parentTitle, String title, String content, Integer weight,
                       Integer progress, String status, Long carriedFromId, Integer sortOrder,
                       Instant createdAt, Instant updatedAt,
                       Integer linkedPlanTotal, Integer linkedPlanDone, Double linkedHours,
                       Integer suggestedProgress) {

    /** 无派生数据的基础视图（CRUD 响应用）。 */
    public static GoalView of(WorklogGoalEntity e, String parentTitle) {
        return new GoalView(e.getId(), e.getScope(), e.getPeriod(), e.getKind(), e.getParentId(),
                parentTitle, e.getTitle(), e.getContent(), e.getWeight(), e.getProgress(),
                e.getStatus(), e.getCarriedFromId(), e.getSortOrder(), e.getCreatedAt(), e.getUpdatedAt(),
                null, null, null, null);
    }

    /** 带派生数据的 KR 视图（tree 视图用）。 */
    public GoalView withDerivation(int planTotal, int planDone, double hours, Integer suggested) {
        return new GoalView(id, scope, period, kind, parentId, parentTitle, title, content, weight,
                progress, status, carriedFromId, sortOrder, createdAt, updatedAt,
                planTotal, planDone, hours, suggested);
    }
}
