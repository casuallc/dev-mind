package com.devmind.docs.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 文档主表（CAP-03 FR-01/FR-02/FR-04）。内容本体在 {@link DocumentVersionEntity}，
 * 每次保存生成新版本；文件镜像到 docs-repo。
 */
@Entity
@Table(name = "documents")
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** requirement | design | api-suite | report（FR-01） */
    @Column(length = 20)
    private String kind;

    /** 关联需求 ID（CAP-13 主线，requirement/design 用，可为空） */
    @Column(name = "requirement_id", length = 64)
    private String requirementId;

    /** 关联工作单元 ID（CAP-13 主线，可为空） */
    @Column(name = "work_item_id", length = 64)
    private String workItemId;

    /** 归属项目（design/api-suite 建议填；可为空） */
    @Column(length = 64)
    private String projectId;

    @Column(length = 200)
    private String title;

    /** 当前版本号（1 起，每次保存 +1） */
    private int currentVersion;

    /** draft | pending_confirm | frozen（FR-04 状态机） */
    @Column(length = 24)
    private String status;

    /** 标签，逗号分隔（FR-06 检索） */
    @Column(length = 500)
    private String tags;

    @Column(length = 64)
    private String createdBy;

    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public int getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(int currentVersion) { this.currentVersion = currentVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
