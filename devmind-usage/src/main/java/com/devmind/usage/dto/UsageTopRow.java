package com.devmind.usage.dto;

import java.time.Instant;

/** CAP-67 FR-04：用量 Top 明细行（会话/问答混合，成本降序）。 */
public record UsageTopRow(String source, String id, String title,
                          String requirementId, String requirementTitle, String projectId,
                          String model, String createdBy, Instant createdAt,
                          long turnCount, double costUsd, long inputTokens, long outputTokens,
                          long cacheReadTokens, long cacheCreationTokens) {
}
