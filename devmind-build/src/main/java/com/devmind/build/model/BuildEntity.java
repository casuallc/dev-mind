package com.devmind.build.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * builds 表（CAP-08 FR-04/05/06）：一次构建记录。
 * 状态机 QUEUED → RUNNING → SUCCESS | FAILED；失败保留退出码与错误摘要；
 * steps_snapshot 固化触发时的步骤清单；logs_text 全量留存（WebSocket 实时流同一份）。
 */
@Entity
@Table(name = "builds")
public class BuildEntity {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    @Column(name = "task_id", length = 32)
    private String taskId;

    /** commit 是 H2 保留字，列名用 commit_sha */
    @Column(name = "commit_sha", length = 128)
    private String commit;

    @Column(length = 128)
    private String branch;

    /** LOCAL / REMOTE */
    @Column(length = 16)
    private String executor;

    /** 远程执行的目标服务器（触发时固化，配置可再改） */
    @Column(name = "remote_server_id")
    private Long remoteServerId;

    /** 触发时固化的步骤清单（JSON: [{name,command,workingDir,location}]） */
    @Lob
    @Column(name = "steps_snapshot")
    private String stepsSnapshot;

    /** 构建成功后登记的制品引用（FR-04，供部署/发版） */
    @Column(name = "artifact_ref", length = 512)
    private String artifactRef;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    /** QUEUED / RUNNING / SUCCESS / FAILED */
    @Column(length = 16)
    private String status;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Lob
    @Column(name = "error_summary")
    private String errorSummary;

    @Lob
    @Column(name = "logs_text")
    private String logsText;

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
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getCommit() { return commit; }
    public void setCommit(String commit) { this.commit = commit; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getExecutor() { return executor; }
    public void setExecutor(String executor) { this.executor = executor; }
    public Long getRemoteServerId() { return remoteServerId; }
    public void setRemoteServerId(Long remoteServerId) { this.remoteServerId = remoteServerId; }
    public String getStepsSnapshot() { return stepsSnapshot; }
    public void setStepsSnapshot(String stepsSnapshot) { this.stepsSnapshot = stepsSnapshot; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getArtifactRef() { return artifactRef; }
    public void setArtifactRef(String artifactRef) { this.artifactRef = artifactRef; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public String getLogsText() { return logsText; }
    public void setLogsText(String logsText) { this.logsText = logsText; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
