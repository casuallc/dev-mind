package com.devmind.release.dto;

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
        Long serverId,
        Long rollbackOf,
        String errorSummary,
        String createdBy,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt) {
}
