package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 任务创建/更新请求。branchSlug 缺省时由 title 生成（任务分支 task/<seq>-<slug>）。
 */
public record TaskRequest(
        @NotBlank String title,
        String description,
        String ownerId,
        String branchSlug,
        Long docId) {
}
