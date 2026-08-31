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
 * test_case_results 表（CAP-10）：用例级结果。status = pass | fail | skip。
 * requestSummary / responseSummary / error 为可读摘要（供报告与缺陷线索）。
 */
@Entity
@Table(name = "test_case_results")
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
    @Column(name = "request_summary")
    private String requestSummary;

    @Lob
    @Column(name = "response_summary")
    private String responseSummary;

    @Lob
    private String error;

    private Long duration;

    @Column(name = "created_at")
    private Instant createdAt;

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
}
