package com.devmind.test.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * CAP-104 M2 test_baselines：基线（某次方案运行通过的用例版本与结果快照），用于回归对比。
 * 对比只读历史数据，不回写运行记录。
 */
@Entity
@Table(name = "test_baselines")
public class TestBaselineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "product_version", length = 64)
    private String productVersion;

    @Column(name = "plan_id")
    private Long planId;

    /** 来源方案运行（可空：允许按当前方案的最近运行创建） */
    @Column(name = "plan_run_id")
    private Long planRunId;

    /** [{caseId,caseKey,caseVersion,name,status,failureCategory,runId}] */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "case_versions_json", length = 16_777_216)
    private String caseVersionsJson;

    /** {total,passed,failed,blocked,error,passRate,conclusion} */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "summary_json", length = 16_777_216)
    private String summaryJson;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProductVersion() { return productVersion; }
    public void setProductVersion(String productVersion) { this.productVersion = productVersion; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public Long getPlanRunId() { return planRunId; }
    public void setPlanRunId(Long planRunId) { this.planRunId = planRunId; }
    public String getCaseVersionsJson() { return caseVersionsJson; }
    public void setCaseVersionsJson(String caseVersionsJson) { this.caseVersionsJson = caseVersionsJson; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
