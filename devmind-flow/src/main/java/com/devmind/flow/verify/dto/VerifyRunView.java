package com.devmind.flow.verify.dto;

import java.time.Instant;

/**
 * CAP-73 verify run 视图（验证 Tab 列表 + run 详情）。
 */
public record VerifyRunView(
        Long id,
        String requirementId,
        String projectId,
        String triggerType,
        String triggeredBy,
        Long environmentId,
        String commitSha,
        String pushStatus,
        String stage,
        String status,
        String modulesHint,
        Integer filesChanged,
        Long durationMs,
        String stageTimingsJson,
        String failureSummary,
        String note,
        String buildNodeId,
        String envNodeId,
        Long testRunId,
        /** v1 全链日志快照（列表接口可为 null 减负，详情接口带全文） */
        String logsText,
        Instant createdAt,
        Instant finishedAt) {

    /** 列表用：剥掉大字段 */
    public VerifyRunView withoutLogs() {
        return new VerifyRunView(id, requirementId, projectId, triggerType, triggeredBy, environmentId,
                commitSha, pushStatus, stage, status, modulesHint, filesChanged, durationMs,
                stageTimingsJson, failureSummary, note, buildNodeId, envNodeId, testRunId,
                null, createdAt, finishedAt);
    }
}
