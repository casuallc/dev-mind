package com.devmind.session.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * sessions 表：会话元数据（短 ID 同时用于 worktree 目录名）。
 */
@Entity
@Table(name = "sessions")
public class SessionEntity {

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "project_id", length = 64)
    private String projectId;

    /** CAP-13 关联约定：工作单元 id（可空 = 项目级或分析型会话） */
    @Column(name = "work_item_id", length = 32)
    private String workItemId;

    /** CAP-13 关联约定：需求 id（分析型会话直挂需求；挂 workItem 时与其 requirementId 一致） */
    @Column(name = "requirement_id", length = 32)
    private String requirementId;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "task_spec", length = 16_777_216)
    private String taskSpec;

    @Column(name = "base_branch", length = 128)
    private String baseBranch;

    @Column(length = 16)
    private String status;

    @Column(name = "worktree_path", length = 512)
    private String worktreePath;

    /** CAP-21：远程执行节点 id（agent_nodes.id）；CAP-34 起新行恒有值，NULL = 历史本机会话（只读，不可 resume） */
    @Column(name = "agent_node_id", length = 64)
    private String agentNodeId;

    private Long pid;

    @Column(length = 64)
    private String model;

    /** 权限模式（acceptEdits/default/bypassPermissions/plan）；create 存值，resume 读回（修复恢复丢权限模式） */
    @Column(name = "permission_mode", length = 32)
    private String permissionMode;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String summary;

    /** CAP-33：创建时挂的场景 code（可空 = 无场景；旧 templateCode 迁移后同为此列） */
    @Column(name = "scenario_code", length = 64)
    private String scenarioCode;

    /** CAP-33 FR-07：装配时的上下文清单快照（JSON；只存清单不存包内容，重建=重跑装配） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "context_manifest_json", length = 16_777_216)
    private String contextManifestJson;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public String getTaskSpec() { return taskSpec; }
    public void setTaskSpec(String taskSpec) { this.taskSpec = taskSpec; }
    public String getBaseBranch() { return baseBranch; }
    public void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getWorktreePath() { return worktreePath; }
    public void setWorktreePath(String worktreePath) { this.worktreePath = worktreePath; }
    public String getAgentNodeId() { return agentNodeId; }
    public void setAgentNodeId(String agentNodeId) { this.agentNodeId = agentNodeId; }
    public Long getPid() { return pid; }
    public void setPid(Long pid) { this.pid = pid; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getScenarioCode() { return scenarioCode; }
    public void setScenarioCode(String scenarioCode) { this.scenarioCode = scenarioCode; }
    public String getContextManifestJson() { return contextManifestJson; }
    public void setContextManifestJson(String contextManifestJson) { this.contextManifestJson = contextManifestJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
