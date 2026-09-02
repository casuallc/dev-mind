package com.devmind.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.time.LocalDate;

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

    /** 来源：LOCAL 自建 / JIRA 同步（Jira 托管字段本地只读，由同步刷新） */
    public static final String SOURCE_LOCAL = "LOCAL";
    public static final String SOURCE_JIRA = "JIRA";

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

    /** 来源：LOCAL/JIRA，默认 LOCAL；旧行 null 兜底见 getSource() */
    @Column(length = 16)
    @ColumnDefault(SOURCE_LOCAL)
    private String source = SOURCE_LOCAL;

    /** 优先级（Jira 词表 Highest/High/Medium/Low/Lowest，存字符串保持开放） */
    @Column(length = 24)
    private String priority;

    /** 经办人（Jira displayName，不映射系统用户） */
    @Column(length = 128)
    private String assignee;

    /** 报告人（Jira displayName） */
    @Column(length = 128)
    private String reporter;

    /** 标签，逗号拼接存储（Jira label 无空格逗号，无损）；View 层拆 List */
    @Column(length = 512)
    private String labels;

    /** 修复版本，逗号拼接 version name */
    @Column(name = "fix_versions", length = 256)
    private String fixVersions;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** 外部 key 冗余展示/搜索缓存（Jira issue key，如 PROJ-123）；幂等真相源仍是 external_links */
    @Column(name = "external_key", length = 256)
    private String externalKey;

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
    /** 旧行可能为 null（ddl-auto 历史数据），统一兜底 LOCAL */
    public String getSource() { return source == null ? SOURCE_LOCAL : source; }
    public void setSource(String source) { this.source = source; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getAssignee() { return assignee; }
    public void setAssignee(String assignee) { this.assignee = assignee; }
    public String getReporter() { return reporter; }
    public void setReporter(String reporter) { this.reporter = reporter; }
    public String getLabels() { return labels; }
    public void setLabels(String labels) { this.labels = labels; }
    public String getFixVersions() { return fixVersions; }
    public void setFixVersions(String fixVersions) { this.fixVersions = fixVersions; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public String getExternalKey() { return externalKey; }
    public void setExternalKey(String externalKey) { this.externalKey = externalKey; }
    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
