package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 通用状态推进请求（requirement / design / work-item 共用）。
 */
public record StatusRequest(@NotBlank String status) {
}
