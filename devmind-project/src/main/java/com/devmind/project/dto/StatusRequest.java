package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 通用状态推进请求（requirement / design / work-item 共用）。
 * force：仅需求 DONE 前置检查用——存在未完结工作单元时，true 强制完成（Jackson 3 布尔必用包装类型）。
 */
public record StatusRequest(@NotBlank String status, Boolean force) {
}
