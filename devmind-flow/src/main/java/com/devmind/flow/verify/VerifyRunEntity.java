package com.devmind.flow.verify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * verify_runs 表（CAP-73 FR-03）：一次「需求开发中验证」的全链追溯——
 * 推送需求分支 → 增量构建 → 补丁下发 → 冒烟。任一步失败即终态 FAILED + 失败阶段。
 *
 * <p>status：RUNNING / SUCCESS / FAILED（互斥语义 = 同需求同时刻仅一个 RUNNING，
 * 不设 QUEUED——并发触发直接 409 不排队）。stage 记录当前/失败阶段。</p>
 */
@Entity
@Table(name = "verify_runs")
public class VerifyRunEntity {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    public static final String STAGE_PUSHING = "PUSHING";
    public static final String STAGE_BUILDING = "BUILDING";
    public static final String STAGE_PATCHING = "PATCHING";
    public static final String STAGE_TESTING = "TESTING";

    public static final String TRIGGER_AGENT = "AGENT";
    public static final String TRIGGER_MANUAL = "MANUAL";

    public static final String PUSH_PUSHED = "PUSHED";
    public static final String PUSH_SKIPPED = "SKIPPED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requirement_id", nullable = false, length = 32)
    private String requirementId;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    /** AGENT / MANUAL */
    @Column(name = "trigger_type", nullable = false, length = 8)
    private String triggerType;

    /** 人工触发 = 用户名；agent 触发 = 会话 id */
    @Column(name = "triggered_by", length = 64)
    private String triggeredBy;

    @Column(name = "environment_id")
    private Long environmentId;

    /** 远端需求分支 HEAD（push 后实测） */
    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    /** PUSHED / SKIPPED（无会话工作区纯远端） */
    @Column(name = "push_status", length = 8)
    private String pushStatus;

    /** PUSHING/BUILDING/PATCHING/TESTING（当前/失败阶段） */
    @Column(name = "stage", length = 16)
    private String stage;

    /** RUNNING/SUCCESS/FAILED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** devmind-verify --modules 透传值（追溯用，平台不解释语义） */
    @Column(name = "modules_hint", length = 512)
    private String modulesHint;

    /** 补丁下发文件数（全量同步 = 全量文件数） */
    @Column(name = "files_changed")
    private Integer filesChanged;

    /** 全链总耗时 */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** 各阶段耗时 map（JSON） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "stage_timings_json", length = 16_777_216)
    private String stageTimingsJson;

    /** 失败阶段 + 日志尾部（脱敏截断；混合现场标注也在此） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "failure_summary", length = 16_777_216)
    private String failureSummary;

    /** devmind-verify --note / 人工备注 */
    @Column(name = "note", length = 512)
    private String note;

    /** 本次 run 的构建节点（bundle 上传绑定校验用） */
    @Column(name = "build_node_id", length = 32)
    private String buildNodeId;

    /** 本次 run 的环境节点（bundle 下载绑定校验用） */
    @Column(name = "env_node_id", length = 32)
    private String envNodeId;

    /** 冒烟测试运行 id（未执行冒烟 = null） */
    @Column(name = "test_run_id")
    private Long testRunId;

    /** 全链日志快照（v1 不走日志 Hub，直接落列） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "logs_text", length = 16_777_216)
    private String logsText;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
    public Long getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(Long environmentId) { this.environmentId = environmentId; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public String getPushStatus() { return pushStatus; }
    public void setPushStatus(String pushStatus) { this.pushStatus = pushStatus; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getModulesHint() { return modulesHint; }
    public void setModulesHint(String modulesHint) { this.modulesHint = modulesHint; }
    public Integer getFilesChanged() { return filesChanged; }
    public void setFilesChanged(Integer filesChanged) { this.filesChanged = filesChanged; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getStageTimingsJson() { return stageTimingsJson; }
    public void setStageTimingsJson(String stageTimingsJson) { this.stageTimingsJson = stageTimingsJson; }
    public String getFailureSummary() { return failureSummary; }
    public void setFailureSummary(String failureSummary) { this.failureSummary = failureSummary; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getBuildNodeId() { return buildNodeId; }
    public void setBuildNodeId(String buildNodeId) { this.buildNodeId = buildNodeId; }
    public String getEnvNodeId() { return envNodeId; }
    public void setEnvNodeId(String envNodeId) { this.envNodeId = envNodeId; }
    public Long getTestRunId() { return testRunId; }
    public void setTestRunId(Long testRunId) { this.testRunId = testRunId; }
    public String getLogsText() { return logsText; }
    public void setLogsText(String logsText) { this.logsText = logsText; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
