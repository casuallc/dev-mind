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
import java.time.LocalDate;

/**
 * daily_reports 表（CAP-89 FR-01 复活，CAP-28 原结构）：当日工作日志报告。
 * unique(user_id, work_date)：定时/手动重复触发幂等跳过（自动不覆盖 + force 显式）。
 *
 * <p>status 列是 CAP-28 DRAFT/CONFIRMED 状态机的遗留（CAP-74 删除状态机、CAP-89 确认不复活）——
 * <strong>实体不映射该列</strong>，ddl-auto 不删存量列，留存无害。</p>
 */
@Entity
@Table(name = "daily_reports",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "work_date"}))
public class DailyReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "content_md", length = 16_777_216)
    private String contentMd;

    /** 生成来源会话（回溯执行痕迹，可空） */
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
    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public String getContentMd() { return contentMd; }
    public void setContentMd(String contentMd) { this.contentMd = contentMd; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
