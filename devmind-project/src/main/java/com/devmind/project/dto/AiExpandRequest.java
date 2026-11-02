package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * CAP-105 AI 扩充请求：notes 必填（用户关键信息/零散要点），
 * title/type/priority 为当前表单已填上下文（可空，不做枚举强校验——与需求字段同口径保持开放）。
 */
public record AiExpandRequest(
        @NotBlank @Size(max = 4000) String notes,
        @Size(max = 256) String title,
        @Size(max = 24) String type,
        @Size(max = 24) String priority) {
}
