package com.devmind.usage.dto;

/** CAP-67 FR-01：总体汇总（会话 + 问答两源合并）。 */
public record UsageSummary(double costUsd, long inputTokens, long outputTokens,
                           long cacheReadTokens, long cacheCreationTokens, long turnCount,
                           long sessionCount, long chatCount) {
}
