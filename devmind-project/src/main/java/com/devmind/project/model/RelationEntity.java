package com.devmind.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * relations 表（CAP-13）：通用横向关系边，把整个研发过程串成可追溯的网。
 * 归属关系用外键（主干层级），本表只存稀疏横联：
 * depends_on（work_item→work_item）、implements（work_item→design）、
 * verifies（test/review work_item→development work_item）、fixes（返工项→未通过项）、
 * produced_by（artifact→session）。类型可扩展，不为新关系加表。
 */
@Entity
@Table(name = "relations")
public class RelationEntity {

    public static final String TYPE_DEPENDS_ON = "depends_on";
    public static final String TYPE_IMPLEMENTS = "implements";
    public static final String TYPE_VERIFIES = "verifies";
    public static final String TYPE_FIXES = "fixes";
    public static final String TYPE_PRODUCED_BY = "produced_by";

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    /** 端点类型：requirement / design / work_item / artifact / session / doc … */
    @Column(name = "from_type", nullable = false, length = 24)
    private String fromType;

    @Column(name = "from_id", nullable = false, length = 32)
    private String fromId;

    @Column(name = "to_type", nullable = false, length = 24)
    private String toType;

    @Column(name = "to_id", nullable = false, length = 32)
    private String toId;

    @Column(name = "relation_type", nullable = false, length = 32)
    private String relationType;

    @Column(name = "created_at")
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getFromType() { return fromType; }
    public void setFromType(String fromType) { this.fromType = fromType; }
    public String getFromId() { return fromId; }
    public void setFromId(String fromId) { this.fromId = fromId; }
    public String getToType() { return toType; }
    public void setToType(String toType) { this.toType = toType; }
    public String getToId() { return toId; }
    public void setToId(String toId) { this.toId = toId; }
    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
