package com.devmind.integration.dto;

/**
 * CAP-47 FR-02 通用下拉选项：id 为回传值（任务类型 id / Jira 项目 key；优先级 id 可能为空），
 * name 为展示名（原样透出实例词表，不做映射）。
 */
public record JiraOptionView(String id, String name) {
}
