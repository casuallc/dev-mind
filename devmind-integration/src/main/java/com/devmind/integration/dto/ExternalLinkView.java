package com.devmind.integration.dto;

import java.time.Instant;

/**
 * External Link 视图（WI ↔ MR 等映射，详情页跳转用）。
 * identitySource 仅创建 MR 响应携带（CAP-35 FR-03：PERSONAL/BOT），其余场景为 null。
 */
public record ExternalLinkView(Long id, Long integrationId, String internalType, String internalId,
                               String externalType, String externalKey, String externalUrl,
                               String status, Instant createdAt, String identitySource) {}
