package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 需求创建/更新请求。
 */
public record RequirementRequest(
        @NotBlank String title,
        String description,
        String ownerId,
        Long docId) {
}
