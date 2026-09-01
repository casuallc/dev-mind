package com.devmind.integration.dto;

/**
 * Jira 同步配置请求。jql 为附加过滤片段（不含 project/updated/order by，由同步服务拼装）；
 * pollIntervalSec 缺省 300；firstSyncDays 首轮同步窗口（天），缺省 7，0 = 不限（全量，慎用）。
 */
public record JiraSyncConfigRequest(Long integrationId, String jiraProjectKey, String jql,
                                    Boolean enabled, Integer pollIntervalSec, Integer firstSyncDays) {}
