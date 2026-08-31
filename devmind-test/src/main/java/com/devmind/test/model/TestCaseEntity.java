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
    @Column(name = "params_json")
    private String paramsJson;

    @Lob
    @Column(name = "headers_json")
    private String headersJson;

    @Lob
    @Column(name = "body_json")
    private String bodyJson;

    @Lob
    @Column(name = "expected_json")
    private String expectedJson;

    private Boolean enabled = true;

    @Column(name = "updated_at")
    private Instant updatedAt;

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
}
