package com.devmind.integration.dto;

/**
 * CAP-47 FR-02 经办人候选：name = 平台用户名（创建 issue 时回传给 {@code assignee.name}），
 * displayName = 界面展示名（实例无显示名时等于 name）。
 */
public record JiraAssignableUserView(String name, String displayName) {
}
