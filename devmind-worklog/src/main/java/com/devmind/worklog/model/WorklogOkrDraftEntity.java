package com.devmind.worklog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * worklog_okr_drafts 表（CAP-93 FR-03）：AI 生成 OKR 的草稿——产物原文 + 解析树 JSON，
 * 人工在草稿抽屉勾选/编辑后「采纳」才批量建目标（草稿确认落库，不进 goals 表直接写）。
 *
 * <p>每人每周期（user_id+period）一份草稿：force 重生成覆盖内容与解析树、状态回 DRAFT。
 * 状态机：DRAFT → ADOPTED（采纳）/ DISCARDED（丢弃）；ADOPTED 后再 force 重生成回 DRAFT
 * （允许推翻重来——采纳不回滚已建目标，重新采纳另建一批，同建议计划「只追加」口径）。</p>
 */
@Entity
@Table(name = "worklog_okr_drafts",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "period"}))
public class WorklogOkrDraftEntity {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ADOPTED = "ADOPTED";
    public static final String STATUS_DISCARDED = "DISCARDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户（users.username，弱关联；按人隔离，他人 404） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** YEAR / QUARTER（生成时锁定，采纳时原样进 goals） */
    @Column(nullable = false, length = 8)
    private String scope;

    /** YEAR='2026'；QUARTER='2026-Q1' */
    @Column(nullable = false, length = 8)
    private String period;

    /** 产物原文（追溯/排查用；@Lob 必带 @JdbcTypeCode + length） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "content_md", length = 16_777_216)
    private String contentMd;

    /** 解析树 JSON：List&lt;SuggestedObjective&gt;（@Lob 必带 @JdbcTypeCode + length） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "items_json", length = 16_777_216)
    private String itemsJson;

    /** DRAFT/ADOPTED/DISCARDED（@ColumnDefault 字符串必带引号） */
    @Column(nullable = false, length = 16)
    @ColumnDefault("'DRAFT'")
    private String status = STATUS_DRAFT;

    /** 生成会话 id（追溯） */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getContentMd() { return contentMd; }
    public void setContentMd(String contentMd) { this.contentMd = contentMd; }
    public String getItemsJson() { return itemsJson; }
    public void setItemsJson(String itemsJson) { this.itemsJson = itemsJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
