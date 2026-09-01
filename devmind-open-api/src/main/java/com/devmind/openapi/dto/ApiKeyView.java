package com.devmind.openapi.dto;

import java.time.Instant;

/**
 * API Key 列表视图（管理端）：永远不含 secret 及其哈希。
 */
public record ApiKeyView(
        Long id,
        String accessKey,
        String name,
        Boolean enabled,
        Instant expiresAt,
        Instant lastUsedAt,
        String createdBy,
        Instant createdAt) {
}
