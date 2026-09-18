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
 * CAP-47 FR-10：项目级 Jira 推送默认值模板。
 * 一个项目一行——打开推送弹窗时自动带入，减少重复填字段。
 *
 * <p>{@code extraFieldsJson} 存动态字段默认值（JSON，键=Jira字段id，值=Jira API 取值形态），
 * 不存 control/options（随 Jira 配置变化，由 createmeta 实时拉取）。
 */
@Entity
@Table(name = "jira_push_defaults",
        uniqueConstraints = @UniqueConstraint(columnNames = "project_id"))
public class JiraPushDefaultsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** integrations.id（JIRA 型）——默认推送目标实例 */
    @Column(name = "integration_id", nullable = false)
    private Long integrationId;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    /** 默认 Jira 项目 key（如 ADMQ） */
    @Column(name = "jira_project_key", length = 64)
    private String jiraProjectKey;

    /** 默认任务类型 id */
    @Column(name = "issue_type_id", length = 32)
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
    public Long getIntegrationId() { return integrationId; }
    public void setIntegrationId(Long integrationId) { this.integrationId = integrationId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
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
