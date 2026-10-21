package com.devmind.task.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * CAP-80 async_tasks：任务登记与调度配置一体表。
 *
 * <p>关键不变量（FR-06）：<b>RUNNING 期间 nextRunAt 恒为 NULL</b>——乐观认领即置 NULL
 * （tick 不会重复扫到在跑任务），run 轮次终态时按调度形态重算回填；DONE/CANCELLED 保持 NULL。</p>
 *
 * <p>数值默认值走字段初始值 + getter 兜底（不走 @ColumnDefault，对齐既有坑口径）。</p>
 */
@Entity
@Table(name = "async_tasks",
        uniqueConstraints = @UniqueConstraint(name = "uk_async_tasks_dedup", columnNames = "dedup_key"),
        indexes = @Index(name = "idx_async_tasks_due", columnList = "status,next_run_at"))
public class AsyncTaskEntity {

    // ---- schedule_kind ----
    public static final String KIND_IMMEDIATE = "IMMEDIATE";
    public static final String KIND_DELAY = "DELAY";
    public static final String KIND_FIXED_DELAY = "FIXED_DELAY";
    public static final String KIND_CRON = "CRON";

    // ---- status ----
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_CANCELLED = "CANCELLED";

    // ---- retry_backoff ----
    public static final String BACKOFF_FIXED = "FIXED";
    public static final String BACKOFF_EXPONENTIAL = "EXPONENTIAL";

    // ---- overlap_policy ----
    public static final String OVERLAP_SKIP = "SKIP";
    public static final String OVERLAP_QUEUE = "QUEUE";

    public static final long DEFAULT_RETRY_INTERVAL_MS = 60_000L;
    public static final long DEFAULT_TIMEOUT_MS = 30 * 60_000L;

    @Id
    @Column(length = 32)
    private String id;

    /** handler type，分发键 */
    @Column(name = "task_type", length = 64, nullable = false)
    private String taskType;

    @Column(length = 200)
    private String name;

    /** 创建时固化的 JSON 快照，后续业务配置变更不影响在途任务 */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String payload;

    @Column(name = "schedule_kind", length = 16, nullable = false)
    private String scheduleKind;

    /** DELAY 指定时刻（其余形态 null） */
    @Column(name = "run_at")
    private Instant runAt;

    /** FIXED_DELAY 间隔 */
    @Column(name = "interval_ms")
    private Long intervalMs;

    /** CRON 表达式（Spring CronExpression 六段式） */
    @Column(name = "cron_expr", length = 128)
    private String cronExpr;

    /** 执行次数上限（null=不限） */
    @Column(name = "max_runs")
    private Integer maxRuns;

    @Column(name = "run_count")
    private int runCount = 0;

    /** 每轮最大尝试次数（含首次），默认 1=不重试 */
    @Column(name = "retry_max_attempts")
    private int retryMaxAttempts = 1;

    @Column(name = "retry_backoff", length = 16)
    private String retryBackoff = BACKOFF_FIXED;

    @Column(name = "retry_interval_ms")
    private Long retryIntervalMs;

    @Column(name = "timeout_ms")
    private Long timeoutMs;

    @Column(name = "overlap_policy", length = 8)
    private String overlapPolicy = OVERLAP_SKIP;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(length = 16, nullable = false)
    private String status = STATUS_ACTIVE;

    /** 任务登记人（非 admin 列表/详情的属主过滤口径） */
    @Column(length = 64)
    private String actor;

    /** 可空，归属过滤用（v1 仅展示过滤，见 CAP-80 O1） */
    @Column(name = "project_id", length = 64)
    private String projectId;

    /** 幂等登记键（ensureScheduled upsert），可空；唯一约束防空登记冲突 */
    @Column(name = "dedup_key", length = 128)
    private String dedupKey;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getScheduleKind() { return scheduleKind; }
    public void setScheduleKind(String scheduleKind) { this.scheduleKind = scheduleKind; }
    public Instant getRunAt() { return runAt; }
    public void setRunAt(Instant runAt) { this.runAt = runAt; }
    public Long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(Long intervalMs) { this.intervalMs = intervalMs; }
    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }
    public Integer getMaxRuns() { return maxRuns; }
    public void setMaxRuns(Integer maxRuns) { this.maxRuns = maxRuns; }
    public int getRunCount() { return runCount; }
    public void setRunCount(int runCount) { this.runCount = runCount; }
    public int getRetryMaxAttempts() { return retryMaxAttempts <= 0 ? 1 : retryMaxAttempts; }
    public void setRetryMaxAttempts(int retryMaxAttempts) { this.retryMaxAttempts = retryMaxAttempts; }
    public String getRetryBackoff() { return retryBackoff == null ? BACKOFF_FIXED : retryBackoff; }
    public void setRetryBackoff(String retryBackoff) { this.retryBackoff = retryBackoff; }
    public long getRetryIntervalMsOrDefault() {
        return retryIntervalMs == null || retryIntervalMs <= 0 ? DEFAULT_RETRY_INTERVAL_MS : retryIntervalMs;
    }
    public Long getRetryIntervalMs() { return retryIntervalMs; }
    public void setRetryIntervalMs(Long retryIntervalMs) { this.retryIntervalMs = retryIntervalMs; }
    public long getTimeoutMsOrDefault() {
        return timeoutMs == null || timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
    }
    public Long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Long timeoutMs) { this.timeoutMs = timeoutMs; }
    public String getOverlapPolicy() { return overlapPolicy == null ? OVERLAP_SKIP : overlapPolicy; }
    public void setOverlapPolicy(String overlapPolicy) { this.overlapPolicy = overlapPolicy; }
    public Instant getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(Instant nextRunAt) { this.nextRunAt = nextRunAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
