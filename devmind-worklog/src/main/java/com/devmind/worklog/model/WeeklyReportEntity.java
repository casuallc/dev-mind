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
 * weekly_reports 表（CAP-89 FR-01 复活，CAP-28 原结构）：周报 = 上周总结 + 下周计划两列。
 * unique(user_id, week_start)；week_start 一律为周一日期。仓文件全文 ↔ 两列的
 * 拆分/重组走 {@code ReportService.splitWeekly/assembleWeekly}（往返一致由测试钉死）。
 * status 列同 daily_reports：CAP-28 状态机遗留，实体不映射。
 */
@Entity
@Table(name = "weekly_reports",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "week_start"}))
public class WeeklyReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 周报归属周的周一 */
    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    /** 上周工作总结 */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "summary_md", length = 16_777_216)
    private String summaryMd;

    /** 下周工作计划（CAP-88 M2 前仍是自由文本汇报形态，结构化计划走 worklog_plan_items） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "next_plan_md", length = 16_777_216)
    private String nextPlanMd;

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
    public LocalDate getWeekStart() { return weekStart; }
    public void setWeekStart(LocalDate weekStart) { this.weekStart = weekStart; }
    public String getSummaryMd() { return summaryMd; }
    public void setSummaryMd(String summaryMd) { this.summaryMd = summaryMd; }
    public String getNextPlanMd() { return nextPlanMd; }
    public void setNextPlanMd(String nextPlanMd) { this.nextPlanMd = nextPlanMd; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
