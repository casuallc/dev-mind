package com.devmind.knowledge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 知识库容器（CAP-44 FR-01）：条目的一等归属。scope 语义自条目上移（global=平台共享 /
 * project=项目特有，projectId 必填）；injectMode 区分消费方式——FULL=全量注入 CLAUDE.md
 * （原 CAP-04 经验库语义），RAG=仅检索（文档库语义，向量检索命中才进上下文）。
 */
@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBaseEntity {

    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_PROJECT = "project";
    public static final String INJECT_FULL = "FULL";
    public static final String INJECT_RAG = "RAG";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_ARCHIVED = "archived";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    /** global | project（全局共享 / 项目特有） */
    @Column(nullable = false, length = 16)
    private String scope = SCOPE_GLOBAL;

    /** scope=project 时必填 */
    @Column(length = 64)
    private String projectId;

    /** FULL=全量注入 CLAUDE.md | RAG=仅向量检索（CAP-46 会话消费） */
    @Column(nullable = false, length = 8)
    private String injectMode = INJECT_RAG;

    /** 覆盖平台默认 embedding 模型（空=走 devmind.knowledge.embedding.model） */
    @Column(length = 64)
    private String embeddingModel;

    /** active | archived（archived 不参与注入与检索） */
    @Column(nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getInjectMode() { return injectMode; }
    public void setInjectMode(String injectMode) { this.injectMode = injectMode; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
