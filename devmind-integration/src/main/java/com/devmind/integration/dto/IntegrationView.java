package com.devmind.integration.dto;

import java.time.Instant;

/**
 * Integration 视图。永不含凭据明文，仅 hasToken 标识是否已配置。
 */
public record IntegrationView(Long id, String type, String name, String baseUrl, String authType,
                              boolean hasToken, String status, String configJson,
                              String createdBy, Instant createdAt, Instant updatedAt) {}
