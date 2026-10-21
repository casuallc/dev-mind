package com.devmind.task.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.task.api.AsyncTaskHandler;
import com.devmind.task.api.ScheduleSpec;
import com.devmind.task.api.TaskOptions;
import com.devmind.task.api.TaskTypeView;
import com.devmind.task.api.TaskUpsertRequest;
import com.devmind.task.engine.AsyncTaskExecutor;
import com.devmind.task.engine.RunLifecycleService;
import com.devmind.task.engine.TaskDispatcher;
import com.devmind.task.model.AsyncTaskEntity;
import com.devmind.task.model.AsyncTaskRunEntity;
import com.devmind.task.repo.AsyncTaskRepository;
import com.devmind.task.repo.AsyncTaskRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-80 FR-10 服务层：把提交/登记/生命周期控制的「该不该拒」逐条钉成断言。
 *
 * <p>按本仓既有测试范式（见 decision-lab DatasetServiceTest）：Spring Boot 4.1.1 无 JPA 切片，
 * 服务层用 mock 仓储 + 内存 Map 往返；JPQL/乐观认领 SQL 的正确性由 E2E 兜住。</p>
 */
class AsyncTaskServiceTest {

    private final AsyncTaskRepository taskRepo = mock(AsyncTaskRepository.class);
    private final AsyncTaskRunRepository runRepo = mock(AsyncTaskRunRepository.class);
    private final RunLifecycleService lifecycle = mock(RunLifecycleService.class);
    private final AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final AgentNodeConnector connector = mock(AgentNodeConnector.class);
    private final Map<String, AsyncTaskEntity> tasks = new LinkedHashMap<>();

    private AsyncTaskService service;

    @BeforeEach
    void setUp() {
        tasks.clear();
        when(taskRepo.save(any(AsyncTaskEntity.class))).thenAnswer(inv -> {
            AsyncTaskEntity t = inv.getArgument(0);
            tasks.put(t.getId(), t);
            return t;
        });
        when(taskRepo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(tasks.get(inv.getArgument(0))));
        when(taskRepo.findByDedupKey(anyString())).thenAnswer(inv -> tasks.values().stream()
                .filter(t -> inv.getArgument(0).equals(t.getDedupKey())).findFirst());
        when(runRepo.findByStatus(AsyncTaskRunEntity.RUNNING)).thenReturn(List.of());
        when(identity.currentActor()).thenReturn("tester");
        when(identity.currentUser()).thenReturn(Optional.empty());
        // 真实分发器（内置 LOCAL stub + RUNNER stub），真实 next_run_at 计算（纯函数）
        TaskDispatcher dispatcher = new TaskDispatcher(List.of(stubHandler("echo"), runnerHandler("remote.shell")));
        when(lifecycle.computeNext(any(AsyncTaskEntity.class), any(Instant.class)))
                .thenAnswer(inv -> {
                    AsyncTaskEntity t = inv.getArgument(0);
                    Instant base = inv.getArgument(1);
                    return AsyncTaskEntity.KIND_FIXED_DELAY.equals(t.getScheduleKind())
                            ? base.plusMillis(t.getIntervalMs()) : null;
                });
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentNodeConnector> connectorProvider = mock(ObjectProvider.class);
        when(connectorProvider.getIfAvailable()).thenReturn(connector);
        service = new AsyncTaskService(taskRepo, runRepo, dispatcher, lifecycle, executor, identity,
                new ObjectMapper(), connectorProvider);
    }

    private static AsyncTaskHandler stubHandler(String type) {
        return new AsyncTaskHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void execute(String payload, com.devmind.task.api.TaskContext ctx) {
            }
        };
    }

    /** RUNNER 类 stub（executor 元数据 = RUNNER，创建期触发节点存在性校验） */
    private static AsyncTaskHandler runnerHandler(String type) {
        return new AsyncTaskHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void execute(String payload, com.devmind.task.api.TaskContext ctx) {
            }

            @Override
            public String executor() {
                return AsyncTaskHandler.EXECUTOR_RUNNER;
            }
        };
    }

    private AsyncTaskEntity oneTask() {
        return tasks.values().iterator().next();
    }

    @Test
    void submitImmediateRunsAtOnceWithSingleRound() {
        Instant before = Instant.now();
        String id = service.submit("echo", "{}", TaskOptions.builder().name("t1").build());
        AsyncTaskEntity t = tasks.get(id);
        assertNotNull(t);
        assertEquals(AsyncTaskEntity.KIND_IMMEDIATE, t.getScheduleKind());
        assertEquals(AsyncTaskEntity.STATUS_ACTIVE, t.getStatus());
        assertEquals(1, t.getMaxRuns());
        assertEquals("tester", t.getActor());
        assertNotNull(t.getNextRunAt());
        assertTrue(!t.getNextRunAt().isBefore(before));
    }

    @Test
    void submitWithRunAtBecomesDelayed() {
        Instant at = Instant.now().plusSeconds(3600);
        String id = service.submit("echo", "{}", TaskOptions.builder().runAt(at).build());
        AsyncTaskEntity t = tasks.get(id);
        assertEquals(AsyncTaskEntity.KIND_DELAY, t.getScheduleKind());
        assertEquals(at, t.getNextRunAt());
    }

    @Test
    void submitUnknownTypeIs400() {
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.submit("ghost", "{}", null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void scheduleRejectsBadCronAndBadInterval() {
        DevMindException bad = assertThrows(DevMindException.class,
                () -> service.schedule("echo", "{}", ScheduleSpec.cron("not-a-cron").build()));
        assertEquals(ErrorCode.BAD_REQUEST, bad.getErrorCode());
        DevMindException unreachable = assertThrows(DevMindException.class,
                () -> service.schedule("echo", "{}", ScheduleSpec.cron("0 0 0 30 2 *").build()));
        assertEquals(ErrorCode.BAD_REQUEST, unreachable.getErrorCode());
        DevMindException zero = assertThrows(DevMindException.class,
                () -> service.schedule("echo", "{}", ScheduleSpec.fixedDelay(0).build()));
        assertEquals(ErrorCode.BAD_REQUEST, zero.getErrorCode());
    }

    @Test
    void scheduleFixedDelayComputesFirstRunAt() {
        Instant before = Instant.now();
        String id = service.schedule("echo", "{}",
                ScheduleSpec.fixedDelay(60_000L).name("p1").maxRuns(5).build());
        AsyncTaskEntity t = tasks.get(id);
        assertEquals(AsyncTaskEntity.KIND_FIXED_DELAY, t.getScheduleKind());
        assertEquals(5, t.getMaxRuns());
        assertNotNull(t.getNextRunAt());
        assertTrue(t.getNextRunAt().isAfter(before));
    }

    @Test
    void ensureScheduledUpsertsWithoutResettingProgress() {
        String id = service.ensureScheduled("k1", "echo", "{}",
                ScheduleSpec.fixedDelay(60_000L).name("v1").build());
        AsyncTaskEntity created = tasks.get(id);
        assertEquals("k1", created.getDedupKey());
        created.setRunCount(7); // 模拟已跑过多轮

        String again = service.ensureScheduled("k1", "echo", "{\"v\":2}",
                ScheduleSpec.fixedDelay(120_000L).name("v2").build());
        assertEquals(id, again, "幂等登记必须复用既有任务，重启不产生重复");
        AsyncTaskEntity updated = tasks.get(id);
        assertEquals(120_000L, updated.getIntervalMs());
        assertEquals("v2", updated.getName());
        assertEquals(7, updated.getRunCount(), "upsert 不重置 run_count");
        assertEquals(AsyncTaskEntity.STATUS_ACTIVE, updated.getStatus());
    }

    @Test
    void pauseAndTriggerGuardStatus() {
        String id = service.submit("echo", "{}", null);
        // DONE/CANCELLED 之外：PAUSED 任务不可再暂停
        service.pause(id);
        assertEquals(AsyncTaskEntity.STATUS_PAUSED, tasks.get(id).getStatus());
        DevMindException pauseAgain = assertThrows(DevMindException.class, () -> service.pause(id));
        assertEquals(ErrorCode.CONFLICT, pauseAgain.getErrorCode());
        // PAUSED 不可触发（FR-10：先恢复再触发）
        DevMindException triggerPaused = assertThrows(DevMindException.class, () -> service.triggerNow(id));
        assertEquals(ErrorCode.CONFLICT, triggerPaused.getErrorCode());
    }

    @Test
    void resumeOverdueTaskRunsImmediately() {
        String id = service.submit("echo", "{}", null);
        tasks.get(id).setNextRunAt(Instant.now().minusSeconds(120)); // 暂停期间已过期
        service.pause(id);
        Instant before = Instant.now();
        service.resume(id);
        AsyncTaskEntity t = tasks.get(id);
        assertEquals(AsyncTaskEntity.STATUS_ACTIVE, t.getStatus());
        assertTrue(!t.getNextRunAt().isBefore(before), "过期的 next_run_at 恢复时置 now 补跑");
    }

    @Test
    void cancelCascadesToRuns() {
        String id = service.submit("echo", "{}", null);
        service.cancel(id);
        AsyncTaskEntity t = tasks.get(id);
        assertEquals(AsyncTaskEntity.STATUS_CANCELLED, t.getStatus());
        assertNull(t.getNextRunAt());
        verify(runRepo).cancelQueuedByTask(anyString(), any(Instant.class));
        // 终态任务不可再取消
        DevMindException e = assertThrows(DevMindException.class, () -> service.cancel(id));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
    }

    @Test
    void nonAdminSeesOnlyOwnTasksAndForeignDetailIs404() {
        String id = service.submit("echo", "{}", null); // actor=tester
        when(identity.currentActor()).thenReturn("someone-else");
        assertEquals("someone-else", service.actorFilterForCurrentUser(), "非 admin 强制本人过滤");
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.requireVisible(tasks.get(id)));
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode(), "他人任务按 404，不泄露存在性");
    }

    @Test
    void adminGetsNoActorFilter() {
        UserEntity admin = new UserEntity();
        admin.setUsername("root");
        admin.setRole(UserEntity.ROLE_ADMIN);
        when(identity.currentUser()).thenReturn(Optional.of(admin));
        when(identity.currentActor()).thenReturn("root");
        assertTrue(service.isAdmin());
        assertNull(service.actorFilterForCurrentUser());
    }

    // ---------------- M4：REST 创建/编辑/types ----------------

    private static TaskUpsertRequest upsert(String type, String payload, String kind) {
        return new TaskUpsertRequest(null, type, payload, kind, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    @Test
    void createImmediateViaRestShape() {
        String id = service.create(upsert("echo", "{}", null)); // kind 空 = IMMEDIATE
        AsyncTaskEntity t = tasks.get(id);
        assertNotNull(t);
        assertEquals(AsyncTaskEntity.KIND_IMMEDIATE, t.getScheduleKind());
        assertEquals(1, t.getMaxRuns());
        assertEquals("echo", t.getName(), "name 空回落 type");
    }

    @Test
    void createRejectsInvalidPayloadAndBadSchedule() {
        DevMindException badJson = assertThrows(DevMindException.class,
                () -> service.create(upsert("echo", "not-json", null)));
        assertEquals(ErrorCode.BAD_REQUEST, badJson.getErrorCode());
        DevMindException delayNoAt = assertThrows(DevMindException.class,
                () -> service.create(upsert("echo", "{}", "DELAY")));
        assertEquals(ErrorCode.BAD_REQUEST, delayNoAt.getErrorCode());
        DevMindException badCron = assertThrows(DevMindException.class,
                () -> service.create(new TaskUpsertRequest(null, "echo", "{}", "CRON", null, null,
                        "not-a-cron", null, null, null, null, null, null, null, null)));
        assertEquals(ErrorCode.BAD_REQUEST, badCron.getErrorCode());
        DevMindException badKind = assertThrows(DevMindException.class,
                () -> service.create(upsert("echo", "{}", "HOURLY")));
        assertEquals(ErrorCode.BAD_REQUEST, badKind.getErrorCode());
    }

    @Test
    void createRunnerTaskValidatesNodeExistence() {
        when(connector.nodeExists("9")).thenReturn(false);
        DevMindException e = assertThrows(DevMindException.class, () -> service.create(
                new TaskUpsertRequest(null, "remote.shell", "{}", null, null, null, null, null,
                        "9", null, null, null, null, null, null)));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());

        when(connector.nodeExists("9")).thenReturn(true);
        String id = service.create(new TaskUpsertRequest(null, "remote.shell", "{}", null, null, null,
                null, null, "9", null, null, null, null, null, null));
        assertEquals("9", tasks.get(id).getAgentNodeId());
    }

    @Test
    void createLocalTaskIgnoresAgentNodeId() {
        // LOCAL 类任务：agent_node_id 不校验也不落库（忽略语义）
        String id = service.create(new TaskUpsertRequest(null, "echo", "{}", null, null, null,
                null, null, "9", null, null, null, null, null, null));
        assertNull(tasks.get(id).getAgentNodeId());
    }

    @Test
    void updateTerminalTaskIs409() {
        String id = service.submit("echo", "{}", null);
        service.cancel(id);
        DevMindException e = assertThrows(DevMindException.class,
                () -> service.update(id, upsert("echo", "{}", null)));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
    }

    @Test
    void updateActiveTaskRecomputesNextRunAt() {
        String id = service.schedule("echo", "{}",
                ScheduleSpec.fixedDelay(60_000L).name("p1").build());
        AsyncTaskEntity before = tasks.get(id);
        Instant oldNext = before.getNextRunAt();
        service.update(id, new TaskUpsertRequest("p1-改", "echo", "{\"v\":2}", "FIXED_DELAY", null,
                120_000L, null, 5, null, null, null, null, null, null, null));
        AsyncTaskEntity t = tasks.get(id);
        assertEquals(120_000L, t.getIntervalMs());
        assertEquals("{\"v\":2}", t.getPayload());
        assertEquals(5, t.getMaxRuns());
        assertNotNull(t.getNextRunAt());
        assertTrue(t.getNextRunAt().isAfter(oldNext), "间隔拉长后下一轮应晚于原排期");
    }

    @Test
    void listTypesCarriesExecutorMetadata() {
        List<TaskTypeView> types = service.listTypes();
        TaskTypeView echo = types.stream().filter(v -> v.type().equals("echo")).findFirst().orElseThrow();
        TaskTypeView shell = types.stream().filter(v -> v.type().equals("remote.shell")).findFirst().orElseThrow();
        assertEquals(AsyncTaskHandler.EXECUTOR_LOCAL, echo.executor());
        assertEquals(AsyncTaskHandler.EXECUTOR_RUNNER, shell.executor());
    }
}
