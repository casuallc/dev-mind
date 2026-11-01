package com.devmind.test.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * CAP-104 M2 test_plan_runs：方案运行（父记录）。子用例运行在 test_runs.plan_run_id 下挂，
 * 父记录只存快照与汇总，不复制用例结果；删除方案不影响历史运行。
 */
@Entity
@Table(name = "test_plan_runs", indexes = {
        @Index(name = "idx_test_plan_runs_project_created", columnList = "project_id, created_at"),
        @Index(name = "idx_test_plan_runs_plan", columnList = "plan_id")
})
public class TestPlanRunEntity {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String BLOCKED = "BLOCKED";
    public static final String CANCELLED = "CANCELLED";

    public static final String MODE_SEQUENTIAL = "SEQUENTIAL";
    public static final String MODE_SHARD = "SHARD";
    public static final String MODE_MATRIX = "MATRIX";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "plan_name", length = 128)
    private String planName;

    @Column(name = "plan_version")
    private Integer planVersion;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "plan_snapshot_json", length = 16_777_216)
    private String planSnapshotJson;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "env_snapshot_json", length = 16_777_216)
    private String envSnapshotJson;

    /** 运行前环境预检结果（多节点时含逐节点报告） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "precheck_json", length = 16_777_216)
    private String precheckJson;

    /** SEQUENTIAL / SHARD / MATRIX */
    @Column(length = 16)
    private String mode;

    /** PASS / FAIL / PARTIAL / BLOCKED / CANCELLED */
    @Column(length = 16)
    private String conclusion;

    @Column(length = 16)
    private String status;

    /** {"items":n,"executed":n,"passed":n,"failed":n,"blocked":n,"skipped":n,"cancelled":n,"total":n,...} */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "summary_json", length = 16_777_216)
    private String summaryJson;

    /** 每方案项/分片的终态与子运行 id（下钻用） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "item_results_json", length = 16_777_216)
    private String itemResultsJson;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "error_summary", length = 16_777_216)
    private String errorSummary;

    @Column(name = "requirement_id", length = 32)
    private String requirementId;

    @Column(name = "environment_id")
    private Long environmentId;

    @Column(name = "base_url", length = 512)
    private String baseUrl;

    @Column(name = "triggered_by", length = 16)
    private String triggeredBy;

    /** 取消请求：编排线程在每个方案项前检查，未启动项记 CANCELLED */
    @Column(name = "cancel_requested", nullable = false)
    private Boolean cancelRequested = false;

    @Column(name = "report_doc_id")
    private Long reportDocId;

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
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }
    public Integer getPlanVersion() { return planVersion; }
    public void setPlanVersion(Integer planVersion) { this.planVersion = planVersion; }
    public String getPlanSnapshotJson() { return planSnapshotJson; }
    public void setPlanSnapshotJson(String planSnapshotJson) { this.planSnapshotJson = planSnapshotJson; }
    public String getEnvSnapshotJson() { return envSnapshotJson; }
    public void setEnvSnapshotJson(String envSnapshotJson) { this.envSnapshotJson = envSnapshotJson; }
    public String getPrecheckJson() { return precheckJson; }
    public void setPrecheckJson(String precheckJson) { this.precheckJson = precheckJson; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public String getItemResultsJson() { return itemResultsJson; }
    public void setItemResultsJson(String itemResultsJson) { this.itemResultsJson = itemResultsJson; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public Long getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(Long environmentId) { this.environmentId = environmentId; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
    public Boolean getCancelRequested() { return cancelRequested; }
    public void setCancelRequested(Boolean cancelRequested) { this.cancelRequested = cancelRequested; }
    public Long getReportDocId() { return reportDocId; }
    public void setReportDocId(Long reportDocId) { this.reportDocId = reportDocId; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
