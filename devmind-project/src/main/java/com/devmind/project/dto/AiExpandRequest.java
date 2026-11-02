package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * CAP-105 需求 AI 扩展请求：points 为用户关键点（唯一素材）；
 * title/description 为表单当前值，仅作上下文让 AI 在其基础上扩写，均可空。
 */
public record AiExpandRequest(
        @NotBlank @Size(max = 2000) String points,
        @Size(max = 256) String title,
        @Size(max = 8000) String description) {
}
