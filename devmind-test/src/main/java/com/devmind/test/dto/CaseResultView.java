package com.devmind.test.dto;

public record CaseResultView(
        Long id,
        Long caseId,
        Long suiteId,
        Integer sort,
        String name,
        String status,
        String requestSummary,
        String responseSummary,
        String error,
        Long duration) {
}
