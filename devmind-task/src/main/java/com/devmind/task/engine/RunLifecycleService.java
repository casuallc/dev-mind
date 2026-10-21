package com.devmind.task.engine;

import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.task.model.AsyncTaskEntity;
import com.devmind.task.model.AsyncTaskRunEntity;
import com.devmind.task.repo.AsyncTaskRepository;
import com.devmind.task.repo.AsyncTaskRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * CAP-80 run 生命周期收口：终态流转（乐观条件防双写）+ 失败重排（FR-04）+ 轮次结算
 *（FR-07 next_run_at 推进 / max_runs / DONE）。执行器、僵尸收割、启动扫描共用这一份，
 * 不散落多处。
 *
 * <p>方法均<b>不加 @Transactional</b>（触发/流转红线）：靠 repository 的 save/@Modifying
 * 自身事务即时提交，异步执行线程立刻可见。</p>
 */
@Service
public class RunLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(RunLifecycleService.class);

    private final AsyncTaskRepository taskRepo;
    private final AsyncTaskRunRepository runRepo;
    private final ExecutionLogHub hub;

    public RunLifecycleService(AsyncTaskRepository taskRepo, AsyncTaskRunRepository runRepo,
                               ExecutionLogHub hub) {
        this.taskRepo = taskRepo;
        this.runRepo = runRepo;
        this.hub = hub;
    }

    /** 认领 run（QUEUED→RUNNING）。false=已被并发认领，调用方放弃本次执行。 */
    public boolean startRun(String runId) {
        return runRepo.start(runId, Instant.now()) > 0;
    }

    /** 成功终态：落日志 → 轮次结算 → WS done 帧 */
    public void succeedRun(String runId, String logs) {
        AsyncTaskRunEntity run = runRepo.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        Instant now = Instant.now();
        int updated = runRepo.finish(runId, AsyncTaskRunEntity.SUCCESS, now, durationMs(run, now), null, logs);
        if (updated == 0) {
            log.debug("run {} 已被并发终态（收割/取消），成功结果丢弃", runId);
            return;
        }
        concludeRound(run);
        hub.done(runId, AsyncTaskRunEntity.SUCCESS);
    }

    /**
     * 失败终态：落日志 → 可重试且 attempt 未用尽则按 FR-04 退避重排新 attempt，
     * 否则轮次结算 → WS done 帧。
     */
    public void failRun(String runId, String error, boolean retryable, String logs) {
        AsyncTaskRunEntity run = runRepo.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        Instant now = Instant.now();
        String summary = truncate(error, 2000);
        int updated = runRepo.finish(runId, AsyncTaskRunEntity.FAILED, now, durationMs(run, now), summary, logs);
        if (updated == 0) {
            log.debug("run {} 已被并发终态（收割/取消），失败结果丢弃", runId);
            return;
        }
        AsyncTaskEntity task = taskRepo.findById(run.getTaskId()).orElse(null);
        if (task != null && retryable && run.getAttempt() < task.getRetryMaxAttempts()) {
            long delay = RetryPolicy.nextDelayMs(task.getRetryBackoff(), task.getRetryIntervalMsOrDefault(),
                    run.getAttempt());
            AsyncTaskRunEntity retry = new AsyncTaskRunEntity();
            retry.setId(TaskIds.newId());
            retry.setTaskId(run.getTaskId());
            retry.setRunSeq(run.getRunSeq());
            retry.setAttempt(run.getAttempt() + 1);
            retry.setStatus(AsyncTaskRunEntity.QUEUED);
            retry.setScheduledAt(now.plusMillis(delay));
            retry.setCreatedAt(now);
            runRepo.save(retry);
            log.info("run {} 失败（attempt {}/{}），{}ms 后重试", runId, run.getAttempt(),
                    task.getRetryMaxAttempts(), delay);
        } else {
            concludeRound(run);
        }
        hub.done(runId, AsyncTaskRunEntity.FAILED);
    }

    /** 取消终态（任务 cancel 级联/在途中断后）。 */
    public void cancelRun(String runId) {
        AsyncTaskRunEntity run = runRepo.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        Instant now = Instant.now();
        int updated = runRepo.finish(runId, AsyncTaskRunEntity.CANCELLED, now, durationMs(run, now),
                "任务已取消", run.getLogs());
        if (updated > 0) {
            hub.done(runId, AsyncTaskRunEntity.CANCELLED);
        }
    }

    /**
     * 轮次结算（FR-07）：run 轮次终态（成功/重试耗尽/不可重试失败）后推进任务。
     * next_run_at 已有到期值（QUEUE 重叠留下的待补跑标记）时保留，立即补跑；
     * 否则按形态重算。CANCELLED 任务不复活。
     */
    private void concludeRound(AsyncTaskRunEntity run) {
        AsyncTaskEntity task = taskRepo.findById(run.getTaskId()).orElse(null);
        if (task == null || AsyncTaskEntity.STATUS_CANCELLED.equals(task.getStatus())) {
            return;
        }
        Instant now = Instant.now();
        boolean runsExhausted = task.getMaxRuns() != null && task.getRunCount() >= task.getMaxRuns();
        boolean oneShot = AsyncTaskEntity.KIND_IMMEDIATE.equals(task.getScheduleKind())
                || AsyncTaskEntity.KIND_DELAY.equals(task.getScheduleKind());
        if (runsExhausted || oneShot) {
            task.setStatus(AsyncTaskEntity.STATUS_DONE);
            task.setNextRunAt(null);
        } else {
            Instant pending = task.getNextRunAt();
            if (pending == null || pending.isAfter(now)) {
                task.setNextRunAt(computeNext(task, now));
            }
            // pending 已过期（QUEUE 待补跑）→ 保留原值，下一 tick 立即补跑
        }
        task.touch();
        taskRepo.save(task);
    }

    /** 按形态算下次执行时刻；QUEUE 重叠跳过轮次的顺延也走这里（基准=now）。 */
    public Instant computeNext(AsyncTaskEntity task, Instant base) {
        return switch (task.getScheduleKind()) {
            case AsyncTaskEntity.KIND_FIXED_DELAY ->
                    base.plusMillis(task.getIntervalMs() == null ? 0 : task.getIntervalMs());
            case AsyncTaskEntity.KIND_CRON -> {
                CronExpression cron = CronExpression.parse(task.getCronExpr());
                ZonedDateTime next = cron.next(ZonedDateTime.now());
                yield next == null ? null : next.toInstant();
            }
            // 一次性任务没有「下一轮」
            default -> null;
        };
    }

    private Long durationMs(AsyncTaskRunEntity run, Instant finishedAt) {
        return run.getStartedAt() == null ? null : finishedAt.toEpochMilli() - run.getStartedAt().toEpochMilli();
    }

    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
