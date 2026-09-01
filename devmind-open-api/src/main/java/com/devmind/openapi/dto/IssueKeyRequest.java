package com.devmind.openapi.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * 签发 API Key 请求。expiresAt 可空 = 永不过期。
 */
public record IssueKeyRequest(
        @NotBlank String name,
        Instant expiresAt) {
}
