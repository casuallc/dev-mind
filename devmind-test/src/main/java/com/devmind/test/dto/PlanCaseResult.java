package com.devmind.test.dto;

/** 方案运行下的最终用例结果（重试取最后一次 attempt；分片/矩阵每个子运行各一份）。 */
public record PlanCaseResult(
        Long caseId,
        String caseKey,
        String name,
        Integer version,
        String status,
        String failureCategory,
        Long runId,
        String nodeId,
        String shardKey,
        Long durationMs,
        String error) {
}
