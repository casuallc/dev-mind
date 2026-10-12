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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAP-41 FR-06 成稿回传：消费 session.completed，把工作日志空间会话回传到
 * session_outputs 的成稿（.devmind/output/daily-&lt;date&gt;.md / weekly-&lt;yyyy&gt;-W&lt;ww&gt;.md）
 * 写进服务端 git 仓（CAP-74：仓是唯一事实源，{@link WorklogRepoService} 单写者收口，
 * 同内容幂等零 commit），再发 worklog.daily.generated / worklog.weekly.generated 事件
 * （refId = 日期/周一开始日期字符串；站内通知沿用既有路由）。
 *
 * <p>消费范围 = WORKLOG 项目的所有会话：内置生成场景（worklog-daily/weekly）失败或缺回传
 * 发降级通知；空间内手工对话（无场景）有成稿静默写仓、缺文件不告警。另提供
 * {@link #syncSession} 手动同步（前端对话页「推送工作日志」）。</p>
 *
 * <p>CAP-74 覆盖口径：DRAFT/CONFIRMED 状态机已删除，会话成稿与手动编辑同权覆盖写仓
 * （防误覆盖靠 git 历史回溯 + 生成侧「非 force 已存在跳过」），skipped 恒空。</p>
 */
@Component
public class WorklogOutputMirror {

    private static final Logger log = LoggerFactory.getLogger(WorklogOutputMirror.class);

    static final Pattern DAILY_FILE = Pattern.compile("^daily-(\\d{4}-\\d{2}-\\d{2})\\.md$");
    static final Pattern WEEKLY_FILE = Pattern.compile("^weekly-(\\d{4})-W(\\d{2})\\.md$");

    private final SessionRepository sessionRepo;
    private final SessionOutputService outputService;
    private final ProjectService projectService;
    private final WorklogRepoService repoService;
    private final DomainEventPublisher eventPublisher;

    public WorklogOutputMirror(SessionRepository sessionRepo, SessionOutputService outputService,
                               ProjectService projectService, WorklogRepoService repoService,
                               DomainEventPublisher eventPublisher) {
        this.sessionRepo = sessionRepo;
        this.outputService = outputService;
        this.projectService = projectService;
        this.repoService = repoService;
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
            log.warn("worklog 会话项目无归属用户，跳过写仓: session={}", s.getId());
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
        // 空间内手工对话（无内置场景）：有成稿静默写仓（exit 已自动回传），缺文件不告警
        WorklogSyncResult r = mirrorAll(s, owner, outputService.list(s.getId()));
        if (!r.mirrored().isEmpty()) {
            log.info("手工对话成稿已写仓: session={} mirrored={}", s.getId(), r.mirrored());
        }
    }

    /**
     * 手动同步（前端对话页「推送工作日志」）：校验会话属于 actor 的日志空间后写仓其产出。
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

    /** 把产出列表里全部命中的日报/周志成稿写仓（幂等：同内容零 commit；skipped 恒空）。 */
    public WorklogSyncResult mirrorAll(SessionEntity s, String owner, List<SessionOutputEntity> outputs) {
        List<String> mirrored = new ArrayList<>();
        for (SessionOutputEntity o : outputs) {
            String fileName = o.getFileName();
            if (fileName == null) {
                continue;
            }
            if (DAILY_FILE.matcher(fileName).matches()) {
                mirrored.add(writeDaily(s, owner, o));
            } else if (WEEKLY_FILE.matcher(fileName).matches()) {
                mirrored.add(writeWeekly(s, owner, o));
            }
        }
        return new WorklogSyncResult(mirrored, List.of());
    }

    /** 日报写仓（生成场景 exit 路径）：缺回传发降级通知，命中走 {@link #writeDaily}。 */
    void mirrorDaily(SessionEntity s, String owner, List<SessionOutputEntity> outputs) {
        SessionOutputEntity out = find(outputs, DAILY_FILE);
        if (out == null) {
            notifyFailed("日报", owner,
                    "日报生成会话已完成，但未回传成稿（.devmind/output 缺 daily-<日期>.md），请到会话详情排查");
            return;
        }
        writeDaily(s, owner, out);
    }

    /** 周报写仓（生成场景 exit 路径）：缺回传发降级通知，命中走 {@link #writeWeekly}。 */
    void mirrorWeekly(SessionEntity s, String owner, List<SessionOutputEntity> outputs) {
        SessionOutputEntity out = find(outputs, WEEKLY_FILE);
        if (out == null) {
            notifyFailed("周报", owner,
                    "周报生成会话已完成，但未回传成稿（.devmind/output 缺 weekly-<yyyy>-W<ww>.md），请到会话详情排查");
            return;
        }
        writeWeekly(s, owner, out);
    }

    /** 日报写仓（AI 来源 + 会话追溯 trailer；同内容零 commit），返回 "日报 <date>"。 */
    private String writeDaily(SessionEntity s, String owner, SessionOutputEntity out) {
        LocalDate date = LocalDate.parse(DAILY_FILE.matcher(out.getFileName()).replaceFirst("$1"));
        repoService.writeReport(owner, WorklogRepoService.ReportKind.DAILY, date,
                out.getContent(), WorklogRepoService.CommitSource.AI, s.getId(), null);
        log.info("日报已写仓: user={} date={} session={}", owner, date, s.getId());
        eventPublisher.publish(SimpleDomainEvent.of("worklog.daily.generated", null, null, owner,
                owner + " 的 " + date + " 日报已生成", "DAILY_REPORT", date.toString(), null));
        return "日报 " + date;
    }

    /** 周报写仓（文件全文原样写仓，分节拆分只是视图层行为；source=AI + 会话追溯）。 */
    private String writeWeekly(SessionEntity s, String owner, SessionOutputEntity out) {
        Matcher m = WEEKLY_FILE.matcher(out.getFileName());
        m.matches();
        LocalDate weekStart = WorklogRepoService.weekStartOf(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        repoService.writeReport(owner, WorklogRepoService.ReportKind.WEEKLY, weekStart,
                out.getContent(), WorklogRepoService.CommitSource.AI, s.getId(), null);
        log.info("周报已写仓: user={} weekStart={} session={}", owner, weekStart, s.getId());
        eventPublisher.publish(SimpleDomainEvent.of("worklog.weekly.generated", null, null, owner,
                owner + " 的 " + weekStart + " 周周报已生成", "WEEKLY_REPORT", weekStart.toString(), null));
        return "周报 " + weekStart + " 周";
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
