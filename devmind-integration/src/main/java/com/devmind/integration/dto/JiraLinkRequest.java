package com.devmind.integration.dto;

/**
 * CAP-112：把 LOCAL 需求手动关联到**已存在**的 Jira issue 的入参。
 * issueKey 服务端归一化（trim + 大写）并校验形态（如 PROJ-123）。
 */
public record JiraLinkRequest(Long integrationId, String issueKey) {
}
