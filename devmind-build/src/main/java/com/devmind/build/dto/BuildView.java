package com.devmind.build.dto;

import com.devmind.project.dto.WorkItemBrief;

import java.time.Instant;

public record BuildView(
        Long id,
        String projectId,
        String workItemId,
        String commit,
        String branch,
        String executor,
        String artifactRef,
        String status,
        Integer exitCode,
        String errorSummary,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        /** 关联工作单元摘要（列表回链需求展示；未关联或已删除时为 null） */
        WorkItemBrief workItem) {
}
