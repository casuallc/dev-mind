package com.devmind.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * tasks 表（Task 主线）：项目内主线工作项，Task 内嵌 Requirement（title/description 即需求内容），每个 Task 一条独立流程。
 * 只做"身份 + 状态 + 关联"，不含流程引擎（流程属易变上层，后续作为组合层叠加）。
 * seq 为项目内自增序号（展示为 TASK-&lt;seq&gt;），(project_id, seq) 唯一。
 */
@Entity
@Table(name = "tasks", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "seq"}))
public class TaskEntity {

    // 状态最小集：DRAFT → DESIGNING → DEVELOPING → TESTING → ACCEPTANCE → DONE（+CANCELLED），转换规则不写死
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_DESIGNING = "DESIGNING";
    public static final String STATUS_DEVELOPING = "DEVELOPING";
    public static final String STATUS_TESTING = "TESTING";
    public static final String STATUS_ACCEPTANCE = "ACCEPTANCE";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    /** 项目内自增序号（展示 TASK-<seq>） */
    @Column(nullable = false)
    private Long seq;

    @Column(nullable = false, length = 256)
    private String title;

    @Lob
    @Column(name = "description")
    private String description;

    @Column(length = 24)
    private String status = STATUS_DRAFT;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    /** 任务分支 slug（分支 task/<seq>-<slug>，每 repo 一条） */
    @Column(name = "branch_slug", length = 64)
    private String branchSlug;

    /** 任务需求文档（docs 模块文档 id，可空后补） */
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
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getBranchSlug() { return branchSlug; }
    public void setBranchSlug(String branchSlug) { this.branchSlug = branchSlug; }
    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
