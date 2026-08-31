package com.devmind.deploy.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * deployments 表（CAP-09 FR-03/04/05）：一次部署单。
 * 状态机 PLANNED → RUNNING → SUCCESS | FAILED | ROLLED_BACK；
 * plan_json 固化创建时渲染好的步骤清单；rollback_of 指向被回滚的原始部署（手动回滚）；
 * backup_ref 记录备份步在远端产出的备份路径（`backup=` 行），供回滚步骤 `${backup}` 使用。
 */
@Entity
@Table(name = "deployments")
public class DeploymentEntity {

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

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    @Column(name = "build_id")
    private Long buildId;

    @Column(length = 64)
    private String env;

    /** 目标环境（P1-1 environments 表 id；与 env 名字冗余，便于追溯变量/服务器组来源） */
    @Column(name = "environment_id")
    private Long environmentId;

    /** 创建时渲染好的计划（JSON: [{name,type,templateCode,params}]） */
    @Lob
    @Column(name = "plan_json")
    private String planJson;

    /** PLANNED / RUNNING / SUCCESS / FAILED / ROLLED_BACK */
    @Column(length = 16)
    private String status;

    /** 当前（或最后）步骤序号 */
    @Column(name = "current_step")
    private Integer currentStep;

    @Column(name = "backup_ref", length = 512)
    private String backupRef;

    /** 手动回滚生成的部署单指向被回滚的原始部署 */
    @Column(name = "rollback_of")
    private Long rollbackOf;

    /** 执行前需确认（FR-07，流程层用；直接调用可不强制） */
    @Column(name = "confirm_required")
    private boolean confirmRequired;

    @Column(name = "confirmed")
    private boolean confirmed;

    @Lob
    @Column(name = "logs_text")
    private String logsText;

    @Lob
    @Column(name = "error_summary")
    private String errorSummary;

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
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public Long getBuildId() { return buildId; }
    public void setBuildId(Long buildId) { this.buildId = buildId; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public Long getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(Long environmentId) { this.environmentId = environmentId; }    public String getPlanJson() { return planJson; }
    public void setPlanJson(String planJson) { this.planJson = planJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getCurrentStep() { return currentStep; }
    public void setCurrentStep(Integer currentStep) { this.currentStep = currentStep; }
    public String getBackupRef() { return backupRef; }
    public void setBackupRef(String backupRef) { this.backupRef = backupRef; }
    public Long getRollbackOf() { return rollbackOf; }
    public void setRollbackOf(Long rollbackOf) { this.rollbackOf = rollbackOf; }
    public boolean isConfirmRequired() { return confirmRequired; }
    public void setConfirmRequired(boolean confirmRequired) { this.confirmRequired = confirmRequired; }
    public boolean isConfirmed() { return confirmed; }
    public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }
    public String getLogsText() { return logsText; }
    public void setLogsText(String logsText) { this.logsText = logsText; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
