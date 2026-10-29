package com.devmind.knowledge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 经验提案（CAP-04 FR-05/FR-06）：会话中「沉淀经验」/ agent 提议 → inbox 待审核 → 采纳。
 * CAP-94 起去向为采纳时显式选库（adoptedKbId）；targetScope/targetProjectId/adoptedTo/
 * adoptedProjectId 停写保留（防 rollback 丢数据），项目维度只剩 sourceProject 溯源。
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
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String contentMd;

    /** 历史列：期望去向 project | global（CAP-94 起停写） */
    @Column(length = 16)
    private String targetScope;

    /** 历史列：project 去向的目标项目（CAP-94 起停写） */
    @Column(length = 64)
    private String targetProjectId;

    /** 来源会话 ID */
    @Column(length = 64)
    private String sourceSessionId;

    /** CAP-94：来源项目（纯溯源标注） */
    @Column(length = 64)
    private String sourceProject;

    /** open | adopted | rejected */
    @Column(length = 16)
    private String status;

    /** 历史列：实际采纳去向（CAP-94 起停写，见 adoptedKbId） */
    @Column(length = 16)
    private String adoptedTo;

    /** 历史列：采纳到的项目（CAP-94 起停写，见 adoptedKbId） */
    @Column(length = 64)
    private String adoptedProjectId;

    /** CAP-94：采纳到的知识库 */
    private Long adoptedKbId;

    private Instant createdAt;
    private Instant adoptedAt;

    /**
     * CAP-55 FR-04 AI 分诊结果（{@code TriageSnapshot} 的 JSON：模型 answers/routing 原文 +
     * 当时的检索依据）。null = 从未分诊——UI 据此决定显不显示徽标。
     */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String triageJson;

    /** 最近一次分诊时间 */
    private Instant triageAt;

    /**
     * 最近一次分诊是否降级（降级 = 不出建议徽标，只留原因）。
     * 用包装类型 + 初始值：db 里存量行是 NULL，读进 primitive 会炸
     * （Boolean 列禁 @ColumnDefault，见开发注意事项），getter 兜底 false。
     */
    @Column(name = "triage_degraded")
    private Boolean triageDegraded = false;

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
    public String getSourceProject() { return sourceProject; }
    public void setSourceProject(String sourceProject) { this.sourceProject = sourceProject; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAdoptedTo() { return adoptedTo; }
    public void setAdoptedTo(String adoptedTo) { this.adoptedTo = adoptedTo; }
    public String getAdoptedProjectId() { return adoptedProjectId; }
    public void setAdoptedProjectId(String adoptedProjectId) { this.adoptedProjectId = adoptedProjectId; }
    public Long getAdoptedKbId() { return adoptedKbId; }
    public void setAdoptedKbId(Long adoptedKbId) { this.adoptedKbId = adoptedKbId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getAdoptedAt() { return adoptedAt; }
    public void setAdoptedAt(Instant adoptedAt) { this.adoptedAt = adoptedAt; }
    public String getTriageJson() { return triageJson; }
    public void setTriageJson(String triageJson) { this.triageJson = triageJson; }
    public Instant getTriageAt() { return triageAt; }
    public void setTriageAt(Instant triageAt) { this.triageAt = triageAt; }
    public boolean isTriageDegraded() { return triageDegraded != null && triageDegraded; }
    public void setTriageDegraded(Boolean triageDegraded) { this.triageDegraded = triageDegraded; }
}
