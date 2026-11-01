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
 * test_cases 表（CAP-10）：套件内用例。kind = http（对 baseUrl 发 HTTP 请求校验 expected）|
 * health（走 CAP-07 健康检查）。
 * params/headers/expected 均为 JSON 字符串；body 为请求体 JSON 文本。
 * expected 格式：http → {"status":200,"contains":"…"}（status 可为 200 或 "2XX"）；
 * health → {"type":"http","url":"…","status":200} 或 {"type":"command","command":"…"}。
 */
@Entity
@Table(name = "test_cases")
public class TestCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "suite_id", nullable = false)
    private Long suiteId;

    @Column(nullable = false)
    private Integer sort;

    @Column(length = 128)
    private String name;

    /** http / health */
    @Column(length = 16)
    private String kind;

    /** GET / POST / PUT / DELETE …（http 用例） */
    @Column(length = 16)
    private String method;

    /** 相对路径，如 /api/users（http 用例） */
    @Column(length = 512)
    private String path;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "params_json", length = 16_777_216)
    private String paramsJson;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "headers_json", length = 16_777_216)
    private String headersJson;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "body_json", length = 16_777_216)
    private String bodyJson;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "expected_json", length = 16_777_216)
    private String expectedJson;

    private Boolean enabled = true;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // ---------------- CAP-104 资产字段（旧用例启动时回填） ----------------

    /** 项目内唯一用例键（发布后不可改；旧用例回填 LEGACY-<id>） */
    @Column(name = "case_key", length = 128)
    private String caseKey;

    /** HTTP / COMMAND / MANUAL（旧用例按 kind 回填） */
    @Column(length = 16)
    private String type;

    /** P0 / P1 / P2，可空 */
    @Column(length = 8)
    private String priority;

    @Column(length = 256)
    private String tags;

    /** CaseSpec JSON；旧用例为空时走 CAP-10 旧字段执行 */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "spec_json", length = 16_777_216)
    private String specJson;

    @Column(name = "case_version")
    private Integer caseVersion;

    /** DRAFT / APPROVED / DEPRECATED */
    @Column(name = "review_status", length = 16)
    private String reviewStatus;

    /** 归一化签名（type + 请求 + 断言），用于重复提示 */
    @Column(length = 64)
    private String signature;

    /** CAP-104 资产引用（未评审资产不可运行） */
    @Column(name = "source_asset_id")
    private Long sourceAssetId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSuiteId() { return suiteId; }
    public void setSuiteId(Long suiteId) { this.suiteId = suiteId; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }
    public String getHeadersJson() { return headersJson; }
    public void setHeadersJson(String headersJson) { this.headersJson = headersJson; }
    public String getBodyJson() { return bodyJson; }
    public void setBodyJson(String bodyJson) { this.bodyJson = bodyJson; }
    public String getExpectedJson() { return expectedJson; }
    public void setExpectedJson(String expectedJson) { this.expectedJson = expectedJson; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getCaseKey() { return caseKey; }
    public void setCaseKey(String caseKey) { this.caseKey = caseKey; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getSpecJson() { return specJson; }
    public void setSpecJson(String specJson) { this.specJson = specJson; }
    public Integer getCaseVersion() { return caseVersion; }
    public void setCaseVersion(Integer caseVersion) { this.caseVersion = caseVersion; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public Long getSourceAssetId() { return sourceAssetId; }
    public void setSourceAssetId(Long sourceAssetId) { this.sourceAssetId = sourceAssetId; }
}
