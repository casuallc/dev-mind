package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 需求状态推进请求（人工/API 驱动，转换规则不写死）。
 */
public record RequirementStatusRequest(@NotBlank String status, String comment) {
}
