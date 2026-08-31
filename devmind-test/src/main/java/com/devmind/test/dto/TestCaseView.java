package com.devmind.test.dto;

import java.time.Instant;
import java.util.Map;

public record TestCaseView(
        Long id,
        Long suiteId,
        Integer sort,
        String name,
        String kind,
        String method,
        String path,
        Map<String, String> params,
        Map<String, String> headers,
        String body,
        Map<String, Object> expected,
        Boolean enabled,
        Instant updatedAt) {
}
