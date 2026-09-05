package com.devmind.integration.dto;

/** CAP-19 FR-08：执行 Jira 工作流转换请求（transitionId 必须来自当前可用转换清单） */
public record JiraTransitionRequest(String transitionId) {
}
