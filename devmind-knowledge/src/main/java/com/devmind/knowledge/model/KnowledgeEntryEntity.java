package com.devmind.knowledge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 经验条目（CAP-04 FR-02/FR-03）：global（通用，可按标签筛选）/ project（项目特有）。
 * 会话注入时按 scope+标签命中组装进 CLAUDE.md。
 */
@Entity
@Table(name = "knowledge_entries")
public class KnowledgeEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** global | project（FR-01 三层结构，inbox 走提案表） */
    @Column(length = 16)
    private String scope;

    /** project 范围所属项目；global 为 null */
    @Column(length = 64)
    private String projectId;

    /** 条目名称（如"前端样式规范"） */
    @Column(length = 200)
    private String name;

    /** 逻辑路径（如 global/frontend/antd-styles.md），仅作组织展示 */
    @Column(length = 255)
    private String path;

    @Lob
    @Column(length = 16_777_216)
    private String contentMd;

    /** 标签，逗号分隔（global 条目用于按项目 tags 匹配注入） */
    @Column(length = 500)
    private String tags;

    /** 来源项目（沉淀自哪个项目） */
    @Column(length = 64)
    private String sourceProject;

    /** 被注入次数（FR-07 清理依据） */
    private int hitCount;

    /** active | deprecated（FR-07） */
    @Column(length = 16)
    private String status;

    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getContentMd() { return contentMd; }
    public void setContentMd(String contentMd) { this.contentMd = contentMd; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getSourceProject() { return sourceProject; }
    public void setSourceProject(String sourceProject) { this.sourceProject = sourceProject; }
    public int getHitCount() { return hitCount; }
    public void setHitCount(int hitCount) { this.hitCount = hitCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
