package com.devmind.integration.dto;

/** CAP-27：登记 Jira 工时请求（seconds 为本次登记时长秒数；comment 可空） */
public record JiraWorklogRequest(Long seconds, String comment) {
}
