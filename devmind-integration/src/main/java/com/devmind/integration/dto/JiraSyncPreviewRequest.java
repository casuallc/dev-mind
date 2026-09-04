package com.devmind.integration.dto;

/**
 * JQL 预览请求（创建/编辑同步配置时实时试算过滤结果）：
 * 与正式同步同一套拼装规则——project + 附加 JQL，无其他过滤。
 */
public record JiraSyncPreviewRequest(Long integrationId, String jiraProjectKey, String jql) {}
