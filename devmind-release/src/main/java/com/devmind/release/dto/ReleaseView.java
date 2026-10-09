package com.devmind.release.dto;

import com.devmind.project.dto.WorkItemBrief;

import java.time.Instant;

/**
 * CAP-11 发版记录视图（releases 表）。
 * 注意：Jackson 3 默认 FAIL_ON_NULL_FOR_PRIMITIVES，时间/数值字段用包装类型。
 */
public record ReleaseView(
        Long id,
        String projectId,
        String workItemId,
        Long buildId,
        String version,
        String status,
        String artifactRef,
        String nexusRef,
        String tagName,
        String executor,
        String agentNodeId,
        Long rollbackOf,
        String errorSummary,
        String createdBy,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        /** 关联工作单元摘要（列表回链需求展示；未关联或已删除时为 null） */
        WorkItemBrief workItem) {
}
