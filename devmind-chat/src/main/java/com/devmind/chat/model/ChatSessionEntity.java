package com.devmind.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * chat_sessions 表：CAP-30 通用问答元数据。
 * 无 project_id / worktree_path / base_branch 列——问答与项目资产零耦合。
 */
@Entity
@Table(name = "chat_sessions")
public class ChatSessionEntity {

    @Id
    @Column(length = 32)
    private String id;

    /** 标题 = 首条消息前 30 字符（去换行） */
    @Column(length = 128)
    private String title;

    @Column(length = 16)
    private String status;

    /** 远程执行节点 id（agent_nodes.id）；NULL = 本地子进程 */
    @Column(name = "agent_node_id", length = 64)
    private String agentNodeId;

    private Long pid;

    @Column(length = 64)
    private String model;

    @Column(name = "permission_mode", length = 32)
    private String permissionMode;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String summary;

    /** CAP-33 FR-05：场景 code（可空 = 未挂场景） */
    @Column(name = "scenario_code", length = 64)
    private String scenarioCode;

    /** CAP-33 FR-07：上下文装配快照（清单 JSON，无快照 = null） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "context_manifest_json", length = 16_777_216)
    private String contextManifestJson;

    /** CAP-33：首条消息原文（重建上下文包时按场景骨架重渲染的 {{task}} 输入） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "initial_prompt", length = 16_777_216)
    private String initialPrompt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAgentNodeId() { return agentNodeId; }
    public void setAgentNodeId(String agentNodeId) { this.agentNodeId = agentNodeId; }
    public Long getPid() { return pid; }
    public void setPid(Long pid) { this.pid = pid; }
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
    public String getInitialPrompt() { return initialPrompt; }
    public void setInitialPrompt(String initialPrompt) { this.initialPrompt = initialPrompt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
