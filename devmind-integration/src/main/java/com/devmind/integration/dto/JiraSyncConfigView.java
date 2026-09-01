package com.devmind.integration.dto;

import java.time.Instant;

/**
 * Jira 同步配置视图（含运行状态：上次同步时刻/水印/计数/错误）。
 * firstSyncDays = 首轮同步窗口（天），仅无水印的首轮生效。
 */
public record JiraSyncConfigView(Long id, Long integrationId, String integrationName,
                                 String projectId, String jiraProjectKey, String jql,
                                 boolean enabled, int pollIntervalSec, int firstSyncDays,
                                 Instant lastSyncAt, Instant lastWatermark,
                                 Integer lastImported, Integer lastUpdatedCount, String lastError,
                                 Instant createdAt, Instant updatedAt) {}
