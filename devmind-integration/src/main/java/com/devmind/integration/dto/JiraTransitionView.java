package com.devmind.integration.dto;

/** CAP-19 FR-08：Jira 工作流转换视图（id 执行时回传，toStatus 为目标状态名，可能为空） */
public record JiraTransitionView(String id, String name, String toStatus) {
}
