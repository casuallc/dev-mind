package com.devmind.test.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * test_runs 表（CAP-10）：一次测试执行。状态机 QUEUED → RUNNING → SUCCESS（无失败用例）| FAILED。
 * suiteIdsJson 为选中的套件 id 数组；summaryJson = {"total":n,"passed":n,"failed":n,"skipped":n}；
 * reportDocId 为沉淀到 docs-repo 的 report 文档 id（FR-04）。
 */
@Entity
@Table(name = "test_runs")
public class TestRunEntity {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    /** P0-6 关联约定：需求 id（可空 = 项目级回归） */
    @Column(name = "requirement_id", length = 32)
    private String requirementId;

    @Lob
    @Column(name = "suite_ids_json")
    private String suiteIdsJson;

    @Column(name = "deployment_id")
    private Long deploymentId;

    @Column(name = "server_id")
    private Long serverId;

    /** 目标环境（P1-1 environments 表 id；环境提供默认服务器与 baseUrl 变量） */
    @Column(name = "environment_id")
    private Long environmentId;

    /** API 测试目标 base URL（http 用例），可空（从服务器推导） */
    @Column(length = 512)
    private String baseUrl;

    @Column(length = 16)
    private String status;

    @Lob
    @Column(name = "summary_json")
    private String summaryJson;

    @Column(name = "report_doc_id")
    private Long reportDocId;

    @Lob
    @Column(name = "error_summary")
    private String errorSummary;

    @Lob
    @Column(name = "logs_text")
    private String logsText;

    /** user / deploy */
    @Column(name = "triggered_by", length = 16)
    private String triggeredBy;

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
    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public String getSuiteIdsJson() { return suiteIdsJson; }
    public void setSuiteIdsJson(String suiteIdsJson) { this.suiteIdsJson = suiteIdsJson; }
    public Long getDeploymentId() { return deploymentId; }
    public void setDeploymentId(Long deploymentId) { this.deploymentId = deploymentId; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public Long getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(Long environmentId) { this.environmentId = environmentId; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public Long getReportDocId() { return reportDocId; }
    public void setReportDocId(Long reportDocId) { this.reportDocId = reportDocId; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public String getLogsText() { return logsText; }
    public void setLogsText(String logsText) { this.logsText = logsText; }
    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
