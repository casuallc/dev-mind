package com.devmind.test.dto;

import com.devmind.test.spec.CaseSpec;

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
        Instant updatedAt,
        // CAP-104 资产字段
        String caseKey,
        String type,
        String priority,
        String tags,
        Integer caseVersion,
        String reviewStatus,
        String signature,
        Long sourceAssetId,
        CaseSpec spec) {
}
