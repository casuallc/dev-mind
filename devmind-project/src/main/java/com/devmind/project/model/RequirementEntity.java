package com.devmind.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * requirements 表（CAP-13 研发主线）：业务目标，主线关系的根。
 * 状态派生聚合为主（见 RequirementService.recomputeStatus），仅 ACCEPTANCE→DONE（人工验收）
 * 与 CANCELLED 为人工翻转。seq 项目内自增（展示 REQ-&lt;seq&gt;），(project_id, seq) 唯一。
 */
@Entity
@Table(name = "requirements", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "seq"}))
public class RequirementEntity {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ANALYZING = "ANALYZING";
    public static final String STATUS_DESIGNING = "DESIGNING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_ACCEPTANCE = "ACCEPTANCE";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 需求类型（对齐 Jira issue type，同步可直接映射） */
    public static final String TYPE_FEATURE = "FEATURE";
    public static final String TYPE_BUG = "BUG";
    public static final String TYPE_IMPROVEMENT = "IMPROVEMENT";
    public static final String TYPE_TASK = "TASK";

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    /** 项目内自增序号（展示 REQ-<seq>） */
    @Column(nullable = false)
    private Long seq;

    @Column(nullable = false, length = 256)
    private String title;

    @Lob
    @Column(name = "description")
    private String description;

    @Column(length = 24)
    private String status = STATUS_DRAFT;

    /** 需求类型：FEATURE/BUG/IMPROVEMENT/TASK，默认 FEATURE */
    @Column(length = 24)
    private String type = TYPE_FEATURE;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    /** 需求文档（docs 模块文档 id，可空后补） */
    @Column(name = "doc_id")
    private Long docId;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
