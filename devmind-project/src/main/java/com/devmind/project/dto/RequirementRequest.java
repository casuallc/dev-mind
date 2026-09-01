package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 需求创建/更新请求。
 */
public record RequirementRequest(
        @NotBlank String title,
        String description,
        String ownerId,
        Long docId,
        /** 需求类型：FEATURE/BUG/IMPROVEMENT/TASK，空则 FEATURE（创建）或不变（更新） */
        String type) {
}
