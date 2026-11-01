package com.devmind.test.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * CAP-104 M2 test_plan_items：方案项。refType=CASE 引用单个用例，refType=SUITE 引用整套
 * （运行时快照该套件下全部启用用例）；parallelGroup 相同的项并行执行，空 = 独占串行。
 */
@Entity
@Table(name = "test_plan_items")
public class TestPlanItemEntity {

    public static final String REF_CASE = "CASE";
    public static final String REF_SUITE = "SUITE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "ref_type", nullable = false, length = 16)
    private String refType;

    @Column(name = "ref_id", nullable = false)
    private Long refId;

    /** 固定用例版本（可空 = 运行时取当前 APPROVED 版本） */
    @Column(name = "ref_version")
    private Integer refVersion;

    @Column(nullable = false)
    private Integer seq;

    @Column(name = "parallel_group", length = 64)
    private String parallelGroup;

    /** 运行前覆盖：{parameters, env, agentNodeId, timeoutSec, retry{max,on}} */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "overrides_json", length = 16_777_216)
    private String overridesJson;

    @Column(nullable = false)
    private Boolean enabled = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }
    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }
    public Integer getRefVersion() { return refVersion; }
    public void setRefVersion(Integer refVersion) { this.refVersion = refVersion; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getParallelGroup() { return parallelGroup; }
    public void setParallelGroup(String parallelGroup) { this.parallelGroup = parallelGroup; }
    public String getOverridesJson() { return overridesJson; }
    public void setOverridesJson(String overridesJson) { this.overridesJson = overridesJson; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
