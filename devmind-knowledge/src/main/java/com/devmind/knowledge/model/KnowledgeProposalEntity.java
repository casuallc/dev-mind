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
 * 经验提案（CAP-04 FR-05/FR-06）：会话中「沉淀经验」/ agent 提议 → inbox 待审核 → 采纳到项目/晋升全局。
 */
@Entity
@Table(name = "knowledge_proposals")
public class KnowledgeProposalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 200)
    private String title;

    @Lob
    @Column(length = 16_777_216)
    private String contentMd;

    /** 期望去向：project | global */
    @Column(length = 16)
    private String targetScope;

    /** project 去向的目标项目（可为空，采纳时再定） */
    @Column(length = 64)
    private String targetProjectId;

    /** 来源会话 ID */
    @Column(length = 64)
    private String sourceSessionId;

    /** open | adopted | rejected */
    @Column(length = 16)
    private String status;

    /** 实际采纳去向 */
    @Column(length = 16)
    private String adoptedTo;

    @Column(length = 64)
    private String adoptedProjectId;

    private Instant createdAt;
    private Instant adoptedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContentMd() { return contentMd; }
    public void setContentMd(String contentMd) { this.contentMd = contentMd; }
    public String getTargetScope() { return targetScope; }
    public void setTargetScope(String targetScope) { this.targetScope = targetScope; }
    public String getTargetProjectId() { return targetProjectId; }
    public void setTargetProjectId(String targetProjectId) { this.targetProjectId = targetProjectId; }
    public String getSourceSessionId() { return sourceSessionId; }
    public void setSourceSessionId(String sourceSessionId) { this.sourceSessionId = sourceSessionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAdoptedTo() { return adoptedTo; }
    public void setAdoptedTo(String adoptedTo) { this.adoptedTo = adoptedTo; }
    public String getAdoptedProjectId() { return adoptedProjectId; }
    public void setAdoptedProjectId(String adoptedProjectId) { this.adoptedProjectId = adoptedProjectId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getAdoptedAt() { return adoptedAt; }
    public void setAdoptedAt(Instant adoptedAt) { this.adoptedAt = adoptedAt; }
}
