package com.devmind.worklog.service;

import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.model.WorklogUserSettingsEntity;
import com.devmind.worklog.repo.WorklogRepoSubscriptionRepository;
import com.devmind.worklog.repo.WorklogUserSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CAP-28 FR-05/06/09 定时调度（照 JiraSyncService 模板）：cron 配置化 + 防重入。
 *
 * <p>两级调度（2026-10-09 起）：不再为每个任务挂固定 cron，而是主 tick 每分钟一跳，
 * 逐用户解析生效 cron——个人执行时间（worklog_user_settings.daily_time 等）优先，
 * 未设置跟随全局（devmind.worklog.*-cron），见 {@link WorklogSchedule}。
 * 防重入按任务分锁（三个任务可被用户设到同一分钟，互不饿死）。</p>
 *
 * <p>CAP-41 FR-03 起生成 = 创建 worklog 会话（受理即返回，成稿由会话结束回传落镜像，
 * 见 {@link WorklogOutputMirror}），调度只需逐用户触发会话创建；并发冲突/节点离线等
 * 409 由会话层 fail-visible 抛出，这里 warn 跳过并发 P0 失败通知（不中断其他用户）。
 * 调度方法本身禁 @Transactional（红线）。</p>
 *
 * <p>调度线程无 SecurityContext：归属一律显式传 username，绝不调 currentActor()。</p>
 */
@Service
public class WorklogScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorklogScheduler.class);

    /** 防重入按任务分锁：label → guard（三个任务可被用户设到同一分钟，共用一个锁会互相饿死） */
    private final ConcurrentHashMap<String, AtomicBoolean> batchGuards = new ConcurrentHashMap<>();

    private final ReportService reportService;
    private final GitAutoImportService gitAutoImport;
    private final WorklogUserSettingsRepository settingsRepo;
    private final WorklogRepoSubscriptionRepository subRepo;
    private final WorklogProperties props;
    private final DomainEventPublisher eventPublisher;

    public WorklogScheduler(ReportService reportService,
                            GitAutoImportService gitAutoImport,
                            WorklogUserSettingsRepository settingsRepo,
                            WorklogRepoSubscriptionRepository subRepo,
                            WorklogProperties props,
                            DomainEventPublisher eventPublisher) {
        this.reportService = reportService;
        this.gitAutoImport = gitAutoImport;
        this.settingsRepo = settingsRepo;
        this.subRepo = subRepo;
        this.props = props;
        this.eventPublisher = eventPublisher;
    }

    /** 主 tick：每分钟一跳，逐任务逐用户判定本分钟是否到期（个人时间优先、全局兜底）。 */
    @Scheduled(cron = "${devmind.worklog.schedule-cron:0 * * * * *}")
    public void masterTick() {
        ZonedDateTime tick = ZonedDateTime.now();
        Map<String, WorklogUserSettingsEntity> byUser = settingsRepo.findAll().stream()
                .collect(Collectors.toMap(WorklogUserSettingsEntity::getUserId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        dailyBatch(tick, byUser);
        weeklyBatch(tick, byUser);
        gitImportBatch(tick, byUser);
    }

    /** 每日生成日报草稿（个人 dailyTime 优先，全局默认 18:30）。 */
    void dailyBatch(ZonedDateTime tick, Map<String, WorklogUserSettingsEntity> byUser) {
        if (!props.isDailyEnabled()) {
            return;
        }
        CronExpression global = parseGlobalCron("日报", props.getDailyCron());
        if (global == null) {
            return;
        }
        Set<String> due = new LinkedHashSet<>();
        for (String u : coveredUsers(true, byUser)) {
            WorklogUserSettingsEntity s = byUser.get(u);
            String userTime = s == null ? null : s.getDailyTime();
            if (WorklogSchedule.dueThisMinute(WorklogSchedule.resolveDaily(userTime, global), tick)) {
                due.add(u);
            }
        }
        LocalDate today = LocalDate.now();
        runBatch("日报", due, u -> reportService.generateDaily(u, today, false));
    }

    /** 每周生成上周周报草稿（个人 weeklyDay+weeklyTime 同时设置才覆盖，全局默认周一 09:00）。 */
    void weeklyBatch(ZonedDateTime tick, Map<String, WorklogUserSettingsEntity> byUser) {
        if (!props.isWeeklyEnabled()) {
            return;
        }
        CronExpression global = parseGlobalCron("周报", props.getWeeklyCron());
        if (global == null) {
            return;
        }
        Set<String> due = new LinkedHashSet<>();
        for (String u : coveredUsers(false, byUser)) {
            WorklogUserSettingsEntity s = byUser.get(u);
            CronExpression effective = s == null ? global
                    : WorklogSchedule.resolveWeekly(s.getWeeklyTime(), s.getWeeklyDay(), global);
            if (WorklogSchedule.dueThisMinute(effective, tick)) {
                due.add(u);
            }
        }
        LocalDate lastWeekStart = LocalDate.now().with(DayOfWeek.MONDAY).minusWeeks(1);
        runBatch("周报", due, u -> reportService.generateWeekly(u, lastWeekStart, false));
    }

    /**
     * CAP-28 FR-09：每日定时从 Git 导入工作条目（全局默认 18:00，早于日报生成的 18:30，
     * 当日条目先落库再进日报素材；个人 gitImportTime 可覆盖）。严格 opt-in：仅设置行显式
     * 打开 autoGitImport 的用户参与（不看订阅——它直接产生数据行，且署名未解析的仓库
     * 没有人工预览兜底，由 GitAutoImportService 整仓跳过）。
     */
    void gitImportBatch(ZonedDateTime tick, Map<String, WorklogUserSettingsEntity> byUser) {
        if (!props.isGitImportEnabled()) {
            return;
        }
        CronExpression global = parseGlobalCron("Git 导入", props.getGitImportCron());
        if (global == null) {
            return;
        }
        Set<String> due = byUser.values().stream()
                .filter(s -> Boolean.TRUE.equals(s.getAutoGitImport()))
                .filter(s -> WorklogSchedule.dueThisMinute(
                        WorklogSchedule.resolveDaily(s.getGitImportTime(), global), tick))
                .map(WorklogUserSettingsEntity::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        LocalDate today = LocalDate.now();
        runBatch("Git 导入", due, u -> gitAutoImport.importForDate(u, today));
    }

    // ---------------- 内部 ----------------

    /** 全局 cron 解析失败 = 配置错误：error 日志 + 跳过该任务本 tick（不拖垮其余任务）。 */
    private CronExpression parseGlobalCron(String label, String cron) {
        try {
            return CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            log.error("{} 全局 cron 非法（devmind.worklog 配置检查）: {}", label, cron);
            return null;
        }
    }

    private void runBatch(String label, Set<String> users, UserJob job) {
        if (users.isEmpty()) {
            return;
        }
        AtomicBoolean running = batchGuards.computeIfAbsent(label, k -> new AtomicBoolean());
        if (!running.compareAndSet(false, true)) {
            log.warn("{} 定时生成上一批未跑完，本次跳过", label);
            return;
        }
        Thread.ofVirtual().name("worklog-generate").start(() -> {
            try {
                for (String u : users) {
                    try {
                        job.run(u);
                    } catch (DevMindException e) {
                        // 节点离线/协议过低/同空间已有会话等 409：warn 跳过，不重试不中断其他用户；失败原因发 P0 通知
                        log.warn("{} 定时生成跳过: user={} err={}", label, u, e.getMessage());
                        notifyFailed(label, u, e.getMessage());
                    } catch (Exception e) {
                        log.warn("{} 定时生成失败: user={}", label, u, e);
                        notifyFailed(label, u, String.valueOf(e.getMessage()));
                    }
                }
            } finally {
                running.set(false);
            }
        });
    }

    /** 生成失败 → 领域事件（success=false 路由为 P0 通知，actor=本人收件）。 */
    private void notifyFailed(String label, String username, String reason) {
        try {
            eventPublisher.publish(SimpleDomainEvent.of("worklog.report.failed", null, null, username,
                    label + "生成失败: " + reason, null, null, Boolean.FALSE));
        } catch (Exception e) {
            log.warn("失败通知发布异常: {}", e.getMessage());
        }
    }

    /**
     * 调度覆盖用户：有订阅的用户 ∪ 有设置行的用户；设置行显式关掉对应开关的剔除
     * （无设置行默认开启）。
     */
    private Set<String> coveredUsers(boolean daily, Map<String, WorklogUserSettingsEntity> byUser) {
        Set<String> users = new LinkedHashSet<>(subRepo.findDistinctUserIds());
        byUser.forEach((userId, s) -> {
            boolean on = daily ? !Boolean.FALSE.equals(s.getAutoDaily())
                    : !Boolean.FALSE.equals(s.getAutoWeekly());
            if (on) {
                users.add(userId);
            } else {
                users.remove(userId);
            }
        });
        return users;
    }

    private interface UserJob {
        void run(String username);
    }
}
