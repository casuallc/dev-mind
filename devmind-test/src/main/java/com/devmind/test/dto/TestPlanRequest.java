package com.devmind.test.dto;

import java.util.List;
import java.util.Map;

/** CAP-104 M2 方案保存请求（items 整体替换；模板通过 isTemplate 标记）。 */
public record TestPlanRequest(
        String name,
        String description,
        Long environmentId,
        String failStrategy,
        Integer concurrency,
        Map<String, String> parameters,
        String requirementId,
        Boolean isTemplate,
        List<TestPlanItemInput> items) {

    public record TestPlanItemInput(
            String refType,
            Long refId,
            Integer refVersion,
            Integer seq,
            String parallelGroup,
            Map<String, Object> overrides,
            Boolean enabled) {
    }
}
