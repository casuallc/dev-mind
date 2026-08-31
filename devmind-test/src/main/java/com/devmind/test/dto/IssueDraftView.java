package com.devmind.test.dto;

/**
 * 缺陷线索（FR-06）：失败用例汇总，供流程层一键建缺陷单并派修复 Agent。
 * title 为缺陷标题；expected/actual 为期望与实际（响应摘要或错误）。
 */
public record IssueDraftView(
        Long runId,
        Long caseId,
        String title,
        String requestSummary,
        String expected,
        String actual,
        String status) {
}
