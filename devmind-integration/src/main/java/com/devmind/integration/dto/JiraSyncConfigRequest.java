package com.devmind.integration.dto;

/**
 * Jira 同步配置请求。jql 为附加过滤片段（不含 project/order by，由同步服务拼装）；
 * 同步只按 project + 附加 JQL 过滤，不加其他条件。pollIntervalSec 缺省 300。
 */
public record JiraSyncConfigRequest(Long integrationId, String jiraProjectKey, String jql,
                                    Boolean enabled, Integer pollIntervalSec) {}
