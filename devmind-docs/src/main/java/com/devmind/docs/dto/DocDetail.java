package com.devmind.docs.dto;

import java.time.Instant;
import java.util.List;

/**
 * 文档详情（含正文）：默认当前版本，?version=vN 读指定版本（FR-02）。
 */
public record DocDetail(
        Long id,
        String kind,
        String requirementId,
        String projectId,
        String title,
        int versionNo,
        String status,
        List<String> tags,
        String contentMd,
        String changeNote,
        String commitSha,
        String filePath,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
