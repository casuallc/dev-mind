package com.devmind.integration.dto;

import java.time.Instant;

/**
 * Jira 同步配置视图（含运行状态：上次同步时刻/计数/错误）。
 */
public record JiraSyncConfigView(Long id, Long integrationId, String integrationName,
                                 String projectId, String jiraProjectKey, String jql,
                                 boolean enabled, int pollIntervalSec,
                                 Instant lastSyncAt,
                                 Integer lastImported, Integer lastUpdatedCount, String lastError,
                                 Instant createdAt, Instant updatedAt) {}
