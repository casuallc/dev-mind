package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 需求创建/更新请求。branchSlug 缺省时由 title 生成（需求分支 req/<seq>-<slug>）。
 */
public record RequirementRequest(
        @NotBlank String title,
        String description,
        String ownerId,
        String branchSlug,
        Long docId) {
}
