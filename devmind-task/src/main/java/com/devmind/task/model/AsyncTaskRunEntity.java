package com.devmind.task.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * CAP-80 async_task_runs：每次执行一行（含每次重试 attempt，同 run_seq 递进）。
 *
 * <p>终态流转一律带 {@code status IN ('QUEUED','RUNNING')} 乐观条件（FR-03）：
 * 收割器与不理会中断的 handler 双写同一 run 时后到者丢弃，防终态翻转。
 * CANCELLED 的唯一生产者是任务 cancel 级联（FR-10）。</p>
 */
@Entity
@Table(name = "async_task_runs", indexes = {
        @Index(name = "idx_async_runs_task", columnList = "task_id,run_seq,attempt"),
        @Index(name = "idx_async_runs_due", columnList = "status,scheduled_at")
})
public class AsyncTaskRunEntity {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "task_id", length = 32, nullable = false)
    private String taskId;

    /** 第几轮执行（1 起，max_runs 口径） */
    @Column(name = "run_seq")
    private int runSeq;

    /** 该轮第几次尝试（1 起，重试递增） */
    private int attempt;

    @Column(length = 16, nullable = false)
    private String status = QUEUED;

    /** 计划执行时刻（重试=退避后的时刻；首轮=认领时刻） */
    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** 失败摘要（堆栈首行 + 关键帧） */
    @Column(length = 2000)
    private String error;

    /** 完整执行日志（TaskContext.log 攒底，终态落库） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String logs;

    @Column(name = "created_at")
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public int getRunSeq() { return runSeq; }
    public void setRunSeq(int runSeq) { this.runSeq = runSeq; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getLogs() { return logs; }
    public void setLogs(String logs) { this.logs = logs; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public boolean isTerminal() {
        return SUCCESS.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }
}
