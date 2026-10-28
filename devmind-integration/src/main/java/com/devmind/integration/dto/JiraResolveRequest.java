package com.devmind.integration.dto;

/** CAP-101：验收联动解决 Jira 请求（seconds 为空 = 只解决不登记工时；comment 写入 worklog comment） */
public record JiraResolveRequest(Long seconds, String comment) {
}
