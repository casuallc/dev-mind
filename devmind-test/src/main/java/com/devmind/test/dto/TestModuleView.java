package com.devmind.test.dto;

import java.time.Instant;

/** CAP-104 功能模块视图（项目 → 模块 → 套件 → 用例）。 */
public record TestModuleView(
        Long id,
        String projectId,
        String name,
        String description,
        String tags,
        String owner,
        Integer sortNo,
        long suiteCount,
        Instant createdAt,
        Instant updatedAt) {
}
