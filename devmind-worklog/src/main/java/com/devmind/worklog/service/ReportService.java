package com.devmind.worklog.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.SessionView;
import com.devmind.session.service.SessionManagerService;
import com.devmind.worklog.dto.DailyReportView;
import com.devmind.worklog.dto.GitCommitView;
import com.devmind.worklog.dto.ReportLaunch;
import com.devmind.worklog.dto.UpdateDailyRequest;
import com.devmind.worklog.dto.UpdateWeeklyRequest;
import com.devmind.worklog.dto.WeeklyReportView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * CAP-28 FR-05/06：日报/周报查询、手动空白稿、编辑保存；CAP-41 FR-03：AI 生成改真实会话；
 * CAP-74：读改写全部收口到服务端 git 仓（{@link WorklogRepoService} 单写者），
 * DRAFT/CONFIRMED 状态机删除——保存即生效，防自动覆盖靠「非 force 已存在跳过 + git 历史可回溯」。
 *
 * <p>生成 = 在本人 WORKLOG 项目下创建 kind=worklog 会话（场景 worklog-daily / worklog-weekly，
 * prompt = 渲染后的格式模板 + 素材快照），claude 在 runner 草稿区把成稿写到
 * .devmind/output/ 回传；写仓由 {@link WorklogOutputMirror} 在会话结束时消费回传完成。</p>
 *
 * <p>幂等：目标文件已存在且非 force 直接复用不起会话；force 显式覆盖（旧版本 git log 可找回）。</p>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** 周报正文分节标记（视图拆分/重组与默认模板共用，改动需同步） */
    static final String SUMMARY_HEADING = "## 上周总结";
    static final String PLAN_HEADING = "## 下周计划";

    /** 周报视图/周报素材读全文的上限（一人一周一文件，量级小；截断只是兜底防爆） */
    private static final int FULL_READ_CHARS = 500_000;

    private final WorklogEntryService entryService;
    private final GitLogScanner gitScanner;
    private final WorklogSettingsService settingsService;
    private final WorklogWorkspaceService workspaceService;
    private final SessionManagerService sessionManager;
    private final WorklogRepoService repoService;

    public ReportService(WorklogEntryService entryService,
                         GitLogScanner gitScanner,
                         WorklogSettingsService settingsService,
                         WorklogWorkspaceService workspaceService,
                         SessionManagerService sessionManager,
                         WorklogRepoService repoService) {
        this.entryService = entryService;
        this.gitScanner = gitScanner;
        this.settingsService = settingsService;
        this.workspaceService = workspaceService;
        this.sessionManager = sessionManager;
        this.repoService = repoService;
    }

    // ---------------- 日报 ----------------

    public DailyReportView getDaily(String username, LocalDate date) {
        return repoService.readReport(username, WorklogRepoService.ReportKind.DAILY, date)
                .map(c -> new DailyReportView(true, date, c.contentMd(), c.sessionId(),
                        c.createdAt(), c.updatedAt()))
                .orElse(null);
    }

    /** 最近 days 天内有报告的日报（新日期在前，contentMd 为前 1000 字预览），前端「最近两周」列表用。 */
    public List<DailyReportView> recentDaily(String username, int days) {
        LocalDate to = LocalDate.now();
        return repoService.listReports(username, WorklogRepoService.ReportKind.DAILY,
                        to.minusDays(days - 1L), to, 1000)
                .stream()
                .map(s -> new DailyReportView(true, s.date(), s.preview(), s.sessionId(),
                        s.createdAt(), s.updatedAt()))
                .toList();
    }

    /** 某周（weekStart=周一）7 天内有报告的日报（日期升序，contentMd 为预览），日报周视图的周日选择条用。 */
    public List<DailyReportView> weekDaily(String username, LocalDate weekStart) {
        return repoService.listReports(username, WorklogRepoService.ReportKind.DAILY,
                        weekStart, weekStart.plusDays(6), 1000)
                .stream()
                .sorted(Comparator.comparing(WorklogRepoService.ReportSummary::date))
                .map(s -> new DailyReportView(true, s.date(), s.preview(), s.sessionId(),
                        s.createdAt(), s.updatedAt()))
                .toList();
    }

    /**
     * 手动生成前同步预检（控制器调用）：已存在且非 force → 直接复用无需素材；
     * 无素材 → 400。让「点生成却永远没有结果」的场景立即报错，而不是起会话后空跑。
     */
    public void precheckDaily(String username, LocalDate date, boolean force) {
        boolean exists = repoService.reportExists(username, WorklogRepoService.ReportKind.DAILY, date);
        if (exists && !force) {
            return; // 已有报告且非 force：generate 直接复用，无需素材
        }
        if (gitScanner.scan(username, date).isEmpty()
                && entryService.listDayEntries(username, date).isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    date + " 无工作素材（无条目且无 git 提交），日报未生成");
        }
    }

    /**
     * 生成日报：创建 worklog 会话（场景 worklog-daily），立即返回会话 id；
     * 成稿随会话结束回传写仓（{@link WorklogOutputMirror}）。
     * 已存在：非 force 复用（reused=true，不起会话）；force 显式覆盖（旧稿 git log 可找回）。
     */
    public ReportLaunch generateDaily(String username, LocalDate date, boolean force) {
        boolean exists = repoService.reportExists(username, WorklogRepoService.ReportKind.DAILY, date);
        if (exists && !force) {
            return new ReportLaunch(null, true);
        }

        List<GitCommitView> commits = gitScanner.scan(username, date);
        List<WorklogEntryService.DatedEntry> entries = entryService.listDayEntries(username, date)
                .stream().map(e -> new WorklogEntryService.DatedEntry(date, e)).toList();
        if (commits.isEmpty() && entries.isEmpty()) {
            log.info("日报无素材，跳过: user={} date={}", username, date);
            return new ReportLaunch(null, exists);
        }

        String template = WorklogTemplates.orDefault(
                settingsService.templateOf(username, true), WorklogTemplates.DEFAULT_DAILY);
        return launch(username, WorklogScenarios.DAILY, ReportPrompts.daily(date, commits, entries, template));
    }

    /** 手动创建空白日报（不经 AI、不要求素材）。幂等：已存在直接返回，不覆盖已有内容。 */
    public DailyReportView createDaily(String username, LocalDate date) {
        if (!repoService.reportExists(username, WorklogRepoService.ReportKind.DAILY, date)) {
            repoService.writeReport(username, WorklogRepoService.ReportKind.DAILY, date, "",
                    WorklogRepoService.CommitSource.MANUAL, null, null);
        }
        return getDaily(username, date);
    }

    /**
     * 编辑保存日报（CAP-74：PUT 按日期寻址，保存即生效写仓 + commit）。
     * status 字段已随状态机删除——传了即 400（防旧前端静默误以为确认生效）。
     */
    public DailyReportView updateDaily(String username, LocalDate date, UpdateDailyRequest req) {
        rejectStatus(req.status());
        if (!repoService.reportExists(username, WorklogRepoService.ReportKind.DAILY, date)) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "日报不存在: " + date);
        }
        if (req.contentMd() != null) {
            repoService.writeReport(username, WorklogRepoService.ReportKind.DAILY, date,
                    req.contentMd(), WorklogRepoService.CommitSource.MANUAL, null, null);
        }
        return getDaily(username, date);
    }

    // ---------------- 周报 ----------------

    public WeeklyReportView getWeekly(String username, LocalDate weekStart) {
        return repoService.readReport(username, WorklogRepoService.ReportKind.WEEKLY, weekStart)
                .map(c -> toWeeklyView(weekStart, c.contentMd(), c.sessionId(), c.createdAt(), c.updatedAt()))
                .orElse(null);
    }

    /** 最近 weeks 个周（含本周，周一为界）有报告的周报（新周在前），前端「最近一个月」列表用。 */
    public List<WeeklyReportView> recentWeekly(String username, int weeks) {
        LocalDate thisMonday = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        return repoService.listReports(username, WorklogRepoService.ReportKind.WEEKLY,
                        thisMonday.minusWeeks(weeks - 1L), thisMonday, FULL_READ_CHARS)
                .stream()
                .map(s -> toWeeklyView(s.date(), s.preview(), s.sessionId(), s.createdAt(), s.updatedAt()))
                .toList();
    }

    /** 周报手动生成预检：同 {@link #precheckDaily}，素材 = 该周日报或原始条目。 */
    public void precheckWeekly(String username, LocalDate weekStart, boolean force) {
        boolean exists = repoService.reportExists(username, WorklogRepoService.ReportKind.WEEKLY, weekStart);
        if (exists && !force) {
            return;
        }
        LocalDate weekEnd = weekStart.plusDays(6);
        boolean noDaily = repoService.listReports(username, WorklogRepoService.ReportKind.DAILY,
                weekStart, weekEnd, 1).isEmpty();
        boolean noEntry = entryService.listRangeEntries(username, weekStart, weekEnd).isEmpty();
        if (noDaily && noEntry) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    weekStart + " 周无工作素材（无日报且无条目），周报未生成");
        }
    }

    /**
     * 生成周报：创建 worklog 会话（场景 worklog-weekly）。素材 = 该周日报全文（CAP-74：
     * 草稿区无历史文件可回读，prompt 携带的日报全文是唯一素材口径；无日报回退该周原始条目）
     * + 该周 git 提交。
     */
    public ReportLaunch generateWeekly(String username, LocalDate weekStart, boolean force) {
        boolean exists = repoService.reportExists(username, WorklogRepoService.ReportKind.WEEKLY, weekStart);
        if (exists && !force) {
            return new ReportLaunch(null, true);
        }

        LocalDate weekEnd = weekStart.plusDays(6);
        List<ReportPrompts.DailyMaterial> dailies = repoService.listReports(username,
                        WorklogRepoService.ReportKind.DAILY, weekStart, weekEnd, FULL_READ_CHARS)
                .stream()
                .sorted(Comparator.comparing(WorklogRepoService.ReportSummary::date))
                .map(s -> new ReportPrompts.DailyMaterial(s.date(), s.preview()))
                .toList();
        List<WorklogEntryService.DatedEntry> entries = List.of();
        if (dailies.isEmpty()) {
            entries = entryService.listRangeEntries(username, weekStart, weekEnd);
        }
        if (dailies.isEmpty() && entries.isEmpty()) {
            log.info("周报无素材，跳过: user={} weekStart={}", username, weekStart);
            return new ReportLaunch(null, exists);
        }
        List<GitCommitView> commits = gitScanner.scanDetailed(username, weekStart, weekEnd).commits();

        String template = WorklogTemplates.orDefault(
                settingsService.templateOf(username, false), WorklogTemplates.DEFAULT_WEEKLY);
        return launch(username, WorklogScenarios.WEEKLY,
                ReportPrompts.weekly(weekStart, dailies, entries, commits, template));
    }

    /** 手动创建空白周报：同 {@link #createDaily}，幂等。 */
    public WeeklyReportView createWeekly(String username, LocalDate weekStart) {
        if (!repoService.reportExists(username, WorklogRepoService.ReportKind.WEEKLY, weekStart)) {
            repoService.writeReport(username, WorklogRepoService.ReportKind.WEEKLY, weekStart,
                    assembleWeekly("", ""), WorklogRepoService.CommitSource.MANUAL, null, null);
        }
        return getWeekly(username, weekStart);
    }

    /**
     * 编辑保存周报（按 weekStart 寻址）：分节字段重组正文写仓（未提供的节保留现状）。
     * status 传了即 400（同 {@link #updateDaily}）。
     */
    public WeeklyReportView updateWeekly(String username, LocalDate weekStart, UpdateWeeklyRequest req) {
        rejectStatus(req.status());
        var current = repoService.readReport(username, WorklogRepoService.ReportKind.WEEKLY, weekStart)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "周报不存在: " + weekStart));
        if (req.summaryMd() != null || req.nextPlanMd() != null) {
            String[] parts = splitWeekly(current.contentMd());
            String summary = req.summaryMd() != null ? req.summaryMd() : parts[0];
            String plan = req.nextPlanMd() != null ? req.nextPlanMd() : parts[1];
            repoService.writeReport(username, WorklogRepoService.ReportKind.WEEKLY, weekStart,
                    assembleWeekly(summary, plan), WorklogRepoService.CommitSource.MANUAL, null, null);
        }
        return getWeekly(username, weekStart);
    }

    // ---------------- 内部 ----------------

    /**
     * 定位本人 WORKLOG 项目并创建生成会话。节点路由：显式空 → 场景预设空 → 项目亲和节点
     * （M1 创建即锁定）；节点离线/协议 v5 门控/同空间并发冲突由会话层 409 fail-visible 上抛。
     */
    private ReportLaunch launch(String username, String scenarioCode, String prompt) {
        String projectId = workspaceService.ensureFor(username).projectId();
        if (projectId == null || projectId.isBlank()) {
            // CAP-74：仓初始化不再被节点阻断，409 推迟到这里 fail-visible
            throw new DevMindException(ErrorCode.CONFLICT,
                    "无平台默认执行节点，无法创建工作日志生成会话（请先注册 runner 节点并设为默认；读/编辑/推送不受影响）");
        }
        SessionView s = sessionManager.create(new CreateSessionRequest(
                null, scenarioCode, projectId, null, null, prompt,
                null, null, null, null, null, null, null, null, null));
        log.info("报告生成会话已创建: user={} scenario={} session={}", username, scenarioCode, s.id());
        return new ReportLaunch(s.id(), false);
    }

    /** 周报分节视图组装（contentMd 全文 → summary/nextPlan）。 */
    private static WeeklyReportView toWeeklyView(LocalDate weekStart, String contentMd, String sessionId,
                                                 java.time.Instant createdAt, java.time.Instant updatedAt) {
        String[] parts = splitWeekly(contentMd);
        return new WeeklyReportView(true, weekStart, parts[0], parts[1], sessionId, createdAt, updatedAt);
    }

    /** 分节重组周报正文（plan 空省略尾部，往返幂等：splitWeekly(assembleWeekly(s,p)) == [s,p]）。 */
    static String assembleWeekly(String summary, String plan) {
        String s = summary == null ? "" : summary.strip();
        String p = plan == null ? "" : plan.strip();
        if (p.isEmpty()) {
            return SUMMARY_HEADING + "\n\n" + s + "\n";
        }
        return SUMMARY_HEADING + "\n\n" + s + "\n\n" + PLAN_HEADING + "\n\n" + p + "\n";
    }

    /** 按分节标题切周报正文；解析失败时全部进 summary，plan 留空（前端可手改）。 */
    static String[] splitWeekly(String body) {
        if (body == null) {
            return new String[]{"", ""};
        }
        int ps = body.indexOf(PLAN_HEADING);
        if (ps < 0) {
            return new String[]{body.replace(SUMMARY_HEADING, "").strip(), ""};
        }
        String summary = body.substring(0, ps).replace(SUMMARY_HEADING, "").strip();
        String plan = body.substring(ps + PLAN_HEADING.length()).strip();
        return new String[]{summary, plan};
    }

    private static void rejectStatus(String status) {
        if (status != null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "status 字段已废弃（CAP-74 去状态机，保存即生效），请不要再传");
        }
    }
}
