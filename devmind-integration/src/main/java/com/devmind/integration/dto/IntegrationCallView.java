package com.devmind.integration.dto;

import java.time.Instant;

/**
 * 出站调用日志视图（不含凭据）。
 */
public record IntegrationCallView(Long id, Long integrationId, String action,
                                  String internalType, String internalId,
                                  String result, String error, String actor, Instant createdAt) {}
