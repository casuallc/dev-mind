package com.devmind.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * work_items 表（CAP-13 研发主线）：工作单元，可派发给 agent/人执行的最小单位。
 * type 五类：DESIGN / DEVELOPMENT / TEST / DOCUMENT / REVIEW；spec 为执行输入
 * （起 Session 时作为 taskSpec 注入）。seq 项目内自增（展示 WI-&lt;seq&gt;），
 * (project_id, seq) 唯一；DEVELOPMENT 型分支约定 wi/&lt;seq&gt;-&lt;slug&gt;。
 */
@Entity
@Table(name = "work_items", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "seq"}))
public class WorkItemEntity {

    public static final String TYPE_DESIGN = "DESIGN";
    public static final String TYPE_DEVELOPMENT = "DEVELOPMENT";
    public static final String TYPE_TEST = "TEST";
    public static final String TYPE_DOCUMENT = "DOCUMENT";
    public static final String TYPE_REVIEW = "REVIEW";

    public static final String STATUS_TODO = "TODO";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_BLOCKED = "BLOCKED";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    @Column(name = "requirement_id", nullable = false, length = 32)
    private String requirementId;

    /** 依据的解决方案（可空，复杂需求拆分时挂） */
    @Column(name = "design_id", length = 32)
    private String designId;

    /** 项目内自增序号（展示 WI-<seq>） */
    @Column(nullable = false)
    private Long seq;

    /** DESIGN / DEVELOPMENT / TEST / DOCUMENT / REVIEW */
    @Column(nullable = false, length = 24)
    private String type;

    @Column(nullable = false, length = 256)
    private String title;

    /** 执行输入：起 Session 时作为 taskSpec 注入（拆分时由 AI 生成、人可编辑） */
    @Lob
    @Column(name = "spec", length = 16_777_216)
    private String spec;

    @Column(length = 24)
    private String status = STATUS_TODO;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    /** 工作分支 slug（分支 wi/<seq>-<slug>，DEVELOPMENT 型使用） */
    @Column(name = "branch_slug", length = 64)
    private String branchSlug;

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
    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public String getDesignId() { return designId; }
    public void setDesignId(String designId) { this.designId = designId; }
    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getBranchSlug() { return branchSlug; }
    public void setBranchSlug(String branchSlug) { this.branchSlug = branchSlug; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
