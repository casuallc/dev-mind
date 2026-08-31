package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 任务状态推进请求（人工/API 驱动，转换规则不写死）。
 */
public record TaskStatusRequest(@NotBlank String status, String comment) {
}
