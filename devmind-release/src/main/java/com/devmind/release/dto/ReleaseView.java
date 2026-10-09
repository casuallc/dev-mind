package com.devmind.release.dto;

import com.devmind.project.dto.WorkItemBrief;

import java.time.Instant;
import java.util.List;

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
        WorkItemBrief workItem,
        /** 发版归集的工作单元（tag 区间 commit 解析 WI-<seq>；未归集为空列表） */
        List<WorkItemBrief> includedWorkItems,
        /** 发版归集的需求（tag 区间 commit 解析 REQ-<seq>；未归集为空列表） */
        List<IncludedRequirement> includedRequirements) {
}
