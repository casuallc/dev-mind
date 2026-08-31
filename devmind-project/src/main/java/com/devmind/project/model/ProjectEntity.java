package com.devmind.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * projects 表（CAP-02 FR-01）：本地 git 仓库项目注册。
 * tags 以逗号分隔存储（FR-02），contextSummary 为生成后可人工修正的项目上下文（FR-07）。
 */
@Entity
@Table(name = "projects")
public class ProjectEntity {

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    /** 本地 git 仓库绝对路径 */
    @Column(nullable = false, length = 512)
    private String path;

    @Column(name = "default_branch", length = 128)
    private String defaultBranch;

    /** 逗号分隔的标签（java/backend/frontend/...） */
    @Column(length = 512)
    private String tags;

    @Lob
    @Column(name = "description")
    private String description;

    /** ACTIVE / ARCHIVED */
    @Column(length = 16)
    private String status;

    /** 指向 OpenAPI 文件（项目内路径或文档库），供测试套件生成（FR-06） */
    @Column(name = "api_doc_source", length = 512)
    private String apiDocSource;

    /** 项目上下文摘要（FR-07，可人工修正） */
    @Lob
    @Column(name = "context_summary")
    private String contextSummary;

    @Column(name = "summary_generated_at")
    private Instant summaryGeneratedAt;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getApiDocSource() { return apiDocSource; }
    public void setApiDocSource(String apiDocSource) { this.apiDocSource = apiDocSource; }
    public String getContextSummary() { return contextSummary; }
    public void setContextSummary(String contextSummary) { this.contextSummary = contextSummary; }
    public Instant getSummaryGeneratedAt() { return summaryGeneratedAt; }
    public void setSummaryGeneratedAt(Instant summaryGeneratedAt) { this.summaryGeneratedAt = summaryGeneratedAt; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
