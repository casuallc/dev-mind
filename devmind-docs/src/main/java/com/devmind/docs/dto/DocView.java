package com.devmind.docs.dto;

import java.time.Instant;
import java.util.List;

/**
 * 文档列表/检索视图（FR-06）：元数据，不含正文。
 */
public record DocView(
        Long id,
        String kind,
        String requirementId,
        String projectId,
        String title,
        int currentVersion,
        String status,
        List<String> tags,
        String filePath,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
