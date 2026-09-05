package com.devmind.worklog.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
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
 * CAP-28 FR-05/06 定时调度（照 JiraSyncService 模板）：
 * cron 配置化 + 全局 AtomicBoolean 防重入 + 手动/定时共用核心（{@link ReportService}）。
 *
 * <p>生成是同步阻塞长任务（one-shot 会话最长 oneshot-timeout-seconds），故统一提交到
 * 虚拟线程执行，调度线程即刻返回；running 旗标跨整个异步执行持有，重复触发（含手动）
 * 直接拒绝。调度方法本身禁 @Transactional（红线：异步线程看不到未提交行）——
 * 报告逐用户逐份 save 即时提交。</p>
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

    public WorklogScheduler(ReportService reportService,
                            WorklogUserSettingsRepository settingsRepo,
                            WorklogRepoSubscriptionRepository subRepo,
                            WorklogProperties props) {
        this.reportService = reportService;
        this.settingsRepo = settingsRepo;
        this.subRepo = subRepo;
        this.props = props;
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

    /** 手动触发（控制器入口）。已有任务在跑返回 false（→ 409）。 */
    public boolean submitDaily(String username, LocalDate date, boolean force) {
        return runAsync(() -> reportService.generateDaily(username, date, force));
    }

    public boolean submitWeekly(String username, LocalDate weekStart, boolean force) {
        return runAsync(() -> reportService.generateWeekly(username, weekStart, force));
    }

    public boolean isRunning() {
        return running.get();
    }

    // ---------------- 内部 ----------------

    private void runBatch(String label, Set<String> users, UserJob job) {
        if (users.isEmpty()) {
            return;
        }
        runAsync(() -> {
            for (String u : users) {
                try {
                    job.run(u);
                } catch (DevMindException e) {
                    // 并发打满（TOO_MANY_SESSIONS）/素材为空等：warn 跳过，不重试不中断其他用户
                    log.warn("{} 定时生成跳过: user={} err={}", label, u, e.getMessage());
                } catch (Exception e) {
                    log.warn("{} 定时生成失败: user={}", label, u, e);
                }
            }
        });
    }

    private boolean runAsync(Runnable r) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        Thread.ofVirtual().name("worklog-generate").start(() -> {
            try {
                r.run();
            } catch (DevMindException e) {
                log.warn("报告生成失败: {}", e.getMessage());
            } catch (Exception e) {
                log.warn("报告生成异常", e);
            } finally {
                running.set(false);
            }
        });
        return true;
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
