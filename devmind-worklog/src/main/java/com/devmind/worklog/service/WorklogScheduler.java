package com.devmind.worklog.service;

import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.repo.WorklogRepoSubscriptionRepository;
import com.devmind.worklog.repo.WorklogUserSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CAP-28 FR-05/06 定时调度（照 JiraSyncService 模板）：cron 配置化 + 全局 AtomicBoolean 防重入。
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

    private final AtomicBoolean running = new AtomicBoolean();

    private final ReportService reportService;
    private final WorklogUserSettingsRepository settingsRepo;
    private final WorklogRepoSubscriptionRepository subRepo;
    private final WorklogProperties props;
    private final DomainEventPublisher eventPublisher;

    public WorklogScheduler(ReportService reportService,
                            WorklogUserSettingsRepository settingsRepo,
                            WorklogRepoSubscriptionRepository subRepo,
                            WorklogProperties props,
                            DomainEventPublisher eventPublisher) {
        this.reportService = reportService;
        this.settingsRepo = settingsRepo;
        this.subRepo = subRepo;
        this.props = props;
        this.eventPublisher = eventPublisher;
    }

    /** 每日生成日报草稿（默认 18:30）。 */
    @Scheduled(cron = "${devmind.worklog.daily-cron:0 30 18 * * *}")
    public void dailyTick() {
        if (!props.isDailyEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now();
        runBatch("日报", coveredUsers(true), u -> reportService.generateDaily(u, today, false));
    }

    /** 每周生成上周周报草稿（默认周一 09:00）。 */
    @Scheduled(cron = "${devmind.worklog.weekly-cron:0 0 9 * * MON}")
    public void weeklyTick() {
        if (!props.isWeeklyEnabled()) {
            return;
        }
        LocalDate lastWeekStart = LocalDate.now().with(DayOfWeek.MONDAY).minusWeeks(1);
        runBatch("周报", coveredUsers(false), u -> reportService.generateWeekly(u, lastWeekStart, false));
    }

    // ---------------- 内部 ----------------

    private void runBatch(String label, Set<String> users, UserJob job) {
        if (users.isEmpty()) {
            return;
        }
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
    private Set<String> coveredUsers(boolean daily) {
        Set<String> users = new LinkedHashSet<>(subRepo.findDistinctUserIds());
        settingsRepo.findAll().forEach(s -> {
            boolean on = daily ? !Boolean.FALSE.equals(s.getAutoDaily())
                    : !Boolean.FALSE.equals(s.getAutoWeekly());
            if (on) {
                users.add(s.getUserId());
            } else {
                users.remove(s.getUserId());
            }
        });
        return users;
    }

    private interface UserJob {
        void run(String username);
    }
}
