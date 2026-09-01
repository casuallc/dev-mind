package com.devmind.integration.dto;

import java.time.Instant;

/**
 * External Link 视图（WI ↔ MR 等映射，详情页跳转用）。
 */
public record ExternalLinkView(Long id, Long integrationId, String internalType, String internalId,
                               String externalType, String externalKey, String externalUrl,
                               String status, Instant createdAt) {}
