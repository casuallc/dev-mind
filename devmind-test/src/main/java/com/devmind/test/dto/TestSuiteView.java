package com.devmind.test.dto;

import java.time.Instant;
import java.util.List;

public record TestSuiteView(
        Long id,
        String projectId,
        String name,
        String kind,
        String source,
        Long docId,
        Integer caseCount,
        List<TestCaseView> cases,
        Instant createdAt) {
}
