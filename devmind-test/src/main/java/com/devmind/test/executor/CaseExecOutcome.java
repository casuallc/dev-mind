package com.devmind.test.executor;

import com.devmind.test.spec.AssertionResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 单用例执行结果（TestRunService 负责落库与证据）。status = pass/fail/blocked/skip/error。 */
public record CaseExecOutcome(
        String status,
        String failureCategory,
        List<AssertionResult> assertions,
        String requestSummary,
        String responseSummary,
        String error,
        long durationMs,
        String nodeId,
        String command,
        Integer exitCode,
        String stdout,
        String stderr,
        String workingDir,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> envSnapshot,
        String cleanupLog,
        ProgramOutcome program) {

    /** 非程序用例（HTTP/MANUAL/普通 COMMAND）沿用原构造。 */
    public CaseExecOutcome(String status, String failureCategory, List<AssertionResult> assertions,
                           String requestSummary, String responseSummary, String error, long durationMs,
                           String nodeId, String command, Integer exitCode, String stdout, String stderr,
                           String workingDir, Instant startedAt, Instant finishedAt,
                           Map<String, Object> envSnapshot, String cleanupLog) {
        this(status, failureCategory, assertions, requestSummary, responseSummary, error, durationMs,
                nodeId, command, exitCode, stdout, stderr, workingDir, startedAt, finishedAt,
                envSnapshot, cleanupLog, null);
    }
}
