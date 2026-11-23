package com.devmind.session.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * CAP-115 terminal_workspaces 表：终端工作区登记载体——用户直接进 git worktree 用 bash
 * 做轻量代码改动（无 claude 会话），复用现有收口/释放链路。
 *
 * <p>workspace_key 规则：新建 = {@code term-<8位小写短id>}（{@code id} 去横线前 8 位）；
 * 挂接 = 既有键（如 {@code req-<requirementId>}，与需求编码会话共用同一棵工作树）。
 * branch 规则：新建 = {@code terminal/<同短id>}；挂接 = 既有键对应的既有分支快照。
 * state 机：OPEN → FINALIZED（收口合入基线）/ RELEASED（释放丢弃）。</p>
 */
@Entity
@Table(name = "terminal_workspaces", indexes = {
        @Index(name = "idx_term_ws_project", columnList = "project_id"),
        @Index(name = "idx_term_ws_key", columnList = "workspace_key"),
        @Index(name = "idx_term_ws_req", columnList = "requirement_id")
})
public class TerminalWorkspaceEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    /** 关联需求（可空 = 项目级终端工作区） */
    @Column(name = "requirement_id", length = 32)
    private String requirementId;

    /** 关联工作单元（可空） */
    @Column(name = "work_item_id", length = 32)
    private String workItemId;

    /** 工作区键（runner 侧 worktrees/<key> 目录名，见类注释） */
    @Column(name = "workspace_key", nullable = false, length = 64)
    private String workspaceKey;

    /** 工作区分支（新建 = terminal/<短id>；挂接 = 既有分支快照） */
    @Column(length = 128)
    private String branch;

    /** runner 侧工作区属主（worktrees 布局的 <owner> 段，= createdBy 用户名） */
    @Column(nullable = false, length = 64)
    private String owner;

    /** 执行节点 id（agent_nodes.id，路由落定后固定） */
    @Column(name = "agent_node_id", nullable = false, length = 64)
    private String agentNodeId;

    @Column(name = "base_branch", length = 128)
    private String baseBranch;

    /** 创建时从 project_repos 拷值的仓库快照（JSON 数组，字段对齐 SessionRepoEntity；生命周期内以快照为准） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "repos_json", length = 16_777_216)
    private String reposJson;

    /** OPEN / FINALIZED / RELEASED */
    @Column(length = 16)
    @ColumnDefault("'OPEN'")
    private String state = "OPEN";

    /** 终端会话占用锁：当前持有锁的终端会话 id（terminal_sessions.id） */
    @Column(name = "lock_session_id", length = 36)
    private String lockSessionId;

    /** 终端会话占用锁：持锁人用户名 */
    @Column(name = "lock_owner", length = 64)
    private String lockOwner;

    /** 终端会话占用锁：最近加锁/心跳续期时刻（90s 无心跳视为可抢占） */
    @Column(name = "lock_at")
    private Instant lockAt;

    /** 收口明细（merge manifest JSON：逐库 merge/push 结果） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "finalize_detail", length = 16_777_216)
    private String finalizeDetail;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    /** 最近活跃时刻（打开终端/执行命令/心跳均刷新；闲置提醒以此为据） */
    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    /** M3 闲置提醒最近发送时刻（每 24h 最多提醒一次的去重依据；null = 未提醒过） */
    @Column(name = "reminded_at")
    private Instant remindedAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public String getWorkspaceKey() { return workspaceKey; }
    public void setWorkspaceKey(String workspaceKey) { this.workspaceKey = workspaceKey; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getAgentNodeId() { return agentNodeId; }
    public void setAgentNodeId(String agentNodeId) { this.agentNodeId = agentNodeId; }
    public String getBaseBranch() { return baseBranch; }
    public void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }
    public String getReposJson() { return reposJson; }
    public void setReposJson(String reposJson) { this.reposJson = reposJson; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getLockSessionId() { return lockSessionId; }
    public void setLockSessionId(String lockSessionId) { this.lockSessionId = lockSessionId; }
    public String getLockOwner() { return lockOwner; }
    public void setLockOwner(String lockOwner) { this.lockOwner = lockOwner; }
    public Instant getLockAt() { return lockAt; }
    public void setLockAt(Instant lockAt) { this.lockAt = lockAt; }
    public String getFinalizeDetail() { return finalizeDetail; }
    public void setFinalizeDetail(String finalizeDetail) { this.finalizeDetail = finalizeDetail; }
    public Instant getFinalizedAt() { return finalizedAt; }
    public void setFinalizedAt(Instant finalizedAt) { this.finalizedAt = finalizedAt; }
    public Instant getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; }
    public Instant getRemindedAt() { return remindedAt; }
    public void setRemindedAt(Instant remindedAt) { this.remindedAt = remindedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
