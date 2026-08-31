package com.devmind.session.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

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

    /** P0-6 关联约定：需求 id（可空 = 项目级会话） */
    @Column(name = "requirement_id", length = 32)
    private String requirementId;

    @Lob
    @Column(name = "task_spec")
    private String taskSpec;

    @Column(name = "base_branch", length = 128)
    private String baseBranch;

    @Column(length = 16)
    private String status;

    @Column(name = "worktree_path", length = 512)
    private String worktreePath;

    private Long pid;

    @Column(length = 64)
    private String model;

    @Lob
    private String summary;

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
    public Long getPid() { return pid; }
    public void setPid(Long pid) { this.pid = pid; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
