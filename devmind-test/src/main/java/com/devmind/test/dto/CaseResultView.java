package com.devmind.test.dto;

import com.devmind.test.spec.AssertionResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        Long duration,
        // CAP-104 过程采集与可解释判定
        String nodeId,
        Integer attempt,
        String failureCategory,
        List<AssertionResult> assertions,
        String command,
        Integer exitCode,
        Map<String, Object> envSnapshot,
        Long evidenceId,
        Instant startedAt,
        Instant finishedAt,
        String manualReview,
        String manualNote,
        // CAP-104 M1.5 程序型用例（非程序用例为 null）
        ProgramRunView program) {
}
