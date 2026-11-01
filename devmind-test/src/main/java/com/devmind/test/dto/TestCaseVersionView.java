package com.devmind.test.dto;

import com.devmind.test.spec.CaseSpec;

import java.time.Instant;

/** CAP-104 用例不可变版本快照视图（历史只读）。 */
public record TestCaseVersionView(
        Long id,
        Long caseId,
        Integer versionNo,
        String reviewStatus,
        String changeNote,
        String createdBy,
        Instant createdAt,
        String reviewedBy,
        Instant reviewedAt,
        CaseSpec spec) {
}
