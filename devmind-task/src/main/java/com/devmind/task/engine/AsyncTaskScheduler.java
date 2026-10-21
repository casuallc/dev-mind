package com.devmind.task.engine;

import com.devmind.task.config.AsyncTaskProperties;
import com.devmind.task.model.AsyncTaskEntity;
import com.devmind.task.model.AsyncTaskRunEntity;
import com.devmind.task.repo.AsyncTaskRepository;
import com.devmind.task.repo.AsyncTaskRunRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * CAP-80 M2 调度内核：主 tick（任务认领 + run 派发）+ 僵尸收割 + 启动扫描。
 *
 * <p>tick 两阶段：① 扫描到期任务（ACTIVE 且 next_run_at&lt;=now），先做 FR-08 重叠判定，
 * 再乐观认领（置 next_run_at=NULL）并建首轮 run；② 扫描到期 QUEUED run（含重试排队）派发给
 * 执行器——重试的到期调度因此走 run 表而非任务表，任务表 next_run_at 只管「新一轮」。</p>
 *
 * <p>方法均<b>不加 @Transactional</b>（调度红线），靠 repository 自身事务即时提交。</p>
 */
@Service
public class AsyncTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskScheduler.class);

    private static final List<String> OPEN_STATUSES =
            List.of(AsyncTaskRunEntity.QUEUED, AsyncTaskRunEntity.RUNNING);

    private final AsyncTaskRepository taskRepo;
    private final AsyncTaskRunRepository runRepo;
    private final AsyncTaskExecutor executor;
    private final RunLifecycleService lifecycle;
    private final AsyncTaskProperties props;

    public AsyncTaskScheduler(AsyncTaskRepository taskRepo, AsyncTaskRunRepository runRepo,
                              AsyncTaskExecutor executor, RunLifecycleService lifecycle,
                              AsyncTaskProperties props) {
        this.taskRepo = taskRepo;
        this.runRepo = runRepo;
        this.executor = executor;
        this.lifecycle = lifecycle;
        this.props = props;
    }

    /**
     * FR-09 启动扫描：重启后所有 RUNNING 按定义皆僵尸（线程随 JVM 消亡），
     * 立即判 FAILED 并按 FR-04 重排，不等 timeout×2（消除最长 1h 恢复盲窗）。
     */
    @PostConstruct
    public void startupSweep() {
        List<AsyncTaskRunEntity> running = runRepo.findByStatus(AsyncTaskRunEntity.RUNNING);
        for (AsyncTaskRunEntity run : running) {
            log.warn("启动扫描收割在途 run {}（服务重启，执行线程已消亡）", run.getId());
            lifecycle.failRun(run.getId(), "服务重启，在途执行随 JVM 消亡", true, run.getLogs());
        }
        if (!running.isEmpty()) {
            log.info("启动扫描收割 {} 个在途 run", running.size());
        }
    }

    /** 主 tick：任务认领建 run → run 派发。fixedDelay 单线程，tick 间无并发。 */
    @Scheduled(fixedDelayString = "${devmind.task.tick-delay-ms:5000}",
            initialDelayString = "${devmind.task.tick-initial-delay-ms:3000}")
    public void tick() {
        try {
            claimDueTasks();
            dispatchDueRuns();
        } catch (Exception e) {
            log.warn("异步任务 tick 异常: {}", e.getMessage(), e);
        }
    }

    private void claimDueTasks() {
        Instant now = Instant.now();
        List<AsyncTaskEntity> due = taskRepo.findByStatusAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
                AsyncTaskEntity.STATUS_ACTIVE, now, PageRequest.of(0, props.getTickMaxClaim()));
        for (AsyncTaskEntity task : due) {
            try {
                claimOne(task, now);
            } catch (Exception e) {
                log.warn("认领任务 {} 失败: {}", task.getId(), e.getMessage(), e);
            }
        }
    }

    private void claimOne(AsyncTaskEntity task, Instant now) {
        // FR-08 重叠判定先于认领：存在未终态 run 时按策略 SKIP/QUEUE
        if (runRepo.existsByTaskIdAndStatusIn(task.getId(), OPEN_STATUSES)) {
            if (AsyncTaskEntity.OVERLAP_QUEUE.equals(task.getOverlapPolicy())) {
                return; // QUEUE：什么都不动，上一 run 终态后自然补跑
            }
            // SKIP：本轮作废，推进到下一轮（一次性任务无下一轮 → 置空）
            task.setNextRunAt(lifecycle.computeNext(task, now));
            task.touch();
            taskRepo.save(task);
            return;
        }
        if (taskRepo.claim(task.getId(), now) == 0) {
            return; // 已被并发认领/已暂停
        }
        AsyncTaskEntity claimed = taskRepo.findById(task.getId()).orElse(null);
        if (claimed == null) {
            return;
        }
        if (claimed.getMaxRuns() != null && claimed.getRunCount() >= claimed.getMaxRuns()) {
            claimed.setStatus(AsyncTaskEntity.STATUS_DONE);
            claimed.touch();
            taskRepo.save(claimed);
            return;
        }
        AsyncTaskRunEntity run = new AsyncTaskRunEntity();
        run.setId(TaskIds.newId());
        run.setTaskId(claimed.getId());
        run.setRunSeq(claimed.getRunCount() + 1);
        run.setAttempt(1);
        run.setStatus(AsyncTaskRunEntity.QUEUED);
        run.setScheduledAt(now);
        run.setCreatedAt(now);
        runRepo.save(run);
        claimed.setRunCount(claimed.getRunCount() + 1);
        claimed.touch();
        taskRepo.save(claimed);
        executor.launch(run.getId());
    }

    private void dispatchDueRuns() {
        Instant now = Instant.now();
        List<AsyncTaskRunEntity> due = runRepo.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                AsyncTaskRunEntity.QUEUED, now, PageRequest.of(0, props.getTickMaxClaim()));
        for (AsyncTaskRunEntity run : due) {
            executor.launch(run.getId());
        }
    }

    /** FR-09 僵尸收割：RUNNING 超 min(timeout_ms×2, zombieCapMs) 判 FAILED，按重试策略重排。 */
    @Scheduled(fixedDelayString = "${devmind.task.stale-reaper-interval-ms:60000}",
            initialDelayString = "${devmind.task.stale-reaper-initial-delay-ms:60000}")
    public void reapStaleRuns() {
        try {
            Instant now = Instant.now();
            for (AsyncTaskRunEntity run : runRepo.findByStatus(AsyncTaskRunEntity.RUNNING)) {
                AsyncTaskEntity task = taskRepo.findById(run.getTaskId()).orElse(null);
                long threshold = task == null ? props.getZombieThresholdCapMs()
                        : Math.min(task.getTimeoutMsOrDefault() * 2, props.getZombieThresholdCapMs());
                if (run.getStartedAt() != null
                        && run.getStartedAt().plusMillis(threshold).isBefore(now)) {
                    log.warn("僵尸收割 run {}（RUNNING 超 {}ms 无终态）", run.getId(), threshold);
                    lifecycle.failRun(run.getId(), "僵尸收割：RUNNING 超 " + threshold + "ms 无终态",
                            true, run.getLogs());
                }
            }
        } catch (Exception e) {
            log.warn("僵尸收割异常: {}", e.getMessage(), e);
        }
    }
}
