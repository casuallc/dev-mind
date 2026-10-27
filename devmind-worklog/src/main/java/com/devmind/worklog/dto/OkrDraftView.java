package com.devmind.worklog.dto;

import java.time.Instant;
import java.util.List;

/**
 * CAP-93 FR-04 OKR 草稿视图。exists=false 时其余字段为 null（无草稿 200 不报 404，
 * 同报告口径——前端轮询生成结果按 exists/updatedAt 变化判定）。
 */
public record OkrDraftView(boolean exists, Long id, String scope, String period,
                           String contentMd, List<SuggestedObjective> objectives,
                           String sessionId, String status, Instant updatedAt) {

    public static OkrDraftView empty(String period) {
        return new OkrDraftView(false, null, null, period, null, null, null, null, null);
    }
}
