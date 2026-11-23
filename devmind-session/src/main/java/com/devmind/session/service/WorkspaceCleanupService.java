package com.devmind.session.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.notification.NotificationEvent;
import com.devmind.notification.NotificationPublisher;
import com.devmind.session.dto.WorkspaceCleanupConfigView;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.WorkspaceCleanupConfigEntity;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.repo.WorkspaceCleanupConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CAP-114：会话工作区定时清理——策略读写 + 清理执行。
 *
 * <p>调度模型：{@link WorkspaceCleanupTask} 固定每小时 tick，tick 内读 DB 配置判定
 * 「enabled 且当前小时 == runHour 且今日未跑」→ 执行一轮。策略即 DB 配置，改即生效，
 * 不依赖 @Scheduled cron（表达式吃不了 DB 值），同 WorklogScheduler 的「固定 tick +
 * 读库判定」模式。</p>
 *
 * <p>清理动作复用 {@link SessionManagerService#releaseWorkspaceInternal} 的既有释放链路
 * （workspace_release 帧 → runner 丢弃式删除 → 落 RELEASED）；单个候选失败只记原因，
 * 不阻断本轮其余候选，下轮重试。并发护栏：{@link #running} 保证定时与手动触发不叠跑。</p>
 */
@Service
public class WorkspaceCleanupService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCleanupService.class);

    /** 默认值（无配置行时的返回口径，PUT 才建行） */
    static final boolean DEFAULT_ENABLED = false;
    static final int DEFAULT_RETENTION_DAYS = 30;
    static final int DEFAULT_RUN_HOUR = 3;
    static final int MIN_RETENTION_DAYS = 1;
    static final int MAX_RETENTION_DAYS = 365;
    /** 报告明细条数上限（防会话量大时 lastReport 列膨胀） */
    private static final int MAX_REPORT_DETAILS = 100;

    private final WorkspaceCleanupConfigRepository configRepo;
    private final SessionRepository sessionRepo;
    private final SessionManagerService sessionManager;
    private final IdentityService identityService;
    private final NotificationPublisher notificationPublisher;
    private final ObjectMapper mapper;

    /** 手动触发与定时 tick 共用的单线程执行器（清理内含 WS 阻塞等 ack，不能跑在调度线程上） */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "workspace-cleanup");
        t.setDaemon(true);
        return t;
    });
    /** 防叠跑：上一轮没结束，新触发直接忽略 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WorkspaceCleanupService(WorkspaceCleanupConfigRepository configRepo,
                                   SessionRepository sessionRepo,
                                   SessionManagerService sessionManager,
                                   IdentityService identityService,
                                   NotificationPublisher notificationPublisher,
                                   ObjectMapper mapper) {
        this.configRepo = configRepo;
        this.sessionRepo = sessionRepo;
        this.sessionManager = sessionManager;
        this.identityService = identityService;
        this.notificationPublisher = notificationPublisher;
        this.mapper = mapper;
    }

    /** 读配置（无行返回默认值，不落库）。 */
    public WorkspaceCleanupConfigView getConfig() {
        WorkspaceCleanupConfigEntity e = configRepo.findById(WorkspaceCleanupConfigEntity.SINGLETON_ID)
                .orElse(null);
        if (e == null) {
            return new WorkspaceCleanupConfigView(DEFAULT_ENABLED, DEFAULT_RETENTION_DAYS, DEFAULT_RUN_HOUR,
                    true, true, null, null);
        }
        return toView(e);
    }

    /** 写配置（upsert 单行）；字段校验失败 400。 */
    public WorkspaceCleanupConfigView saveConfig(WorkspaceCleanupConfigView req) {
        int retentionDays = req.retentionDays() != null ? req.retentionDays() : DEFAULT_RETENTION_DAYS;
        int runHour = req.runHour() != null ? req.runHour() : DEFAULT_RUN_HOUR;
        boolean scopeRequirement = req.scopeRequirement() == null || req.scopeRequirement();
        boolean scopeSession = req.scopeSession() == null || req.scopeSession();
        if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "保留天数需在 " + MIN_RETENTION_DAYS + "~" + MAX_RETENTION_DAYS + " 天之间");
        }
        if (runHour < 0 || runHour > 23) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "执行时间需在 0~23 点之间");
        }
        if (!scopeRequirement && !scopeSession) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "清理范围至少选择一类工作区");
        }
        WorkspaceCleanupConfigEntity e = configRepo.findById(WorkspaceCleanupConfigEntity.SINGLETON_ID)
                .orElseGet(WorkspaceCleanupConfigEntity::new);
        e.setEnabled(req.enabled() != null && req.enabled());
        e.setRetentionDays(retentionDays);
        e.setRunHour(runHour);
        e.setScopeRequirement(scopeRequirement);
        e.setScopeSession(scopeSession);
        e.setUpdatedAt(Instant.now());
        e.setUpdatedBy(identityService.currentActor());
        return toView(configRepo.save(e));
    }

    /**
     * 定时 tick（每小时一次）：enabled 且当前小时命中 runHour 且今日未跑 → 触发一轮。
     * 返回是否触发（单测断言用）。
     */
    public boolean tick() {
        WorkspaceCleanupConfigEntity e = configRepo.findById(WorkspaceCleanupConfigEntity.SINGLETON_ID)
                .orElse(null);
        if (e == null || !Boolean.TRUE.equals(e.getEnabled())) {
            return false;
        }
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        int runHour = e.getRunHour() != null ? e.getRunHour() : DEFAULT_RUN_HOUR;
        if (now.getHour() != runHour) {
            return false;
        }
        if (e.getLastRunAt() != null
                && LocalDate.ofInstant(e.getLastRunAt(), ZoneId.systemDefault()).equals(now.toLocalDate())) {
            return false; // 今日已跑（含手动触发），防同小时多次 tick 重跑
        }
        return trigger("scheduled");
    }

    /** 手动触发一轮（设置页「立即执行一次」）：无视 enabled/runHour/lastRunAt，仍按 retentionDays/scope 筛选。 */
    public boolean triggerManual() {
        return trigger("manual");
    }

    /** 触发一轮异步清理；已在跑则忽略（返回 false）。 */
    private boolean trigger(String triggerType) {
        if (!running.compareAndSet(false, true)) {
            log.info("工作区定时清理上一轮未结束，忽略本次触发: trigger={}", triggerType);
            return false;
        }
        executor.submit(() -> {
            try {
                runOnce(triggerType);
            } catch (Exception e) {
                log.warn("工作区定时清理执行异常（下轮重试）: {}", e.getMessage());
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    /**
     * 清理一轮本体（包可见便于单测同步驱动）：按策略筛候选 → 按工作区键去重 →
     * 逐候选走释放链路 → 报告落库 + 有清理/失败时发站内通知。
     */
    void runOnce(String triggerType) {
        WorkspaceCleanupConfigView cfg = getConfig();
        int retentionDays = cfg.retentionDays() != null ? cfg.retentionDays() : DEFAULT_RETENTION_DAYS;
        boolean scopeRequirement = cfg.scopeRequirement() == null || cfg.scopeRequirement();
        boolean scopeSession = cfg.scopeSession() == null || cfg.scopeSession();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        List<String> cleaned = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int skippedActive = 0;
        // 需求级共享工作树：同需求多会话共用一棵，按键去重避免重复下发释放帧
        Map<String, SessionEntity> candidates = new LinkedHashMap<>();
        for (SessionEntity s : sessionRepo.findByWorkspaceStateOrderByUpdatedAtAsc(SessionEntity.WORKSPACE_OPEN)) {
            if (s.getUpdatedAt() != null && s.getUpdatedAt().isAfter(cutoff)) {
                continue; // 未超龄，静默跳过（正常路径）
            }
            if (s.getAgentNodeId() == null || s.getAgentNodeId().isBlank()) {
                continue; // 历史本机会话：无节点可清理
            }
            if (isActiveOrSuspended(s.getStatus())) {
                skippedActive++; // 进行中（含挂起）会话的工作区永不自动清
                continue;
            }
            boolean requirementScoped = s.getWorkspaceKey() != null && s.getWorkspaceKey().startsWith("req-");
            if (requirementScoped && !scopeRequirement) {
                continue;
            }
            if (!requirementScoped && !scopeSession) {
                continue;
            }
            String dedupeKey = requirementScoped ? "req:" + s.getRequirementId() : "sid:" + s.getId();
            candidates.putIfAbsent(dedupeKey, s);
        }
        for (Map.Entry<String, SessionEntity> entry : candidates.entrySet()) {
            SessionEntity s = entry.getValue();
            try {
                sessionManager.releaseWorkspaceInternal(s);
                cleaned.add(label(s));
                log.info("定时清理已释放工作区: session={} key={}", s.getId(), s.getWorkspaceKey());
            } catch (Exception e) {
                failed.add(label(s) + "（" + e.getMessage() + "）");
                log.warn("定时清理释放失败（下轮重试）: session={} err={}", s.getId(), e.getMessage());
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("trigger", triggerType);
        report.put("finishedAt", Instant.now().toString());
        report.put("retentionDays", retentionDays);
        report.put("cleaned", cleaned.size());
        report.put("skippedActive", skippedActive);
        report.put("failed", failed.size());
        List<String> allDetails = new ArrayList<>();
        cleaned.forEach(c -> allDetails.add("已清理 " + c));
        failed.forEach(f -> allDetails.add("失败 " + f));
        List<String> details = allDetails;
        if (details.size() > MAX_REPORT_DETAILS) {
            details = new ArrayList<>(details.subList(0, MAX_REPORT_DETAILS));
            details.add("……（明细超 " + MAX_REPORT_DETAILS + " 条截断）");
        }
        report.put("details", details);
        String reportJson = mapper.writeValueAsString(report);
        persistReport(reportJson);

        if (!cleaned.isEmpty() || !failed.isEmpty()) {
            String content = "清理 " + cleaned.size() + " 个、跳过进行中 " + skippedActive
                    + " 个、失败 " + failed.size() + " 个（保留 " + retentionDays + " 天口径）";
            if (!failed.isEmpty()) {
                content += "；失败明细见设置页「工作区清理」报告";
            }
            try {
                notificationPublisher.publish(NotificationEvent.of("WORKSPACE_CLEANUP_DONE", null,
                        "会话工作区定时清理完成", content));
            } catch (Exception e) {
                log.debug("清理结果通知发布失败(忽略): {}", e.getMessage());
            }
        }
        log.info("工作区定时清理完成: trigger={} 清理 {} 跳过进行中 {} 失败 {}",
                triggerType, cleaned.size(), skippedActive, failed.size());
    }

    /** 会话状态是否进行中（含挂起——挂起保留工作区可 resume，同样不可清）；状态值异常的历史行按进行中兜底 */
    private static boolean isActiveOrSuspended(String status) {
        try {
            SessionState st = SessionState.valueOf(status);
            return st.isActive() || st == SessionState.SUSPENDED;
        } catch (Exception e) {
            return true; // 宁跳不错删
        }
    }

    /** 报告落库（配置行不存在时只建报告行——不影响开关语义：enabled 仍默认关）。 */
    private void persistReport(String reportJson) {
        WorkspaceCleanupConfigEntity e = configRepo.findById(WorkspaceCleanupConfigEntity.SINGLETON_ID)
                .orElseGet(WorkspaceCleanupConfigEntity::new);
        e.setLastRunAt(Instant.now());
        e.setLastReport(reportJson);
        configRepo.save(e);
    }

    private static String label(SessionEntity s) {
        String key = s.getWorkspaceKey() != null && !s.getWorkspaceKey().isBlank()
                ? s.getWorkspaceKey() : "work";
        return key + "@" + (s.getWorkspaceOwner() != null && !s.getWorkspaceOwner().isBlank()
                ? s.getWorkspaceOwner() : s.getCreatedBy());
    }

    private static WorkspaceCleanupConfigView toView(WorkspaceCleanupConfigEntity e) {
        return new WorkspaceCleanupConfigView(
                Boolean.TRUE.equals(e.getEnabled()),
                e.getRetentionDays() != null ? e.getRetentionDays() : DEFAULT_RETENTION_DAYS,
                e.getRunHour() != null ? e.getRunHour() : DEFAULT_RUN_HOUR,
                e.getScopeRequirement() == null || e.getScopeRequirement(),
                e.getScopeSession() == null || e.getScopeSession(),
                e.getLastRunAt(), e.getLastReport());
    }
}
