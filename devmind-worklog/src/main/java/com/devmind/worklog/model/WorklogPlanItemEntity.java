package com.devmind.worklog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/**
 * worklog_plan_items 表（CAP-88 FR-01）：计划项一等实体——日/周双层计划，有状态、可关联、可滚转。
 * scope=DAY 时 plan_date=当天日期；scope=WEEK 时 plan_date=该周周一（ISO，服务端归一）。
 * 预估工时内部以分钟存储（0.25h=15，同条目口径），API 暴露 hours。
 *
 * <p>状态机（FR-03）：TODO/DOING/DONE/DROPPED/CARRIED——自动只到 DOING（条目关联触发），
 * DONE/DROPPED 必须人工，CARRIED 仅由滚转写入（人工不可设）。</p>
 *
 * <p>carried_from_id 自引用成链：「一项拖了三周」可回溯；删计划项时条目 plan_item_id 置空
 * （DB 世界直接清引用，无悬空 id）。</p>
 */
@Entity
@Table(name = "worklog_plan_items")
public class WorklogPlanItemEntity {

    public static final String SCOPE_DAY = "DAY";
    public static final String SCOPE_WEEK = "WEEK";

    public static final String STATUS_TODO = "TODO";
    public static final String STATUS_DOING = "DOING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_DROPPED = "DROPPED";
    public static final String STATUS_CARRIED = "CARRIED";

    public static final String SOURCE_MANUAL = "MANUAL";
    /** M3：AI 建议计划落库的草稿行 */
    public static final String SOURCE_AI_SUGGEST = "AI_SUGGEST";

    /** 平台惯例 Long 自增（条目 String uuid 是 CAP-89 载明的有意例外，不扩散） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户（users.username，弱关联不设外键；按人隔离，他人 404） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** DAY / WEEK */
    @Column(nullable = false, length = 8)
    private String scope;

    /** DAY=当天日期；WEEK=week_start（周一，服务端归一） */
    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    @Column(nullable = false, length = 256)
    private String title;

    /** 详情（@Lob 必带 @JdbcTypeCode + length：裸 @Lob 在 PG 落 oid / MySQL 落 tinytext） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String content;

    /** 与条目同枚举：DEV/SUPPORT/MEETING/RESEARCH/OTHER */
    @Column(name = "plan_type", nullable = false, length = 32)
    @ColumnDefault("'DEV'")
    private String planType = "DEV";

    /** 预估工时（分钟；可空=未预估） */
    @Column(name = "estimate_minutes")
    private Integer estimateMinutes;

    /** TODO/DOING/DONE/DROPPED/CARRIED（@ColumnDefault 字符串必带引号） */
    @Column(nullable = false, length = 16)
    @ColumnDefault("'TODO'")
    private String status = STATUS_TODO;

    /** MANUAL / AI_SUGGEST（M3） */
    @Column(nullable = false, length = 16)
    @ColumnDefault("'MANUAL'")
    private String source = SOURCE_MANUAL;

    /** 可选关联：研发主线需求（沿用条目口径） */
    @Column(name = "requirement_id", length = 64)
    private String requirementId;

    /** 可选关联：Jira issue key */
    @Column(name = "jira_issue_key", length = 64)
    private String jiraIssueKey;

    /** 可选关联：季度 KR（CAP-91 FR-05，worklog_goals 的 KEY_RESULT；删 KR 时置空） */
    @Column(name = "kr_id")
    private Long krId;

    /** 滚转来源项 id（自引用，拖延链可追溯；首版无折叠展示） */
    @Column(name = "carried_from_id")
    private Long carriedFromId;

    /** 展示排序（M1 无拖拽排序，默认 0，同值按 id 升序=录入序） */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

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
    public LocalDate getPlanDate() { return planDate; }
    public void setPlanDate(LocalDate planDate) { this.planDate = planDate; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getPlanType() { return planType; }
    public void setPlanType(String planType) { this.planType = planType; }
    public Integer getEstimateMinutes() { return estimateMinutes; }
    public void setEstimateMinutes(Integer estimateMinutes) { this.estimateMinutes = estimateMinutes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public String getJiraIssueKey() { return jiraIssueKey; }
    public void setJiraIssueKey(String jiraIssueKey) { this.jiraIssueKey = jiraIssueKey; }
    public Long getKrId() { return krId; }
    public void setKrId(Long krId) { this.krId = krId; }
    public Long getCarriedFromId() { return carriedFromId; }
    public void setCarriedFromId(Long carriedFromId) { this.carriedFromId = carriedFromId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
