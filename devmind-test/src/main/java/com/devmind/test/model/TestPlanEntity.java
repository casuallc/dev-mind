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
 * CAP-104 M2 test_plans：测试方案。方案运行前固化快照（plan_runs.plan_snapshot_json），
 * 后续编辑不影响历史运行；isTemplate=true 的方案只用于复制，不可直接运行。
 */
@Entity
@Table(name = "test_plans")
public class TestPlanEntity {

    public static final String FAIL_FAST = "FAIL_FAST";
    public static final String CONTINUE = "CONTINUE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 1024)
    private String description;

    /** 目标环境（environments.id；运行前可覆盖） */
    @Column(name = "environment_id")
    private Long environmentId;

    /** FAIL_FAST / CONTINUE */
    @Column(name = "fail_strategy", length = 16)
    private String failStrategy;

    /** 并行组内最大并行度（>=1；默认 1 = 串行） */
    private Integer concurrency;

    /** 方案级参数（运行前可覆盖；secret 只经环境变量注入，不写入快照明文） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "parameters_json", length = 16_777_216)
    private String parametersJson;

    @Column(name = "requirement_id", length = 32)
    private String requirementId;

    @Column(name = "is_template", nullable = false)
    private Boolean isTemplate = false;

    /** 方案版本：每次保存 +1，运行快照记录该值 */
    @Column(name = "plan_version", nullable = false)
    private Integer planVersion = 1;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(Long environmentId) { this.environmentId = environmentId; }
    public String getFailStrategy() { return failStrategy; }
    public void setFailStrategy(String failStrategy) { this.failStrategy = failStrategy; }
    public Integer getConcurrency() { return concurrency; }
    public void setConcurrency(Integer concurrency) { this.concurrency = concurrency; }
    public String getParametersJson() { return parametersJson; }
    public void setParametersJson(String parametersJson) { this.parametersJson = parametersJson; }
    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public Boolean getIsTemplate() { return isTemplate; }
    public void setIsTemplate(Boolean isTemplate) { this.isTemplate = isTemplate; }
    public Integer getPlanVersion() { return planVersion; }
    public void setPlanVersion(Integer planVersion) { this.planVersion = planVersion; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
