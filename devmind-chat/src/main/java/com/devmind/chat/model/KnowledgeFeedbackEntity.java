package com.devmind.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * CAP-84 FR-25 消息级答案反馈。每个用户对同一会话同一轮只能有一条（重复提交覆盖原因）。
 * 引用集合拆到 {@link KnowledgeFeedbackRefEntity}，便于按文档聚合负面反馈。
 */
@Entity
@Table(name = "knowledge_feedback",
        uniqueConstraints = @UniqueConstraint(columnNames = {"chat_id", "turn_seq", "created_by"}),
        indexes = @Index(name = "idx_knowledge_feedback_created", columnList = "created_at"))
public class KnowledgeFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", length = 32, nullable = false)
    private String chatId;

    @Column(name = "turn_seq", nullable = false)
    private Long turnSeq;

    @Column(nullable = false)
    private boolean useful;

    @Column(length = 1000)
    private String reason;

    /** 反馈时的会话知识模式（ANSWER/SOLUTION）；历史非知识会话为 null */
    @Column(length = 16)
    private String mode;

    @Column(name = "created_by", length = 64, nullable = false)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public Long getTurnSeq() { return turnSeq; }
    public void setTurnSeq(Long turnSeq) { this.turnSeq = turnSeq; }
    public boolean isUseful() { return useful; }
    public void setUseful(boolean useful) { this.useful = useful; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
