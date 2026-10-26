package com.devmind.worklog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * worklog_quarterly_reports 表（CAP-91 FR-08）：季度复盘报告——单 content_md 列
 * （不复用周报两列分节：复盘结构由 AI 自由组织，平台不强约束章节）。
 * unique(user_id, quarter)；quarter 格式 '2026-Q1'（服务端校验，见 WorklogQuarters）。
 */
@Entity
@Table(name = "worklog_quarterly_reports",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "quarter"}))
public class WorklogQuarterlyReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 复盘归属季度（'2026-Q1'） */
    @Column(nullable = false, length = 8)
    private String quarter;

    /** 复盘正文（@Lob 必带 @JdbcTypeCode + length：裸 @Lob 在 PG 落 oid / MySQL 落 tinytext） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "content_md", length = 16_777_216)
    private String contentMd;

    @Column(name = "session_id", length = 16)
    private String sessionId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getQuarter() { return quarter; }
    public void setQuarter(String quarter) { this.quarter = quarter; }
    public String getContentMd() { return contentMd; }
    public void setContentMd(String contentMd) { this.contentMd = contentMd; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
