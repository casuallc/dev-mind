package com.devmind.skill.dto;

import java.time.Instant;
import java.util.List;

/**
 * Skill 列表视图。不带 contentMd/extraFrontmatter（Lob，列表不读）；
 * GLOBAL 的 projectId 展示为 null（落库是 ""，见 SkillEntity 类注释）。
 */
public record SkillView(
        String id,
        String scope,
        String projectId,
        String name,
        String description,
        List<String> tags,
        String status,
        int fileCount,
        int hitCount,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
