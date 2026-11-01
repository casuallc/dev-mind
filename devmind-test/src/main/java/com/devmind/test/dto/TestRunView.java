package com.devmind.test.dto;

import com.devmind.project.dto.WorkItemBrief;

import java.time.Instant;
import java.util.List;

public record TestRunView(
        Long id,
        String projectId,
        String workItemId,
        List<Long> suiteIds,
        Long deploymentId,
        String agentNodeId,
        Long environmentId,
        String baseUrl,
        String status,
        RunSummary summary,
        Long reportDocId,
        String errorSummary,
        String triggeredBy,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        List<CaseResultView> results,
        /** 关联工作单元摘要（列表回链需求展示；未关联或已删除时为 null） */
        WorkItemBrief workItem,
        /** CAP-104：SUITE / CASE / SCRIPT */
        String runScope,
        /** CAP-104：PASS / FAIL / PARTIAL / BLOCKED */
        String conclusion,
        /** CAP-104 M2/M3：所属方案运行 / 方案项 / 重试次数 / 关联需求 / 分片键 */
        Long planRunId,
        Long planItemId,
        Integer attempt,
        String requirementId,
        String shardKey) {
}
