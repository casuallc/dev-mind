package com.devmind.integration.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * CAP-47 FR-10：个人 Jira 推送模板（取代原项目级单行默认值）。
 * 唯一键 = 用户 + 实例 + Jira 项目 + 任务类型——动态必填字段随「项目+类型」组合变化，
 * 一人可有多行；推送弹窗选定组合后自动带入匹配模板的字段默认值。
 *
 * <p>{@code extraFieldsJson} 存动态字段默认值（JSON，键=Jira字段id，值=Jira API 取值形态），
 * 不存 control/options（随 Jira 配置变化，由 createmeta 实时拉取）。
 */
@Entity
@Table(name = "jira_push_templates",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"user_id", "integration_id", "jira_project_key", "issue_type_id"}))
public class JiraPushTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** users.id——模板是 per-user 的，服务层按认证上下文隔离，ADMIN 也不能读他人 */
    @Column(name = "user_id", nullable = false, length = 32)
    private String userId;

    /** integrations.id（JIRA 型） */
    @Column(name = "integration_id", nullable = false)
    private Long integrationId;

    /** Jira 项目 key（如 ADMQ） */
    @Column(name = "jira_project_key", nullable = false, length = 64)
    private String jiraProjectKey;

    /** 任务类型 id（实例内的值，跨实例无意义） */
    @Column(name = "issue_type_id", nullable = false, length = 32)
    private String issueTypeId;

    /** 默认优先级 name */
    @Column(name = "priority_name", length = 64)
    private String priorityName;

    /** 默认经办人 name（Jira 登录名） */
    @Column(name = "assignee_name", length = 255)
    private String assigneeName;

    /** 默认标签（逗号拼接，同 RequirementEntity 口径） */
    @Column(name = "labels", length = 1024)
    private String labels;

    /** 动态字段默认值 JSON：{fieldId: jiraApiValue} */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "extra_fields", length = 16_777_216)
    private String extraFieldsJson;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getIntegrationId() { return integrationId; }
    public void setIntegrationId(Long integrationId) { this.integrationId = integrationId; }
    public String getJiraProjectKey() { return jiraProjectKey; }
    public void setJiraProjectKey(String jiraProjectKey) { this.jiraProjectKey = jiraProjectKey; }
    public String getIssueTypeId() { return issueTypeId; }
    public void setIssueTypeId(String issueTypeId) { this.issueTypeId = issueTypeId; }
    public String getPriorityName() { return priorityName; }
    public void setPriorityName(String priorityName) { this.priorityName = priorityName; }
    public String getAssigneeName() { return assigneeName; }
    public void setAssigneeName(String assigneeName) { this.assigneeName = assigneeName; }
    public String getLabels() { return labels; }
    public void setLabels(String labels) { this.labels = labels; }
    public String getExtraFieldsJson() { return extraFieldsJson; }
    public void setExtraFieldsJson(String extraFieldsJson) { this.extraFieldsJson = extraFieldsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
