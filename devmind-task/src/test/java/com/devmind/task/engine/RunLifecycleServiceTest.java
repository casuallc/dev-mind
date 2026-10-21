package com.devmind.task.engine;

import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.task.model.AsyncTaskEntity;
import com.devmind.task.model.AsyncTaskRunEntity;
import com.devmind.task.repo.AsyncTaskRepository;
import com.devmind.task.repo.AsyncTaskRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-80 run 生命周期收口：终态流转防双写、失败重排、轮次结算（FR-04/07）的核心分支。
 * mock 仓储 + 内存 Map 往返（仓内无 JPA 切片基建，见 AsyncTaskServiceTest 说明）。
 */
class RunLifecycleServiceTest {

    private final AsyncTaskRepository taskRepo = mock(AsyncTaskRepository.class);
    private final AsyncTaskRunRepository runRepo = mock(AsyncTaskRunRepository.class);
    private final ExecutionLogHub hub = mock(ExecutionLogHub.class);
    private final Map<String, AsyncTaskEntity> tasks = new LinkedHashMap<>();
    private final Map<String, AsyncTaskRunEntity> runs = new LinkedHashMap<>();

    private RunLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        tasks.clear();
        runs.clear();
        when(taskRepo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(tasks.get(inv.getArgument(0))));
        when(taskRepo.save(any(AsyncTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(runRepo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(runs.get(inv.getArgument(0))));
        when(runRepo.save(any(AsyncTaskRunEntity.class))).thenAnswer(inv -> {
            AsyncTaskRunEntity r = inv.getArgument(0);
            runs.put(r.getId(), r);
            return r;
        });
        // 乐观终态流转默认成功（1 行）；双写场景在各自用例里覆盖为 0
        when(runRepo.finish(anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);
        lifecycle = new RunLifecycleService(taskRepo, runRepo, hub);
    }

    private AsyncTaskEntity task(String kind) {
        AsyncTaskEntity t = new AsyncTaskEntity();
        t.setId(TaskIds.newId());
        t.setTaskType("echo");
        t.setScheduleKind(kind);
        t.setStatus(AsyncTaskEntity.STATUS_ACTIVE);
        t.setIntervalMs(60_000L);
        tasks.put(t.getId(), t);
        return t;
    }

    private AsyncTaskRunEntity running(AsyncTaskEntity t, int runSeq, int attempt) {
        AsyncTaskRunEntity r = new AsyncTaskRunEntity();
        r.setId(TaskIds.newId());
        r.setTaskId(t.getId());
        r.setRunSeq(runSeq);
        r.setAttempt(attempt);
        r.setStatus(AsyncTaskRunEntity.RUNNING);
        r.setStartedAt(Instant.now());
        runs.put(r.getId(), r);
        return r;
    }

    @Test
    void succeedOnOneShotMarksTaskDone() {
        AsyncTaskEntity t = task(AsyncTaskEntity.KIND_IMMEDIATE);
        AsyncTaskRunEntity r = running(t, 1, 1);
        lifecycle.succeedRun(r.getId(), "logs");
        assertEquals(AsyncTaskEntity.STATUS_DONE, t.getStatus());
        assertNull(t.getNextRunAt());
        verify(hub).done(r.getId(), AsyncTaskRunEntity.SUCCESS);
    }

    @Test
    void succeedOnFixedDelayAdvancesNextRunFromNow() {
        AsyncTaskEntity t = task(AsyncTaskEntity.KIND_FIXED_DELAY);
        AsyncTaskRunEntity r = running(t, 1, 1);
        Instant before = Instant.now();
        lifecycle.succeedRun(r.getId(), null);
        assertEquals(AsyncTaskEntity.STATUS_ACTIVE, t.getStatus());
        assertNotNull(t.getNextRunAt());
        assertTrue(t.getNextRunAt().isAfter(before.plusMillis(59_000L)),
                "FIXED_DELAY：下次 = 本轮终态时刻 + interval");
    }

    @Test
    void maxRunsExhaustedMarksDone() {
        AsyncTaskEntity t = task(AsyncTaskEntity.KIND_FIXED_DELAY);
        t.setMaxRuns(3);
        t.setRunCount(3);
        lifecycle.succeedRun(running(t, 3, 1).getId(), null);
        assertEquals(AsyncTaskEntity.STATUS_DONE, t.getStatus());
        assertNull(t.getNextRunAt());
    }

    @Test
    void queueOverlapPendingTriggerIsPreserved() {
        // QUEUE 重叠策略：本轮期间 next_run_at 到期未动（待补跑标记），结算时保留立即补跑
        AsyncTaskEntity t = task(AsyncTaskEntity.KIND_FIXED_DELAY);
        Instant overdue = Instant.now().minusSeconds(30);
        t.setNextRunAt(overdue);
        lifecycle.succeedRun(running(t, 1, 1).getId(), null);
        assertEquals(overdue, t.getNextRunAt(), "过期的待补跑标记不得被重算覆盖");
        assertEquals(AsyncTaskEntity.STATUS_ACTIVE, t.getStatus());
    }

    @Test
    void cancelledTaskIsNotResurrected() {
        AsyncTaskEntity t = task(AsyncTaskEntity.KIND_FIXED_DELAY);
        t.setStatus(AsyncTaskEntity.STATUS_CANCELLED);
        lifecycle.succeedRun(running(t, 1, 1).getId(), null);
        assertEquals(AsyncTaskEntity.STATUS_CANCELLED, t.getStatus());
        assertNull(t.getNextRunAt());
    }

    @Test
    void retryableFailureQueuesNextAttemptWithBackoff() {
        AsyncTaskEntity t = task(AsyncTaskEntity.KIND_IMMEDIATE);
        t.setRetryMaxAttempts(3);
        t.setRetryIntervalMs(5_000L);
        AsyncTaskRunEntity r = running(t, 1, 1);
        Instant before = Instant.now();
        lifecycle.failRun(r.getId(), "boom", true, "logs");

        AsyncTaskRunEntity retry = runs.values().stream()
                .filter(x -> x.getAttempt() == 2).findFirst().orElseThrow();
        assertEquals(AsyncTaskRunEntity.QUEUED, retry.getStatus());
        assertEquals(1, retry.getRunSeq(), "重试属同一轮次（runSeq 不变）");
        assertTrue(!retry.getScheduledAt().isBefore(before.plusMillis(5_000L)),
                "重试按 FIXED 退避 5s 后排队");
        assertEquals(AsyncTaskEntity.STATUS_ACTIVE, t.getStatus(), "尚有重试在途，轮次未结算");
        verify(hub).done(r.getId(), AsyncTaskRunEntity.FAILED);
    }

    @Test
    void exhaustedAttemptsConcludeRound() {
        AsyncTaskEntity t = task(AsyncTaskEntity.KIND_IMMEDIATE);
        t.setRetryMaxAttempts(2);
        lifecycle.failRun(running(t, 1, 2).getId(), "boom", true, null);
        assertEquals(AsyncTaskEntity.STATUS_DONE, t.getStatus(), "最后一次尝试失败 → 轮次结算");
        assertEquals(1, runs.size(), "不再重排新 run");
    }

    @Test
    void nonRetryableFailureConcludesImmediately() {
        AsyncTaskEntity t = task(AsyncTaskEntity.KIND_IMMEDIATE);
        t.setRetryMaxAttempts(5);
        lifecycle.failRun(running(t, 1, 1).getId(), "fatal", false, null);
        assertEquals(AsyncTaskEntity.STATUS_DONE, t.getStatus());
        assertEquals(1, runs.size());
    }

    @Test
    void guardedFinishLosingRaceDropsResult() {
        // 收割器已先把 run 判死：finish 影响 0 行 → 迟到结果丢弃，不重排、不结算
        when(runRepo.finish(anyString(), anyString(), any(), any(), any(), any())).thenReturn(0);
        AsyncTaskEntity t = task(AsyncTaskEntity.KIND_IMMEDIATE);
        t.setRetryMaxAttempts(3);
        AsyncTaskRunEntity r = running(t, 1, 1);
        lifecycle.failRun(r.getId(), "boom", true, null);
        assertEquals(1, runs.size(), "双写落败方不得再建重试 run");
        assertEquals(AsyncTaskEntity.STATUS_ACTIVE, t.getStatus(), "双写落败方不得触碰任务态");
        verify(hub, never()).done(anyString(), anyString());
    }

    @Test
    void computeNextByKind() {
        AsyncTaskEntity fixed = task(AsyncTaskEntity.KIND_FIXED_DELAY);
        fixed.setIntervalMs(10_000L);
        Instant base = Instant.now();
        assertEquals(base.plusMillis(10_000L), lifecycle.computeNext(fixed, base));

        AsyncTaskEntity cron = task(AsyncTaskEntity.KIND_CRON);
        cron.setCronExpr("0 * * * * *");
        assertNotNull(lifecycle.computeNext(cron, base));

        AsyncTaskEntity once = task(AsyncTaskEntity.KIND_IMMEDIATE);
        assertNull(lifecycle.computeNext(once, base), "一次性任务没有下一轮");
    }
}
