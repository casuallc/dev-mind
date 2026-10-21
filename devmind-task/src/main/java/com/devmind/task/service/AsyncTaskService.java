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
import com.devmind.task.engine.TaskIds;
import com.devmind.task.model.AsyncTaskEntity;
import com.devmind.task.model.AsyncTaskRunEntity;
import com.devmind.task.repo.AsyncTaskRepository;
import com.devmind.task.repo.AsyncTaskRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * CAP-80 FR-10 任务提交与控制服务（Java 侧统一入口；REST 管理面也走这里）。
 *
 * <p>方法均<b>不加 @Transactional</b>（异步触发红线）：靠 save 自身事务即时提交，
 * tick/执行线程立刻可见。</p>
 */
@Service
public class AsyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskService.class);

    private final AsyncTaskRepository taskRepo;
    private final AsyncTaskRunRepository runRepo;
    private final TaskDispatcher dispatcher;
    private final RunLifecycleService lifecycle;
    private final AsyncTaskExecutor executor;
    private final IdentityService identityService;
    private final ObjectMapper mapper;
    /** M4：节点存在性校验用（agent 模块未装配 = 无节点可指定） */
    private final ObjectProvider<AgentNodeConnector> connectorProvider;

    public AsyncTaskService(AsyncTaskRepository taskRepo, AsyncTaskRunRepository runRepo,
                            TaskDispatcher dispatcher, RunLifecycleService lifecycle,
                            AsyncTaskExecutor executor, IdentityService identityService,
                            ObjectMapper mapper, ObjectProvider<AgentNodeConnector> connectorProvider) {
        this.taskRepo = taskRepo;
        this.runRepo = runRepo;
        this.dispatcher = dispatcher;
        this.lifecycle = lifecycle;
        this.executor = executor;
        this.identityService = identityService;
        this.mapper = mapper;
        this.connectorProvider = connectorProvider;
    }

    /** 一次性任务提交（IMMEDIATE/DELAY，固定 max_runs=1）。返回任务 id。 */
    public String submit(String type, String payload, TaskOptions opts) {
        dispatcher.require(type);
        TaskOptions o = opts == null ? TaskOptions.builder().build() : opts;
        Instant now = Instant.now();
        boolean delayed = o.getRunAt() != null;

        AsyncTaskEntity t = new AsyncTaskEntity();
        t.setId(TaskIds.newId());
        t.setTaskType(type);
        t.setName(o.getName() == null || o.getName().isBlank() ? type : o.getName());
        t.setPayload(payload);
        t.setScheduleKind(delayed ? AsyncTaskEntity.KIND_DELAY : AsyncTaskEntity.KIND_IMMEDIATE);
        t.setRunAt(delayed ? o.getRunAt() : null);
        t.setMaxRuns(1);
        applyPolicies(t, o.getRetryMaxAttempts(), o.getRetryBackoff(), o.getRetryIntervalMs(),
                o.getTimeoutMs(), o.getOverlapPolicy());
        t.setNextRunAt(delayed ? o.getRunAt() : now);
        t.setStatus(AsyncTaskEntity.STATUS_ACTIVE);
        t.setActor(identityService.currentActor());
        t.setProjectId(o.getProjectId());
        t.setAgentNodeId(o.getAgentNodeId());
        t.setCreatedAt(now);
        t.touch();
        taskRepo.save(t);
        log.info("异步任务已提交: {} [{}] {}", t.getId(), t.getScheduleKind(), type);
        return t.getId();
    }

    /** 周期任务登记（FIXED_DELAY/CRON）。返回任务 id。 */
    public String schedule(String type, String payload, ScheduleSpec spec) {
        AsyncTaskEntity t = buildScheduled(type, payload, spec, identityService.currentActor());
        taskRepo.save(t);
        log.info("周期任务已登记: {} [{}] {}", t.getId(), t.getScheduleKind(), type);
        return t.getId();
    }

    /**
     * 幂等登记（FR-10）：按 dedupKey upsert——不存在则创建；已存在仅更新调度策略与
     * payload/name，<b>不重置 run_count/status</b>。供存量 @Scheduled 迁移在启动期登记，
     * 重启不产生重复任务。
     */
    public String ensureScheduled(String dedupKey, String type, String payload, ScheduleSpec spec) {
        if (dedupKey == null || dedupKey.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "dedupKey 不能为空");
        }
        AsyncTaskEntity existing = taskRepo.findByDedupKey(dedupKey).orElse(null);
        if (existing == null) {
            AsyncTaskEntity t = buildScheduled(type, payload, spec, identityService.currentActor());
            t.setDedupKey(dedupKey);
            taskRepo.save(t);
            log.info("幂等登记新建周期任务: {} [{}] {}（dedup={}）", t.getId(), t.getScheduleKind(), type, dedupKey);
            return t.getId();
        }
        validateSpec(spec);
        dispatcher.require(type);
        existing.setTaskType(type);
        existing.setName(specName(spec, type));
        existing.setPayload(payload);
        existing.setScheduleKind(spec.getKind());
        existing.setIntervalMs(spec.getIntervalMs());
        existing.setCronExpr(spec.getCronExpr());
        existing.setMaxRuns(spec.getMaxRuns());
        existing.setProjectId(spec.getProjectId());
        existing.setAgentNodeId(spec.getAgentNodeId());
        applyPolicies(existing, spec.getRetryMaxAttempts(), spec.getRetryBackoff(),
                spec.getRetryIntervalMs(), spec.getTimeoutMs(), spec.getOverlapPolicy());
        existing.touch();
        taskRepo.save(existing);
        return existing.getId();
    }

    /** 暂停：仅 ACTIVE 可暂停。 */
    public void pause(String taskId) {
        AsyncTaskEntity t = requireTask(taskId);
        if (!AsyncTaskEntity.STATUS_ACTIVE.equals(t.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "仅 ACTIVE 任务可暂停（当前 " + t.getStatus() + "）");
        }
        t.setStatus(AsyncTaskEntity.STATUS_PAUSED);
        t.touch();
        taskRepo.save(t);
    }

    /**
     * 恢复：PAUSED→ACTIVE；next_run_at 已过期（或为空）则置 now 立即补跑一次——
     * 跨多个漏跑轮次也只补一次（FR-07）。
     */
    public void resume(String taskId) {
        AsyncTaskEntity t = requireTask(taskId);
        if (!AsyncTaskEntity.STATUS_PAUSED.equals(t.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "仅 PAUSED 任务可恢复（当前 " + t.getStatus() + "）");
        }
        Instant now = Instant.now();
        t.setStatus(AsyncTaskEntity.STATUS_ACTIVE);
        if (t.getNextRunAt() == null || !t.getNextRunAt().isAfter(now)) {
            // next_run_at 为空 = 暂停时恰有在途 run（认领置 NULL），置 now 由重叠策略裁决
            t.setNextRunAt(now);
        }
        t.touch();
        taskRepo.save(t);
    }

    /** 取消：终态 CANCELLED；级联中断在途 run（QUEUED 直接判 CANCELLED，RUNNING 协作式中断）。 */
    public void cancel(String taskId) {
        AsyncTaskEntity t = requireTask(taskId);
        if (AsyncTaskEntity.STATUS_DONE.equals(t.getStatus())
                || AsyncTaskEntity.STATUS_CANCELLED.equals(t.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "任务已终态（" + t.getStatus() + "）");
        }
        t.setStatus(AsyncTaskEntity.STATUS_CANCELLED);
        t.setNextRunAt(null);
        t.touch();
        taskRepo.save(t);
        runRepo.cancelQueuedByTask(taskId, Instant.now());
        for (AsyncTaskRunEntity run : runRepo.findByStatus(AsyncTaskRunEntity.RUNNING)) {
            if (taskId.equals(run.getTaskId())) {
                executor.interrupt(run.getId());
                lifecycle.cancelRun(run.getId());
            }
        }
    }

    /** 立即触发：仅 ACTIVE（PAUSED 请先恢复，DONE/CANCELLED 已终态），重叠策略照常生效。 */
    public void triggerNow(String taskId) {
        AsyncTaskEntity t = requireTask(taskId);
        if (!AsyncTaskEntity.STATUS_ACTIVE.equals(t.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "仅 ACTIVE 任务可触发（当前 " + t.getStatus() + "，PAUSED 请先恢复）");
        }
        t.setNextRunAt(Instant.now());
        t.touch();
        taskRepo.save(t);
    }

    /**
     * CAP-80 M4 REST 创建（仅 ADMIN，SecurityConfig 收敛）。四形态共用一份请求：
     * IMMEDIATE/DELAY 走 {@link #submit}（固定 max_runs=1），FIXED_DELAY/CRON 走 {@link #schedule}。
     */
    public String create(TaskUpsertRequest req) {
        String kind = validateUpsert(req);
        if (AsyncTaskEntity.KIND_FIXED_DELAY.equals(kind) || AsyncTaskEntity.KIND_CRON.equals(kind)) {
            return schedule(req.type(), req.payload(), toSpec(req, kind));
        }
        return submit(req.type(), req.payload(), toOptions(req));
    }

    /**
     * CAP-80 M4 REST 编辑（仅 ADMIN）。终态（DONE/CANCELLED）409；ACTIVE 且当前无在途 run
     * （next_run_at 非空）按新调度规格即时重排；PAUSED 保持原 next_run_at（恢复语义不变）。
     * LOCAL 类任务的 agent_node_id 一律不落库（忽略语义，避免误读为生效）。
     */
    public void update(String taskId, TaskUpsertRequest req) {
        AsyncTaskEntity t = requireTask(taskId);
        if (AsyncTaskEntity.STATUS_DONE.equals(t.getStatus())
                || AsyncTaskEntity.STATUS_CANCELLED.equals(t.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "终态任务不可编辑（" + t.getStatus() + "）");
        }
        String kind = validateUpsert(req);
        t.setTaskType(req.type());
        t.setName(req.name() == null || req.name().isBlank() ? req.type() : req.name());
        t.setPayload(req.payload());
        t.setScheduleKind(kind);
        t.setRunAt(AsyncTaskEntity.KIND_DELAY.equals(kind) ? req.runAt() : null);
        t.setIntervalMs(AsyncTaskEntity.KIND_FIXED_DELAY.equals(kind) ? req.intervalMs() : null);
        t.setCronExpr(AsyncTaskEntity.KIND_CRON.equals(kind) ? req.cronExpr() : null);
        boolean oneShot = AsyncTaskEntity.KIND_IMMEDIATE.equals(kind) || AsyncTaskEntity.KIND_DELAY.equals(kind);
        t.setMaxRuns(oneShot ? 1 : req.maxRuns());
        t.setProjectId(req.projectId());
        t.setAgentNodeId(isRunnerType(req.type()) ? blankToNull(req.agentNodeId()) : null);
        applyPolicies(t, req.retryMaxAttempts(), req.retryBackoff(), req.retryIntervalMs(),
                req.timeoutMs(), req.overlapPolicy());
        if (AsyncTaskEntity.STATUS_ACTIVE.equals(t.getStatus()) && t.getNextRunAt() != null) {
            t.setNextRunAt(recomputeAfterEdit(t, kind, req));
        }
        t.touch();
        taskRepo.save(t);
        log.info("任务已编辑: {} [{}] {}", t.getId(), t.getScheduleKind(), t.getTaskType());
    }

    /** CAP-80 M4 types 端点：已注册 handler 元数据（页面类型下拉/模板/执行位置）。 */
    public List<TaskTypeView> listTypes() {
        return dispatcher.all().stream()
                .map(h -> new TaskTypeView(h.type(), h.description(), h.payloadTemplate(), h.executor()))
                .sorted(Comparator.comparing(TaskTypeView::type))
                .toList();
    }

    /** 创建/编辑共用校验：type 已注册、payload 合法 JSON、形态必填项、节点已登记（仅 RUNNER 类）。 */
    private String validateUpsert(TaskUpsertRequest req) {
        if (req == null || req.type() == null || req.type().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "任务类型 type 不能为空");
        }
        dispatcher.require(req.type());
        if (req.payload() != null && !req.payload().isBlank()) {
            try {
                mapper.readTree(req.payload());
            } catch (Exception e) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "payload 不是合法 JSON: " + e.getMessage());
            }
        }
        String kind = req.scheduleKind() == null || req.scheduleKind().isBlank()
                ? AsyncTaskEntity.KIND_IMMEDIATE : req.scheduleKind();
        switch (kind) {
            case AsyncTaskEntity.KIND_IMMEDIATE -> { /* 无额外必填 */ }
            case AsyncTaskEntity.KIND_DELAY -> {
                if (req.runAt() == null) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST, "DELAY 必须提供 runAt");
                }
            }
            case AsyncTaskEntity.KIND_FIXED_DELAY -> {
                if (req.intervalMs() == null || req.intervalMs() <= 0) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST, "FIXED_DELAY 必须提供正数 intervalMs");
                }
            }
            case AsyncTaskEntity.KIND_CRON -> {
                if (req.cronExpr() == null || !CronExpression.isValidExpression(req.cronExpr())) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST, "非法 cron 表达式: " + req.cronExpr());
                }
            }
            default -> throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "调度形态仅支持 IMMEDIATE/DELAY/FIXED_DELAY/CRON: " + kind);
        }
        if (req.maxRuns() != null && req.maxRuns() < 1) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "maxRuns 必须 >= 1");
        }
        if (isRunnerType(req.type()) && req.agentNodeId() != null && !req.agentNodeId().isBlank()) {
            AgentNodeConnector connector = connectorProvider.getIfAvailable();
            if (connector == null || !connector.nodeExists(req.agentNodeId().strip())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "执行节点不存在: " + req.agentNodeId());
            }
        }
        return kind;
    }

    private boolean isRunnerType(String type) {
        AsyncTaskHandler h = dispatcher.handlerOrNull(type);
        return h != null && AsyncTaskHandler.EXECUTOR_RUNNER.equals(h.executor());
    }

    /** 编辑后重排（仅 ACTIVE 且无在途 run 时调用）：一次性形态立即/按时刻，周期形态按规格算下一轮。 */
    private Instant recomputeAfterEdit(AsyncTaskEntity t, String kind, TaskUpsertRequest req) {
        if (AsyncTaskEntity.KIND_IMMEDIATE.equals(kind)) {
            return Instant.now();
        }
        if (AsyncTaskEntity.KIND_DELAY.equals(kind)) {
            return req.runAt();
        }
        return lifecycle.computeNext(t, Instant.now());
    }

    private TaskOptions toOptions(TaskUpsertRequest req) {
        TaskOptions.Builder b = TaskOptions.builder()
                .name(req.name()).projectId(req.projectId())
                .agentNodeId(isRunnerType(req.type()) ? blankToNull(req.agentNodeId()) : null);
        if (req.runAt() != null) {
            b.runAt(req.runAt());
        }
        if (req.retryMaxAttempts() != null) {
            b.retryMaxAttempts(req.retryMaxAttempts());
        }
        if (req.retryBackoff() != null) {
            b.retryBackoff(req.retryBackoff());
        }
        if (req.retryIntervalMs() != null) {
            b.retryIntervalMs(req.retryIntervalMs());
        }
        if (req.timeoutMs() != null) {
            b.timeoutMs(req.timeoutMs());
        }
        if (req.overlapPolicy() != null) {
            b.overlapPolicy(req.overlapPolicy());
        }
        return b.build();
    }

    private ScheduleSpec toSpec(TaskUpsertRequest req, String kind) {
        ScheduleSpec.Builder b = ScheduleSpec.builder(kind)
                .name(req.name()).projectId(req.projectId())
                .agentNodeId(isRunnerType(req.type()) ? blankToNull(req.agentNodeId()) : null);
        if (req.intervalMs() != null) {
            b.intervalMs(req.intervalMs());
        }
        if (req.cronExpr() != null) {
            b.cronExpr(req.cronExpr());
        }
        if (req.maxRuns() != null) {
            b.maxRuns(req.maxRuns());
        }
        if (req.retryMaxAttempts() != null) {
            b.retryMaxAttempts(req.retryMaxAttempts());
        }
        if (req.retryBackoff() != null) {
            b.retryBackoff(req.retryBackoff());
        }
        if (req.retryIntervalMs() != null) {
            b.retryIntervalMs(req.retryIntervalMs());
        }
        if (req.timeoutMs() != null) {
            b.timeoutMs(req.timeoutMs());
        }
        if (req.overlapPolicy() != null) {
            b.overlapPolicy(req.overlapPolicy());
        }
        return b.build();
    }

    public Page<AsyncTaskEntity> query(String type, String status, String keyword,
                                       String actor, int page, int size) {
        return taskRepo.search(blankToNull(type), blankToNull(status), blankToNull(actor),
                blankToNull(keyword), PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
    }

    public AsyncTaskEntity requireTask(String id) {
        return taskRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "任务不存在: " + id));
    }

    public Page<AsyncTaskRunEntity> runsOf(String taskId, int page, int size) {
        requireTask(taskId);
        return runRepo.findByTaskIdOrderByRunSeqDescAttemptDesc(taskId,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
    }

    public AsyncTaskRunEntity requireRun(String runId) {
        return runRepo.findById(runId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "执行记录不存在: " + runId));
    }

    /** 执行记录横查（管理页「执行记录」视图，admin 专用）。 */
    public Page<AsyncTaskRunEntity> queryRuns(String status, int page, int size) {
        return runRepo.queryRuns(blankToNull(status),
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
    }

    /** 非 admin 强制本人 actor 过滤（对齐 CAP-62 owner 强制口径）。返回 null=全量（admin）。 */
    public String actorFilterForCurrentUser() {
        if (isAdmin()) {
            return null;
        }
        return identityService.currentActor();
    }

    public boolean isAdmin() {
        return identityService.currentUser()
                .map(u -> UserEntity.ROLE_ADMIN.equals(u.getRole()))
                .orElse(false);
    }

    /** 属主校验：非 admin 访问他人任务按 404 处理（不泄露存在性，对齐附件归属 404 先例）。 */
    public void requireVisible(AsyncTaskEntity task) {
        if (!isAdmin() && !identityService.currentActor().equals(task.getActor())) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "任务不存在: " + task.getId());
        }
    }

    private AsyncTaskEntity buildScheduled(String type, String payload, ScheduleSpec spec, String actor) {
        dispatcher.require(type);
        validateSpec(spec);
        Instant now = Instant.now();
        AsyncTaskEntity t = new AsyncTaskEntity();
        t.setId(TaskIds.newId());
        t.setTaskType(type);
        t.setName(specName(spec, type));
        t.setPayload(payload);
        t.setScheduleKind(spec.getKind());
        t.setIntervalMs(spec.getIntervalMs());
        t.setCronExpr(spec.getCronExpr());
        t.setMaxRuns(spec.getMaxRuns());
        applyPolicies(t, spec.getRetryMaxAttempts(), spec.getRetryBackoff(), spec.getRetryIntervalMs(),
                spec.getTimeoutMs(), spec.getOverlapPolicy());
        t.setNextRunAt(lifecycle.computeNext(t, now));
        t.setStatus(AsyncTaskEntity.STATUS_ACTIVE);
        t.setActor(actor);
        t.setProjectId(spec.getProjectId());
        t.setAgentNodeId(spec.getAgentNodeId());
        t.setCreatedAt(now);
        t.touch();
        return t;
    }

    private void validateSpec(ScheduleSpec spec) {
        if (spec == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "调度规格不能为空");
        }
        if (AsyncTaskEntity.KIND_FIXED_DELAY.equals(spec.getKind())) {
            if (spec.getIntervalMs() == null || spec.getIntervalMs() <= 0) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "FIXED_DELAY 必须提供正数 intervalMs");
            }
        } else if (AsyncTaskEntity.KIND_CRON.equals(spec.getKind())) {
            if (spec.getCronExpr() == null || !CronExpression.isValidExpression(spec.getCronExpr())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "非法 cron 表达式: " + spec.getCronExpr());
            }
            if (CronExpression.parse(spec.getCronExpr()).next(ZonedDateTime.now()) == null) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "cron 表达式无可达执行时刻: " + spec.getCronExpr());
            }
        } else {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "周期任务形态仅支持 FIXED_DELAY/CRON: " + spec.getKind());
        }
    }

    private String specName(ScheduleSpec spec, String type) {
        return spec.getName() == null || spec.getName().isBlank() ? type : spec.getName();
    }

    private void applyPolicies(AsyncTaskEntity t, Integer retryMaxAttempts, String retryBackoff,
                               Long retryIntervalMs, Long timeoutMs, String overlapPolicy) {
        if (retryMaxAttempts != null) {
            if (retryMaxAttempts < 1) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "retryMaxAttempts 必须 >= 1");
            }
            t.setRetryMaxAttempts(retryMaxAttempts);
        }
        if (retryBackoff != null) {
            if (!AsyncTaskEntity.BACKOFF_FIXED.equals(retryBackoff)
                    && !AsyncTaskEntity.BACKOFF_EXPONENTIAL.equals(retryBackoff)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "retryBackoff 仅支持 FIXED/EXPONENTIAL");
            }
            t.setRetryBackoff(retryBackoff);
        }
        if (retryIntervalMs != null) {
            t.setRetryIntervalMs(retryIntervalMs);
        }
        if (timeoutMs != null) {
            t.setTimeoutMs(timeoutMs);
        }
        if (overlapPolicy != null) {
            if (!AsyncTaskEntity.OVERLAP_SKIP.equals(overlapPolicy)
                    && !AsyncTaskEntity.OVERLAP_QUEUE.equals(overlapPolicy)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "overlapPolicy 仅支持 SKIP/QUEUE");
            }
            t.setOverlapPolicy(overlapPolicy);
        }
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
