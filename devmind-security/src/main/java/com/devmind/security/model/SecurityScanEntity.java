package com.devmind.security.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * security_scans 表（CAP-66 FR-01）：一次扫描 = 项目 + 单仓库 + 基线 commit + 勾选引擎集。
 * 状态机 QUEUED → RUNNING → SUCCESS / FAILED / CANCELED；单引擎失败不拖垮整任务
 * （engine_status JSON 记每引擎子状态，任务整体仍 SUCCESS = 部分成功）。
 */
@Entity
@Table(name = "security_scans")
public class SecurityScanEntity {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CANCELED = "CANCELED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    /** project_repos.id */
    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(nullable = false, length = 128)
    private String branch;

    @Column(name = "baseline_commit_sha", length = 64)
    private String baselineCommitSha;

    /** 逗号分隔勾选集（sca/sast/secrets 至少一项） */
    @Column(nullable = false, length = 64)
    private String engines;

    @Column(name = "agent_node_id")
    private Long agentNodeId;

    @Column(length = 16)
    private String status = QUEUED;

    /** 引擎 → status/耗时/原始发现数/归一化数/错误摘要 的 JSON 对象 */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "engine_status", length = 16_777_216)
    private String engineStatus;

    /** diff 摘要冗余（列表免聚合） */
    @Column(name = "new_count")
    private Integer newCount;
    @Column(name = "existing_count")
    private Integer existingCount;
    @Column(name = "fixed_count")
    private Integer fixedCount;
    /** FR-10-1 白名单过滤计数 */
    @Column(name = "filtered_count")
    private Integer filteredCount;

    /** 执行日志（各引擎段拼接，WS 快照与详情页回放用；引擎日志行带 [engine] 前缀） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String logs;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getBaselineCommitSha() { return baselineCommitSha; }
    public void setBaselineCommitSha(String baselineCommitSha) { this.baselineCommitSha = baselineCommitSha; }
    public String getEngines() { return engines; }
    public void setEngines(String engines) { this.engines = engines; }
    public Long getAgentNodeId() { return agentNodeId; }
    public void setAgentNodeId(Long agentNodeId) { this.agentNodeId = agentNodeId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEngineStatus() { return engineStatus; }
    public void setEngineStatus(String engineStatus) { this.engineStatus = engineStatus; }
    public Integer getNewCount() { return newCount; }
    public void setNewCount(Integer newCount) { this.newCount = newCount; }
    public Integer getExistingCount() { return existingCount; }
    public void setExistingCount(Integer existingCount) { this.existingCount = existingCount; }
    public Integer getFixedCount() { return fixedCount; }
    public void setFixedCount(Integer fixedCount) { this.fixedCount = fixedCount; }
    public Integer getFilteredCount() { return filteredCount; }
    public void setFilteredCount(Integer filteredCount) { this.filteredCount = filteredCount; }
    public String getLogs() { return logs; }
    public void setLogs(String logs) { this.logs = logs; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
