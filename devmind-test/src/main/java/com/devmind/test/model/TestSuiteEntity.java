package com.devmind.test.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * test_suites 表（CAP-10）：测试套件。kind = api（由 OpenAPI 生成的 API 套件）| smoke（冒烟套件）。
 * source = openapi（生成）/ manual（手工）。docId 为沉淀到 docs-repo 的 api-suite 文档（FR-03）。
 */
@Entity
@Table(name = "test_suites")
public class TestSuiteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    @Column(nullable = false, length = 128)
    private String name;

    /** api / smoke */
    @Column(length = 16)
    private String kind;

    /** openapi / manual */
    @Column(length = 32)
    private String source;

    /** 沉淀到 docs-repo 的 api-suite 文档 id（FR-03），可空 */
    @Column(name = "doc_id")
    private Long docId;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
