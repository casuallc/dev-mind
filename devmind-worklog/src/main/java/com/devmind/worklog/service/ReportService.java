package com.devmind.worklog.service;

import com.devmind.common.agent.OneShotAgentRunner;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.dto.DailyReportView;
import com.devmind.worklog.dto.GitCommitView;
import com.devmind.worklog.dto.UpdateDailyRequest;
import com.devmind.worklog.dto.UpdateWeeklyRequest;
import com.devmind.worklog.dto.WeeklyReportView;
import com.devmind.worklog.model.DailyReportEntity;
import com.devmind.worklog.model.WeeklyReportEntity;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.repo.DailyReportRepository;
import com.devmind.worklog.repo.WeeklyReportRepository;
import com.devmind.worklog.repo.WorklogEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * CAP-28 FR-05/06：AI 日报/周报生成与确认。
 *
 * <p>素材内联进 prompt（git 提交 + 手动/已导入条目），one-shot 会话以只读 plan 模式跑，
 * 不依赖文件系统。生成是同步阻塞的（最长 oneshot-timeout-seconds），调用方
 * （调度器/控制器）负责异步化。</p>
 *
 * <p>幂等：unique(user_id, work_date / week_start)，已存在且非 force 直接返回已有；
 * force 仅覆盖 DRAFT，CONFIRMED 拒绝（409）。</p>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** 周报正文分节标记（prompt 与解析共用） */
    static final String SUMMARY_HEADING = "## 上周总结";
    static final String PLAN_HEADING = "## 下周计划";

    private final DailyReportRepository dailyRepo;
    private final WeeklyReportRepository weeklyRepo;
    private final WorklogEntryRepository entryRepo;
    private final GitLogScanner gitScanner;
    private final WorklogProperties props;
    private final ObjectProvider<OneShotAgentRunner> oneShotRunner;
    private final DomainEventPublisher eventPublisher;

    public ReportService(DailyReportRepository dailyRepo,
                         WeeklyReportRepository weeklyRepo,
                         WorklogEntryRepository entryRepo,
                         GitLogScanner gitScanner,
                         WorklogProperties props,
                         ObjectProvider<OneShotAgentRunner> oneShotRunner,
                         DomainEventPublisher eventPublisher) {
        this.dailyRepo = dailyRepo;
        this.weeklyRepo = weeklyRepo;
        this.entryRepo = entryRepo;
        this.gitScanner = gitScanner;
        this.props = props;
        this.oneShotRunner = oneShotRunner;
        this.eventPublisher = eventPublisher;
    }

    // ---------------- 日报 ----------------

    public DailyReportView getDaily(String username, LocalDate date) {
        return dailyRepo.findByUserIdAndWorkDate(username, date).map(DailyReportView::of).orElse(null);
    }

    /**
     * 生成日报草稿（同步阻塞；调度与手动共用核心）。
     * 已存在：非 force 直接返回；force 仅覆盖 DRAFT。
     */
    public DailyReportView generateDaily(String username, LocalDate date, boolean force) {
        DailyReportEntity existing = dailyRepo.findByUserIdAndWorkDate(username, date).orElse(null);
        if (existing != null && !force) {
            return DailyReportView.of(existing);
        }
        if (existing != null && DailyReportEntity.STATUS_CONFIRMED.equals(existing.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "当日日报已确认，不可重新生成: " + date);
        }

        List<GitCommitView> commits = gitScanner.scan(username, date);
        List<WorklogEntryEntity> entries = entryRepo.findByUserIdAndWorkDateOrderByIdAsc(username, date);
        if (commits.isEmpty() && entries.isEmpty()) {
            log.info("日报无素材，跳过: user={} date={}", username, date);
            return existing != null ? DailyReportView.of(existing) : null;
        }

        OneShotAgentRunner.Result r = requireRunner().run(buildDailyPrompt(date, commits, entries),
                props.getOneshotTimeoutSeconds());

        DailyReportEntity e = existing != null ? existing : new DailyReportEntity();
        e.setUserId(username);
        e.setWorkDate(date);
        e.setContentMd(r.summary());
        e.setStatus(DailyReportEntity.STATUS_DRAFT);
        e.setSessionId(r.sessionId());
        e.setUpdatedAt(Instant.now());
        if (e.getId() == null) {
            e.setCreatedAt(Instant.now());
        }
        DailyReportEntity saved = dailyRepo.save(e);
        eventPublisher.publish(SimpleDomainEvent.of("worklog.daily.generated", null, null, username,
                username + " 的 " + date + " 日报草稿已生成", "DAILY_REPORT",
                String.valueOf(saved.getId()), null));
        return DailyReportView.of(saved);
    }

    @Transactional
    public DailyReportView updateDaily(Long id, String username, UpdateDailyRequest req) {
        DailyReportEntity e = dailyRepo.findByIdAndUserId(id, username)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "日报不存在: " + id));
        if (req.contentMd() != null) {
            e.setContentMd(req.contentMd());
        }
        if (req.status() != null) {
            requireReportStatus(req.status());
            e.setStatus(req.status());
        }
        e.setUpdatedAt(Instant.now());
        return DailyReportView.of(dailyRepo.save(e));
    }

    // ---------------- 周报 ----------------

    public WeeklyReportView getWeekly(String username, LocalDate weekStart) {
        return weeklyRepo.findByUserIdAndWeekStart(username, weekStart)
                .map(WeeklyReportView::of).orElse(null);
    }

    /**
     * 生成周报草稿（同步阻塞；调度与手动共用核心）。
     * 素材 = 该周（周一至周日）日报集合；无日报回退该周原始条目；皆无 → 跳过。
     */
    public WeeklyReportView generateWeekly(String username, LocalDate weekStart, boolean force) {
        WeeklyReportEntity existing = weeklyRepo.findByUserIdAndWeekStart(username, weekStart).orElse(null);
        if (existing != null && !force) {
            return WeeklyReportView.of(existing);
        }
        if (existing != null && WeeklyReportEntity.STATUS_CONFIRMED.equals(existing.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "该周周报已确认，不可重新生成: " + weekStart);
        }

        LocalDate weekEnd = weekStart.plusDays(6);
        List<DailyReportEntity> dailies = dailyRepo
                .findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(username, weekStart, weekEnd);
        List<WorklogEntryEntity> entries = List.of();
        if (dailies.isEmpty()) {
            entries = entryRepo.findByUserIdAndWorkDateBetweenOrderByWorkDateAscIdAsc(
                    username, weekStart, weekEnd);
        }
        if (dailies.isEmpty() && entries.isEmpty()) {
            log.info("周报无素材，跳过: user={} weekStart={}", username, weekStart);
            return existing != null ? WeeklyReportView.of(existing) : null;
        }

        OneShotAgentRunner.Result r = requireRunner().run(
                buildWeeklyPrompt(weekStart, dailies, entries), props.getOneshotTimeoutSeconds());

        String[] parts = splitWeekly(r.summary());
        WeeklyReportEntity e = existing != null ? existing : new WeeklyReportEntity();
        e.setUserId(username);
        e.setWeekStart(weekStart);
        e.setSummaryMd(parts[0]);
        e.setNextPlanMd(parts[1]);
        e.setStatus(WeeklyReportEntity.STATUS_DRAFT);
        e.setSessionId(r.sessionId());
        e.setUpdatedAt(Instant.now());
        if (e.getId() == null) {
            e.setCreatedAt(Instant.now());
        }
        WeeklyReportEntity saved = weeklyRepo.save(e);
        eventPublisher.publish(SimpleDomainEvent.of("worklog.weekly.generated", null, null, username,
                username + " 的 " + weekStart + " 周周报草稿已生成", "WEEKLY_REPORT",
                String.valueOf(saved.getId()), null));
        return WeeklyReportView.of(saved);
    }

    @Transactional
    public WeeklyReportView updateWeekly(Long id, String username, UpdateWeeklyRequest req) {
        WeeklyReportEntity e = weeklyRepo.findByIdAndUserId(id, username)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "周报不存在: " + id));
        if (req.summaryMd() != null) {
            e.setSummaryMd(req.summaryMd());
        }
        if (req.nextPlanMd() != null) {
            e.setNextPlanMd(req.nextPlanMd());
        }
        if (req.status() != null) {
            requireReportStatus(req.status());
            e.setStatus(req.status());
        }
        e.setUpdatedAt(Instant.now());
        return WeeklyReportView.of(weeklyRepo.save(e));
    }

    // ---------------- prompt ----------------

    private String buildDailyPrompt(LocalDate date, List<GitCommitView> commits,
                                    List<WorklogEntryEntity> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是我的工作日志助手。请根据以下 ").append(date).append(" 当天的工作素材，"
                + "生成一份简洁的中文当日工作日志（Markdown 正文，不要标题外的客套话）。\n"
                + "要求：按主题归并同类提交；每条一句话说明做了什么；文末附一行工时合计。\n"
                + "绝对不要读写任何文件，只输出 Markdown 日报正文。\n\n");
        if (!commits.isEmpty()) {
            sb.append("### git 提交记录\n");
            for (GitCommitView c : commits) {
                sb.append("- [").append(c.repoName()).append("] ").append(c.subject())
                        .append("（").append(c.sha(), 0, Math.min(8, c.sha().length())).append("）\n");
            }
            sb.append('\n');
        }
        if (!entries.isEmpty()) {
            sb.append("### 已记录的工作条目\n");
            for (WorklogEntryEntity e : entries) {
                sb.append("- [").append(e.getEntryType()).append("] ").append(e.getTitle())
                        .append("（").append(e.getMinutes() == null ? 0 : e.getMinutes()).append(" 分钟）");
                if (e.getContent() != null && !e.getContent().isBlank()) {
                    sb.append("：").append(e.getContent());
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private String buildWeeklyPrompt(LocalDate weekStart, List<DailyReportEntity> dailies,
                                     List<WorklogEntryEntity> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是我的周报助手。请根据 ").append(weekStart).append(" 这一周的工作记录，生成中文周报。\n"
                + "输出必须恰好包含以下两个小节标题，各小节下是 Markdown 列表：\n"
                + SUMMARY_HEADING + "\n（按主题归并，每条一句话，突出成果与进展）\n"
                + PLAN_HEADING + "\n（根据本周未完成/进行中的线索列出下周计划草稿，每条一句话）\n"
                + "绝对不要读写任何文件，只输出周报正文。\n\n");
        if (!dailies.isEmpty()) {
            for (DailyReportEntity d : dailies) {
                sb.append("### ").append(d.getWorkDate()).append('\n')
                        .append(d.getContentMd() == null ? "" : d.getContentMd()).append("\n\n");
            }
        } else {
            sb.append("### 本周工作条目\n");
            for (WorklogEntryEntity e : entries) {
                sb.append("- ").append(e.getWorkDate()).append(" [").append(e.getEntryType())
                        .append("] ").append(e.getTitle()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 按分节标题切周报正文；解析失败时全部进 summary，plan 留空（前端可手改）。 */
    static String[] splitWeekly(String body) {
        if (body == null) {
            return new String[]{"", ""};
        }
        int ps = body.indexOf(PLAN_HEADING);
        if (ps < 0) {
            return new String[]{body.strip(), ""};
        }
        String summary = body.substring(0, ps).replace(SUMMARY_HEADING, "").strip();
        String plan = body.substring(ps + PLAN_HEADING.length()).strip();
        return new String[]{summary, plan};
    }

    private void requireReportStatus(String status) {
        if (!DailyReportEntity.STATUS_DRAFT.equals(status)
                && !DailyReportEntity.STATUS_CONFIRMED.equals(status)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "status 仅支持 DRAFT/CONFIRMED");
        }
    }

    private OneShotAgentRunner requireRunner() {
        OneShotAgentRunner runner = oneShotRunner.getIfAvailable();
        if (runner == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "会话模块未装配，AI 报告生成不可用（条目/仓库功能不受影响）");
        }
        return runner;
    }
}
