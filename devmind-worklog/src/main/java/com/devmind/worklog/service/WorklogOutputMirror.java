package com.devmind.worklog.service;

import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.project.ProjectService;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionOutputEntity;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.service.SessionOutputService;
import com.devmind.worklog.model.DailyReportEntity;
import com.devmind.worklog.model.WeeklyReportEntity;
import com.devmind.worklog.repo.DailyReportRepository;
import com.devmind.worklog.repo.WeeklyReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAP-41 FR-06 成稿回传落镜像：消费 session.completed，把 worklog 生成会话回传到
 * session_outputs 的成稿（.devmind/output/daily-&lt;date&gt;.md / weekly-&lt;yyyy&gt;-W&lt;ww&gt;.md）
 * 幂等 upsert 进 daily_reports / weekly_reports（DB 镜像语义，事实源是 runner 工作区文件），
 * 再发 worklog.daily.generated / worklog.weekly.generated 事件（站内通知沿用既有路由）。
 *
 * <p>幂等与冲突口径：同 (user, date/week) 覆盖写 DRAFT；已 CONFIRMED 不覆盖（人工确认为大）。
 * 会话失败或成功但未回传成稿 → 发降级通知（worklog.report.failed），不落空镜像。</p>
 */
@Component
public class WorklogOutputMirror {

    private static final Logger log = LoggerFactory.getLogger(WorklogOutputMirror.class);

    static final Pattern DAILY_FILE = Pattern.compile("^daily-(\\d{4}-\\d{2}-\\d{2})\\.md$");
    static final Pattern WEEKLY_FILE = Pattern.compile("^weekly-(\\d{4})-W(\\d{2})\\.md$");

    private final SessionRepository sessionRepo;
    private final SessionOutputService outputService;
    private final ProjectService projectService;
    private final DailyReportRepository dailyRepo;
    private final WeeklyReportRepository weeklyRepo;
    private final DomainEventPublisher eventPublisher;

    public WorklogOutputMirror(SessionRepository sessionRepo, SessionOutputService outputService,
                               ProjectService projectService,
                               DailyReportRepository dailyRepo, WeeklyReportRepository weeklyRepo,
                               DomainEventPublisher eventPublisher) {
        this.sessionRepo = sessionRepo;
        this.outputService = outputService;
        this.projectService = projectService;
        this.dailyRepo = dailyRepo;
        this.weeklyRepo = weeklyRepo;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onSessionCompleted(SimpleDomainEvent event) {
        if (!"session.completed".equals(event.type()) || event.entityId() == null) {
            return;
        }
        SessionEntity s = sessionRepo.findById(event.entityId()).orElse(null);
        if (s == null) {
            return;
        }
        boolean daily = WorklogScenarios.DAILY.equals(s.getScenarioCode());
        boolean weekly = WorklogScenarios.WEEKLY.equals(s.getScenarioCode());
        if (!daily && !weekly) {
            return; // 非 worklog 生成会话（含空间内手工对话）：不消费产出
        }
        String owner = s.getProjectId() == null ? null : projectService.requireProject(s.getProjectId()).ownerId();
        String label = daily ? "日报" : "周报";
        if (owner == null || owner.isBlank()) {
            log.warn("worklog 生成会话无项目归属，跳过镜像: session={}", s.getId());
            return;
        }
        if (!Boolean.TRUE.equals(event.success())) {
            notifyFailed(label, owner, label + "生成会话执行失败，请到会话详情查看事件流（session " + s.getId() + "）");
            return;
        }
        List<SessionOutputEntity> outputs = outputService.list(s.getId());
        if (daily) {
            mirrorDaily(s, owner, outputs);
        } else {
            mirrorWeekly(s, owner, outputs);
        }
    }

    /** 日报镜像：找 daily-&lt;date&gt;.md 产出，upsert（DRAFT 覆盖 / CONFIRMED 跳过）。 */
    void mirrorDaily(SessionEntity s, String owner, List<SessionOutputEntity> outputs) {
        SessionOutputEntity out = find(outputs, DAILY_FILE);
        if (out == null) {
            notifyFailed("日报", owner,
                    "日报生成会话已完成，但未回传成稿（.devmind/output 缺 daily-<日期>.md），请到会话详情排查");
            return;
        }
        Matcher m = DAILY_FILE.matcher(out.getFileName());
        m.matches();
        LocalDate date = LocalDate.parse(m.group(1));
        DailyReportEntity e = dailyRepo.findByUserIdAndWorkDate(owner, date).orElse(null);
        if (e != null && DailyReportEntity.STATUS_CONFIRMED.equals(e.getStatus())) {
            log.info("日报已确认，镜像不覆盖: user={} date={} session={}", owner, date, s.getId());
            return;
        }
        if (e == null) {
            e = new DailyReportEntity();
            e.setUserId(owner);
            e.setWorkDate(date);
            e.setCreatedAt(Instant.now());
        }
        e.setContentMd(out.getContent());
        e.setStatus(DailyReportEntity.STATUS_DRAFT);
        e.setSessionId(s.getId());
        e.setUpdatedAt(Instant.now());
        DailyReportEntity saved = dailyRepo.save(e);
        log.info("日报镜像已落库: user={} date={} session={}", owner, date, s.getId());
        eventPublisher.publish(SimpleDomainEvent.of("worklog.daily.generated", null, null, owner,
                owner + " 的 " + date + " 日报草稿已生成", "DAILY_REPORT",
                String.valueOf(saved.getId()), null));
    }

    /** 周报镜像：weekly-&lt;yyyy&gt;-W&lt;ww&gt;.md（ISO 周 → 周一），分节解析 summary/nextPlan。 */
    void mirrorWeekly(SessionEntity s, String owner, List<SessionOutputEntity> outputs) {
        SessionOutputEntity out = find(outputs, WEEKLY_FILE);
        if (out == null) {
            notifyFailed("周报", owner,
                    "周报生成会话已完成，但未回传成稿（.devmind/output 缺 weekly-<yyyy>-W<ww>.md），请到会话详情排查");
            return;
        }
        Matcher m = WEEKLY_FILE.matcher(out.getFileName());
        m.matches();
        LocalDate weekStart = isoWeekMonday(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        WeeklyReportEntity e = weeklyRepo.findByUserIdAndWeekStart(owner, weekStart).orElse(null);
        if (e != null && WeeklyReportEntity.STATUS_CONFIRMED.equals(e.getStatus())) {
            log.info("周报已确认，镜像不覆盖: user={} weekStart={} session={}", owner, weekStart, s.getId());
            return;
        }
        String[] parts = ReportService.splitWeekly(out.getContent());
        if (e == null) {
            e = new WeeklyReportEntity();
            e.setUserId(owner);
            e.setWeekStart(weekStart);
            e.setCreatedAt(Instant.now());
        }
        e.setSummaryMd(parts[0]);
        e.setNextPlanMd(parts[1]);
        e.setStatus(WeeklyReportEntity.STATUS_DRAFT);
        e.setSessionId(s.getId());
        e.setUpdatedAt(Instant.now());
        WeeklyReportEntity saved = weeklyRepo.save(e);
        log.info("周报镜像已落库: user={} weekStart={} session={}", owner, weekStart, s.getId());
        eventPublisher.publish(SimpleDomainEvent.of("worklog.weekly.generated", null, null, owner,
                owner + " 的 " + weekStart + " 周周报草稿已生成", "WEEKLY_REPORT",
                String.valueOf(saved.getId()), null));
    }

    /** ISO 周 → 该周周一（周 reports 表以 weekStart=周一 为键，与既有口径一致）。 */
    static LocalDate isoWeekMonday(int year, int week) {
        return LocalDate.of(year, 1, 4)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                .with(DayOfWeek.MONDAY);
    }

    private static SessionOutputEntity find(List<SessionOutputEntity> outputs, Pattern pattern) {
        for (SessionOutputEntity o : outputs) {
            if (o.getFileName() != null && pattern.matcher(o.getFileName()).matches()) {
                return o;
            }
        }
        return null;
    }

    /** 降级通知：success=false 由统一监听器路由为 P0（actor=本人收件）。 */
    private void notifyFailed(String label, String owner, String reason) {
        try {
            eventPublisher.publish(SimpleDomainEvent.of("worklog.report.failed", null, null, owner,
                    label + "生成失败: " + reason, null, null, Boolean.FALSE));
        } catch (Exception e) {
            log.warn("失败通知发布异常: {}", e.getMessage());
        }
    }
}
