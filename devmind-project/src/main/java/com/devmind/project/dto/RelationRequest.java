package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 通用关系边创建请求：depends_on / implements / verifies / fixes / produced_by（可扩展）。
 */
public record RelationRequest(
        @NotBlank String fromType,
        @NotBlank String fromId,
        @NotBlank String toType,
        @NotBlank String toId,
        @NotBlank String relationType) {
}
