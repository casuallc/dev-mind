package com.devmind.worklog.service;

import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.project.model.ProjectEntity;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionOutputEntity;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.service.SessionOutputService;
import com.devmind.worklog.dto.WorklogSyncResult;
import com.devmind.worklog.model.DailyReportEntity;
import com.devmind.worklog.model.WeeklyReportEntity;
import com.devmind.worklog.repo.DailyReportRepository;
import com.devmind.worklog.repo.WeeklyReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAP-41 FR-06 成稿回传：消费 session.completed，把工作日志空间会话回传到
 * session_outputs 的成稿（.devmind/output/daily-&lt;date&gt;.md / weekly-&lt;yyyy&gt;-W&lt;ww&gt;.md）
 * 落库（CAP-89：daily_reports/weekly_reports 重新成为事实源），再发
 * worklog.daily.generated / worklog.weekly.generated 事件（refId = 日期/周一开始日期字符串；
 * 站内通知沿用既有路由）。
 *
 * <p>消费范围 = WORKLOG 项目的所有会话：内置生成场景（worklog-daily/weekly）失败或缺回传
 * 发降级通知；空间内手工对话（无场景）有成稿静默落库、缺文件不告警。另提供
 * {@link #syncSession} 手动同步（前端对话页「推送工作日志」）。</p>
 *
 * <p>落库口径（CAP-89 定稿「自动不覆盖 + force 显式」）：场景生成（{@link #mirrorDaily}/
 * {@link #mirrorWeekly}）无条件落库（upsert）——generate 非 force 已在行存在时跳过，force
 * 是显式覆盖意图。手工对话与「推送工作日志」走的 {@link #mirrorAll} 逐份过闸：库中无行
 * → 写入；内容一致 → UNCHANGED 静默；库中已有不同内容 → 跳过进 skipped（「库中已有报告，
 * 未覆盖」，要覆盖走日志页 force 重新生成——DB 无手改标记可考，一律按已存在保护）。</p>
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
        if (s == null || s.getProjectId() == null) {
            return;
        }
        var project = projectService.requireProject(s.getProjectId());
        if (!ProjectEntity.KIND_WORKLOG.equals(project.kind())) {
            return; // 只消费工作日志空间的会话
        }
        String owner = project.ownerId();
        if (owner == null || owner.isBlank()) {
            log.warn("worklog 会话项目无归属用户，跳过落库: session={}", s.getId());
            return;
        }
        boolean daily = WorklogScenarios.DAILY.equals(s.getScenarioCode());
        boolean weekly = WorklogScenarios.WEEKLY.equals(s.getScenarioCode());
        if (daily || weekly) {
            String label = daily ? "日报" : "周报";
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
            return;
        }
        // 空间内手工对话（无内置场景）：有成稿静默落库（exit 已自动回传），缺文件不告警
        WorklogSyncResult r = mirrorAll(s, owner, outputService.list(s.getId()));
        if (!r.mirrored().isEmpty()) {
            log.info("手工对话成稿已落库: session={} mirrored={}", s.getId(), r.mirrored());
        }
    }

    /**
     * 手动同步（前端对话页「推送工作日志」）：校验会话属于 actor 的日志空间后落库其产出。
     * 调用方通常先触发 runner 即时回传（outputs/collect）再调本方法。
     */
    public WorklogSyncResult syncSession(String sessionId, String actor) {
        SessionEntity s = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));
        var workspace = projectService.findWorklogByOwner(actor)
                .orElseThrow(() -> new DevMindException(ErrorCode.CONFLICT, "工作日志空间尚未初始化"));
        if (!workspace.getId().equals(s.getProjectId())) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "该会话不属于你的工作日志空间");
        }
        return mirrorAll(s, actor, outputService.list(sessionId));
    }

    /** 镜像前置闸（{@link #mirrorAll} 专用）判定结果 */
    private enum MirrorGuard { WRITE, UNCHANGED, EXISTING }

    /**
     * 手工对话/手动同步镜像——runner 侧产出回传已按会话过滤（共享草稿区的历史旧稿不进
     * session_outputs），这里对 outputs 里每一份命中的日报/周报逐个过闸：库中无行 → 写入；
     * 内容一致 → 静默；库中已有不同内容 → 跳过进 skipped（「自动不覆盖」，要覆盖走 force）。
     * 场景生成不走本类镜像（见类注释）。
     */
    public WorklogSyncResult mirrorAll(SessionEntity s, String owner, List<SessionOutputEntity> outputs) {
        List<String> mirrored = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (SessionOutputEntity o : outputs) {
            String fileName = o.getFileName();
            if (fileName == null) {
                continue;
            }
            Matcher dm = DAILY_FILE.matcher(fileName);
            Matcher wm = WEEKLY_FILE.matcher(fileName);
            if (dm.matches()) {
                LocalDate date = LocalDate.parse(dm.group(1));
                switch (dailyGuard(owner, date, o.getContent())) {
                    case UNCHANGED -> { }
                    case EXISTING -> skipped.add("日报 " + date + "（库中已有报告，未覆盖）");
                    case WRITE -> mirrored.add(writeDaily(s, owner, date, o.getContent()));
                }
            } else if (wm.matches()) {
                LocalDate weekStart = WorklogWeeks.weekStartOf(
                        Integer.parseInt(wm.group(1)), Integer.parseInt(wm.group(2)));
                switch (weeklyGuard(owner, weekStart, o.getContent())) {
                    case UNCHANGED -> { }
                    case EXISTING -> skipped.add("周报 " + weekStart + " 周（库中已有报告，未覆盖）");
                    case WRITE -> mirrored.add(writeWeekly(s, owner, weekStart, o.getContent()));
                }
            }
        }
        return new WorklogSyncResult(mirrored, skipped);
    }

    /** 日报前置闸：无行 → WRITE；内容一致 → UNCHANGED；已有不同内容 → EXISTING（不覆盖）。 */
    private MirrorGuard dailyGuard(String owner, LocalDate date, String content) {
        return dailyRepo.findByUserIdAndWorkDate(owner, date)
                .map(e -> Objects.equals(e.getContentMd(), content)
                        ? MirrorGuard.UNCHANGED : MirrorGuard.EXISTING)
                .orElse(MirrorGuard.WRITE);
    }

    /** 周报前置闸：同上（按分节拆分后与两列比对）。 */
    private MirrorGuard weeklyGuard(String owner, LocalDate weekStart, String content) {
        return weeklyRepo.findByUserIdAndWeekStart(owner, weekStart)
                .map(e -> {
                    String[] parts = ReportService.splitWeekly(content);
                    return Objects.equals(e.getSummaryMd(), parts[0])
                            && Objects.equals(e.getNextPlanMd(), parts[1])
                            ? MirrorGuard.UNCHANGED : MirrorGuard.EXISTING;
                })
                .orElse(MirrorGuard.WRITE);
    }

    /** 日报落库（生成场景 exit 路径）：缺回传发降级通知，命中走 {@link #writeDaily}。 */
    void mirrorDaily(SessionEntity s, String owner, List<SessionOutputEntity> outputs) {
        SessionOutputEntity out = find(outputs, DAILY_FILE);
        if (out == null) {
            notifyFailed("日报", owner,
                    "日报生成会话已完成，但未回传成稿（.devmind/output 缺 daily-<日期>.md），请到会话详情排查");
            return;
        }
        LocalDate date = LocalDate.parse(DAILY_FILE.matcher(out.getFileName()).replaceFirst("$1"));
        writeDaily(s, owner, date, out.getContent());
    }

    /** 周报落库（生成场景 exit 路径）：缺回传发降级通知，命中走 {@link #writeWeekly}。 */
    void mirrorWeekly(SessionEntity s, String owner, List<SessionOutputEntity> outputs) {
        SessionOutputEntity out = find(outputs, WEEKLY_FILE);
        if (out == null) {
            notifyFailed("周报", owner,
                    "周报生成会话已完成，但未回传成稿（.devmind/output 缺 weekly-<yyyy>-W<ww>.md），请到会话详情排查");
            return;
        }
        Matcher m = WEEKLY_FILE.matcher(out.getFileName());
        m.matches();
        LocalDate weekStart = WorklogWeeks.weekStartOf(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        writeWeekly(s, owner, weekStart, out.getContent());
    }

    /** 日报落库（upsert + 会话追溯 sessionId），返回 "日报 <date>"。 */
    private String writeDaily(SessionEntity s, String owner, LocalDate date, String content) {
        DailyReportEntity e = dailyRepo.findByUserIdAndWorkDate(owner, date)
                .orElseGet(() -> {
                    DailyReportEntity n = new DailyReportEntity();
                    n.setUserId(owner);
                    n.setWorkDate(date);
                    n.setCreatedAt(now());
                    return n;
                });
        e.setContentMd(content == null ? "" : content);
        e.setSessionId(s.getId());
        e.setUpdatedAt(now());
        dailyRepo.save(e);
        log.info("日报已落库: user={} date={} session={}", owner, date, s.getId());
        eventPublisher.publish(SimpleDomainEvent.of("worklog.daily.generated", null, null, owner,
                owner + " 的 " + date + " 日报已生成", "DAILY_REPORT", date.toString(), null));
        return "日报 " + date;
    }

    /** 周报落库（全文按分节标记拆两列 upsert；sessionId 追溯）。 */
    private String writeWeekly(SessionEntity s, String owner, LocalDate weekStart, String content) {
        String[] parts = ReportService.splitWeekly(content);
        WeeklyReportEntity e = weeklyRepo.findByUserIdAndWeekStart(owner, weekStart)
                .orElseGet(() -> {
                    WeeklyReportEntity n = new WeeklyReportEntity();
                    n.setUserId(owner);
                    n.setWeekStart(weekStart);
                    n.setCreatedAt(now());
                    return n;
                });
        e.setSummaryMd(parts[0]);
        e.setNextPlanMd(parts[1]);
        e.setSessionId(s.getId());
        e.setUpdatedAt(now());
        weeklyRepo.save(e);
        log.info("周报已落库: user={} weekStart={} session={}", owner, weekStart, s.getId());
        eventPublisher.publish(SimpleDomainEvent.of("worklog.weekly.generated", null, null, owner,
                owner + " 的 " + weekStart + " 周周报已生成", "WEEKLY_REPORT", weekStart.toString(), null));
        return "周报 " + weekStart + " 周";
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
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
