package com.devmind.test.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 方案运行（父记录）视图：汇总 + 方案项结果 + 子运行摘要，供列表/详情/下钻复用。 */
public record TestPlanRunView(
        Long id,
        String projectId,
        Long planId,
        String planName,
        Integer planVersion,
        String mode,
        String status,
        String conclusion,
        Map<String, Object> summary,
        List<ItemResult> items,
        List<ChildRun> children,
        String errorSummary,
        String requirementId,
        Long environmentId,
        String baseUrl,
        String triggeredBy,
        Boolean cancelRequested,
        Long reportDocId,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        String planSnapshotJson,
        String envSnapshotJson) {

    public record ItemResult(
            Long itemId,
            String refType,
            Long refId,
            Integer seq,
            String parallelGroup,
            String refName,
            String status,
            String conclusion,
            Long runId,
            Integer attempts,
            Long durationMs,
            String shardKey,
            Integer caseCount,
            Integer passed,
            Integer failed,
            Integer blocked,
            Integer errors,
            String error) {
    }

    public record ChildRun(
            Long id,
            String status,
            String conclusion,
            String agentNodeId,
            String shardKey,
            Integer attempt,
            Long planItemId,
            RunSummary summary,
            String errorSummary,
            Instant startedAt,
            Instant finishedAt) {
    }
}
