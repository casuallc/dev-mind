package com.devmind.usage.dto;

/**
 * CAP-67 FR-02：分组行。key 为分组原值（requirementId / projectId / model / createdBy），
 * 未归属桶 key=null；projectId 仅需求行带出（前端跳需求详情用）。
 */
public record UsageBreakdownRow(String key, String label, String projectId,
                                long sessionCount, long chatCount, long turnCount, double costUsd,
                                long inputTokens, long outputTokens,
                                long cacheReadTokens, long cacheCreationTokens) {
}
