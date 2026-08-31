package com.devmind.release.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * releases 表（CAP-11 FR-05 发版记录）：一次发版 = 版本号 + 产物引用 + 推送执行 + git tag。
 * 状态机 PLANNED/RUNNING/SUCCESS/FAILED/ROLLED_BACK（FR-06 回滚 = 移除制品引用 + 删 tag）。
 * 注：version 用 release_version 列名规避 H2/SQL 关键字。
 */
@Entity
@Table(name = "releases")
public class ReleaseEntity {

    public static final String PLANNED = "PLANNED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String ROLLED_BACK = "ROLLED_BACK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    @Column(name = "task_id", length = 32)
    private String taskId;

    /** 发版产物来源构建；artifacts 表登记过（artifactRef 不为空）才能发版 */
    @Column(name = "build_id")
    private Long buildId;

    @Column(name = "release_version", length = 64)
    private String releaseVersion;

    @Column(length = 16)
    private String status;

    /** 构建产物引用（来自 build.artifactRef） */
    @Column(name = "artifact_ref", length = 512)
    private String artifactRef;

    /** Nexus 制品引用（nexusRepo:version），成功即登记 */
    @Column(name = "nexus_ref", length = 512)
    private String nexusRef;

    /** git tag（v<version>，FR-04） */
    @Column(name = "tag_name", length = 256)
    private String tagName;

    /** 执行方式：LOCAL / REMOTE */
    @Column(length = 16)
    private String executor;

    /** 远程执行目标服务器 id（executor=REMOTE 时有效） */
    @Column(name = "server_id")
    private Long serverId;

    /** 回滚来源：本记录是对该发版的回滚 */
    @Column(name = "rollback_of")
    private Long rollbackOf;

    @Lob
    @Column(name = "logs_text")
    private String logsText;

    @Column(name = "error_summary")
    private String errorSummary;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Long getBuildId() { return buildId; }
    public void setBuildId(Long buildId) { this.buildId = buildId; }
    public String getReleaseVersion() { return releaseVersion; }
    public void setReleaseVersion(String releaseVersion) { this.releaseVersion = releaseVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getArtifactRef() { return artifactRef; }
    public void setArtifactRef(String artifactRef) { this.artifactRef = artifactRef; }
    public String getNexusRef() { return nexusRef; }
    public void setNexusRef(String nexusRef) { this.nexusRef = nexusRef; }
    public String getTagName() { return tagName; }
    public void setTagName(String tagName) { this.tagName = tagName; }
    public String getExecutor() { return executor; }
    public void setExecutor(String executor) { this.executor = executor; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public Long getRollbackOf() { return rollbackOf; }
    public void setRollbackOf(Long rollbackOf) { this.rollbackOf = rollbackOf; }
    public String getLogsText() { return logsText; }
    public void setLogsText(String logsText) { this.logsText = logsText; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
