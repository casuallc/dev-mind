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
 * test_case_results 表（CAP-10）：用例级结果。status = pass | fail | skip。
 * requestSummary / responseSummary / error 为可读摘要（供报告与缺陷线索）。
 */
@Entity
@Table(name = "test_case_results", indexes = {
        @Index(name = "idx_tcr_run_case_status", columnList = "run_id, case_id, status"),
        @Index(name = "idx_tcr_run_status_duration", columnList = "run_id, status, duration")
})
public class TestCaseResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "case_id")
    private Long caseId;

    @Column(name = "suite_id")
    private Long suiteId;

    @Column(nullable = false)
    private Integer sort;

    /** 结果快照（用例可能后续被删/改） */
    @Column(length = 128)
    private String name;

    /** pass / fail / skip */
    @Column(length = 16)
    private String status;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "request_summary", length = 16_777_216)
    private String requestSummary;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "response_summary", length = 16_777_216)
    private String responseSummary;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String error;

    private Long duration;

    @Column(name = "created_at")
    private Instant createdAt;

    // ---------------- CAP-104 过程采集与证据 ----------------

    /** 执行节点 id（SERVER 执行时留空） */
    @Column(name = "node_id", length = 64)
    private String nodeId;

    /** 第几次尝试（M1 恒为 1） */
    private Integer attempt;

    /** ASSERTION / INFRA / TIMEOUT / PRECHECK / SCRIPT_ERROR / UNKNOWN */
    @Column(name = "failure_category", length = 32)
    private String failureCategory;

    /** 断言明细 JSON 数组：[{source,op,expected,actual,pass,message}] */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "assertion_json", length = 16_777_216)
    private String assertionJson;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String command;

    @Column(name = "exit_code")
    private Integer exitCode;

    /** 环境快照 JSON（secret 掩码；节点、runner、OS、环境名/版本、变量、commit） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "env_snapshot_json", length = 16_777_216)
    private String envSnapshotJson;

    @Column(name = "evidence_id")
    private Long evidenceId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "working_dir", length = 512)
    private String workingDir;

    /** 人工复核：CONFIRMED / REJECTED（原始判定不变） */
    @Column(name = "manual_review", length = 16)
    private String manualReview;

    @Column(name = "manual_note", length = 1024)
    private String manualNote;

    // ---------------- CAP-104 M1.5 程序型用例 ----------------

    @Column(name = "program_asset_id")
    private Long programAssetId;

    @Column(name = "program_contract_version", length = 16)
    private String programContractVersion;

    /** 程序清单原文 JSON（asset sha256、runtime、entrypoint 等快照） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "program_manifest_json", length = 16_777_216)
    private String programManifestJson;

    /** result.json 的 status（pass/fail/blocked/skip/error） */
    @Column(name = "program_status", length = 16)
    private String programStatus;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "program_message", length = 16_777_216)
    private String programMessage;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "program_metrics_json", length = 16_777_216)
    private String programMetricsJson;

    /** 产物元数据 JSON：[{path,sizeBytes,sha256}]（内容在 ARTIFACT 证据里） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "program_artifacts_json", length = 16_777_216)
    private String programArtifactsJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public Long getSuiteId() { return suiteId; }
    public void setSuiteId(Long suiteId) { this.suiteId = suiteId; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRequestSummary() { return requestSummary; }
    public void setRequestSummary(String requestSummary) { this.requestSummary = requestSummary; }
    public String getResponseSummary() { return responseSummary; }
    public void setResponseSummary(String responseSummary) { this.responseSummary = responseSummary; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public String getFailureCategory() { return failureCategory; }
    public void setFailureCategory(String failureCategory) { this.failureCategory = failureCategory; }
    public String getAssertionJson() { return assertionJson; }
    public void setAssertionJson(String assertionJson) { this.assertionJson = assertionJson; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public String getEnvSnapshotJson() { return envSnapshotJson; }
    public void setEnvSnapshotJson(String envSnapshotJson) { this.envSnapshotJson = envSnapshotJson; }
    public Long getEvidenceId() { return evidenceId; }
    public void setEvidenceId(Long evidenceId) { this.evidenceId = evidenceId; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }
    public String getManualReview() { return manualReview; }
    public void setManualReview(String manualReview) { this.manualReview = manualReview; }
    public String getManualNote() { return manualNote; }
    public void setManualNote(String manualNote) { this.manualNote = manualNote; }
    public Long getProgramAssetId() { return programAssetId; }
    public void setProgramAssetId(Long programAssetId) { this.programAssetId = programAssetId; }
    public String getProgramContractVersion() { return programContractVersion; }
    public void setProgramContractVersion(String programContractVersion) { this.programContractVersion = programContractVersion; }
    public String getProgramManifestJson() { return programManifestJson; }
    public void setProgramManifestJson(String programManifestJson) { this.programManifestJson = programManifestJson; }
    public String getProgramStatus() { return programStatus; }
    public void setProgramStatus(String programStatus) { this.programStatus = programStatus; }
    public String getProgramMessage() { return programMessage; }
    public void setProgramMessage(String programMessage) { this.programMessage = programMessage; }
    public String getProgramMetricsJson() { return programMetricsJson; }
    public void setProgramMetricsJson(String programMetricsJson) { this.programMetricsJson = programMetricsJson; }
    public String getProgramArtifactsJson() { return programArtifactsJson; }
    public void setProgramArtifactsJson(String programArtifactsJson) { this.programArtifactsJson = programArtifactsJson; }
}
