package com.devmind.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * CAP-84 FR-25 反馈引用快照：一条反馈命中的文档/块。文档改名或删除后仍可回溯当时证据。
 */
@Entity
@Table(name = "knowledge_feedback_ref",
        indexes = {@Index(name = "idx_knowledge_feedback_ref_fb", columnList = "feedback_id"),
                @Index(name = "idx_knowledge_feedback_ref_entry", columnList = "entry_id")})
public class KnowledgeFeedbackRefEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "feedback_id", nullable = false)
    private Long feedbackId;

    @Column(name = "entry_id")
    private Long entryId;

    @Column(name = "kb_id")
    private Long kbId;

    @Column(name = "chunk_id")
    private Long chunkId;

    @Column(name = "citation_no")
    private Integer citationNo;

    @Column(name = "entry_name", length = 255)
    private String entryName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFeedbackId() { return feedbackId; }
    public void setFeedbackId(Long feedbackId) { this.feedbackId = feedbackId; }
    public Long getEntryId() { return entryId; }
    public void setEntryId(Long entryId) { this.entryId = entryId; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public Long getChunkId() { return chunkId; }
    public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
    public Integer getCitationNo() { return citationNo; }
    public void setCitationNo(Integer citationNo) { this.citationNo = citationNo; }
    public String getEntryName() { return entryName; }
    public void setEntryName(String entryName) { this.entryName = entryName; }
}
