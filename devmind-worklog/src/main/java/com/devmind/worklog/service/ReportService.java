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
import com.devmind.worklog.model.DailyReportEntity;
import com.devmind.worklog.model.WeeklyReportEntity;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.repo.DailyReportRepository;
import com.devmind.worklog.repo.WeeklyReportRepository;
import com.devmind.worklog.repo.WorklogEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * CAP-28 FR-05/06：日报/周报查询、手动草稿、编辑确认；CAP-41 FR-03：AI 生成改真实会话。
 *
 * <p>生成 = 在本人 WORKLOG 项目下创建 kind=worklog 会话（场景 worklog-daily / worklog-weekly，
 * prompt = 渲染后的格式模板 + 素材快照），claude 在 runner 持久工作区写 daily//weekly/ 文件
 * 并按 skill 约定把成稿复制到 .devmind/output/ 回传；落库由 {@link WorklogOutputMirror}
 * 在会话结束时消费回传完成（DB 降为镜像）。CAP-28 的 one-shot 裸会话链路已从本模块摘除
 * （OneShotAgentRunner SPI 保留给其他调用方）。</p>
 *
 * <p>幂等：unique(user_id, work_date / week_start)，已存在且非 force 直接复用不起会话；
 * force 仅覆盖 DRAFT，CONFIRMED 拒绝（409）。</p>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** 周报正文分节标记（镜像落库解析与默认模板共用，改动需同步） */
    static final String SUMMARY_HEADING = "## 上周总结";
    static final String PLAN_HEADING = "## 下周计划";

    private final DailyReportRepository dailyRepo;
    private final WeeklyReportRepository weeklyRepo;
    private final WorklogEntryRepository entryRepo;
    private final GitLogScanner gitScanner;
    private final WorklogSettingsService settingsService;
    private final WorklogWorkspaceService workspaceService;
    private final SessionManagerService sessionManager;

    public ReportService(DailyReportRepository dailyRepo,
                         WeeklyReportRepository weeklyRepo,
                         WorklogEntryRepository entryRepo,
                         GitLogScanner gitScanner,
                         WorklogSettingsService settingsService,
                         WorklogWorkspaceService workspaceService,
                         SessionManagerService sessionManager) {
        this.dailyRepo = dailyRepo;
        this.weeklyRepo = weeklyRepo;
        this.entryRepo = entryRepo;
        this.gitScanner = gitScanner;
        this.settingsService = settingsService;
        this.workspaceService = workspaceService;
        this.sessionManager = sessionManager;
    }

    // ---------------- 日报 ----------------

    public DailyReportView getDaily(String username, LocalDate date) {
        return dailyRepo.findByUserIdAndWorkDate(username, date).map(DailyReportView::of).orElse(null);
    }

    /** 最近 days 天内有报告的日报（新日期在前），前端「最近两周」列表用。 */
    public List<DailyReportView> recentDaily(String username, int days) {
        LocalDate to = LocalDate.now();
        return dailyRepo.findByUserIdAndWorkDateBetweenOrderByWorkDateDesc(
                        username, to.minusDays(days - 1L), to)
                .stream().map(DailyReportView::of).toList();
    }

    /** 某周（weekStart=周一）7 天内有报告的日报（日期升序），前端日报周视图的周日选择条用。 */
    public List<DailyReportView> weekDaily(String username, LocalDate weekStart) {
        return dailyRepo.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(
                        username, weekStart, weekStart.plusDays(6))
                .stream().map(DailyReportView::of).toList();
    }

    /**
     * 手动生成前同步预检（控制器调用）：已确认 → 409；无素材 → 400。
     * 让「点生成却永远没有结果」的场景立即报错，而不是起会话后空跑。
     */
    public void precheckDaily(String username, LocalDate date, boolean force) {
        DailyReportEntity existing = dailyRepo.findByUserIdAndWorkDate(username, date).orElse(null);
        if (existing != null && !force) {
            return; // 已有报告且非 force：generate 直接复用，无需素材
        }
        if (existing != null && DailyReportEntity.STATUS_CONFIRMED.equals(existing.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "当日日报已确认，不可重新生成: " + date);
        }
        if (gitScanner.scan(username, date).isEmpty()
                && entryRepo.findByUserIdAndWorkDateOrderByIdAsc(username, date).isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    date + " 无工作素材（无条目且无 git 提交），日报未生成");
        }
    }

    /**
     * 生成日报：创建 worklog 会话（场景 worklog-daily），立即返回会话 id；
     * 成稿随会话结束回传落镜像（{@link WorklogOutputMirror}）。
     * 已存在：非 force 复用（reused=true，不起会话）；force 仅覆盖 DRAFT。
     */
    public ReportLaunch generateDaily(String username, LocalDate date, boolean force) {
        DailyReportEntity existing = dailyRepo.findByUserIdAndWorkDate(username, date).orElse(null);
        if (existing != null && !force) {
            return new ReportLaunch(null, true);
        }
        if (existing != null && DailyReportEntity.STATUS_CONFIRMED.equals(existing.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "当日日报已确认，不可重新生成: " + date);
        }

        List<GitCommitView> commits = gitScanner.scan(username, date);
        List<WorklogEntryEntity> entries = entryRepo.findByUserIdAndWorkDateOrderByIdAsc(username, date);
        if (commits.isEmpty() && entries.isEmpty()) {
            log.info("日报无素材，跳过: user={} date={}", username, date);
            return new ReportLaunch(null, existing != null);
        }

        String template = WorklogTemplates.orDefault(
                settingsService.templateOf(username, true), WorklogTemplates.DEFAULT_DAILY);
        return launch(username, WorklogScenarios.DAILY, ReportPrompts.daily(date, commits, entries, template));
    }

    /**
     * 手动创建空白日报草稿（不经 AI、不要求素材）。幂等：已存在直接返回，
     * 不覆盖任何已有内容与状态（含 CONFIRMED）。
     */
    public DailyReportView createDaily(String username, LocalDate date) {
        DailyReportEntity existing = dailyRepo.findByUserIdAndWorkDate(username, date).orElse(null);
        if (existing != null) {
            return DailyReportView.of(existing);
        }
        DailyReportEntity e = new DailyReportEntity();
        e.setUserId(username);
        e.setWorkDate(date);
        e.setContentMd("");
        e.setStatus(DailyReportEntity.STATUS_DRAFT);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return DailyReportView.of(dailyRepo.save(e));
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

    /** 最近 weeks 个周（含本周，周一为界）有报告的周报（新周在前），前端「最近一个月」列表用。 */
    public List<WeeklyReportView> recentWeekly(String username, int weeks) {
        LocalDate thisMonday = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        return weeklyRepo.findByUserIdAndWeekStartBetweenOrderByWeekStartDesc(
                        username, thisMonday.minusWeeks(weeks - 1L), thisMonday)
                .stream().map(WeeklyReportView::of).toList();
    }

    /** 周报手动生成预检：同 {@link #precheckDaily}，素材 = 该周日报或原始条目。 */
    public void precheckWeekly(String username, LocalDate weekStart, boolean force) {
        WeeklyReportEntity existing = weeklyRepo.findByUserIdAndWeekStart(username, weekStart).orElse(null);
        if (existing != null && !force) {
            return;
        }
        if (existing != null && WeeklyReportEntity.STATUS_CONFIRMED.equals(existing.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "该周周报已确认，不可重新生成: " + weekStart);
        }
        LocalDate weekEnd = weekStart.plusDays(6);
        boolean noDaily = dailyRepo
                .findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(username, weekStart, weekEnd).isEmpty();
        boolean noEntry = entryRepo
                .findByUserIdAndWorkDateBetweenOrderByWorkDateAscIdAsc(username, weekStart, weekEnd).isEmpty();
        if (noDaily && noEntry) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    weekStart + " 周无工作素材（无日报且无条目），周报未生成");
        }
    }

    /**
     * 生成周报：创建 worklog 会话（场景 worklog-weekly）。素材 = 该周日报集合（无日报回退
     * 该周原始条目）+ 该周 git 提交；claude 在工作区还应回读 daily/ 文件（skill 约定）。
     */
    public ReportLaunch generateWeekly(String username, LocalDate weekStart, boolean force) {
        WeeklyReportEntity existing = weeklyRepo.findByUserIdAndWeekStart(username, weekStart).orElse(null);
        if (existing != null && !force) {
            return new ReportLaunch(null, true);
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
            return new ReportLaunch(null, existing != null);
        }
        List<GitCommitView> commits = gitScanner.scanDetailed(username, weekStart, weekEnd).commits();

        String template = WorklogTemplates.orDefault(
                settingsService.templateOf(username, false), WorklogTemplates.DEFAULT_WEEKLY);
        return launch(username, WorklogScenarios.WEEKLY,
                ReportPrompts.weekly(weekStart, dailies, entries, commits, template));
    }

    /**
     * 手动创建空白周报草稿：同 {@link #createDaily}，幂等。
     */
    public WeeklyReportView createWeekly(String username, LocalDate weekStart) {
        WeeklyReportEntity existing = weeklyRepo.findByUserIdAndWeekStart(username, weekStart).orElse(null);
        if (existing != null) {
            return WeeklyReportView.of(existing);
        }
        WeeklyReportEntity e = new WeeklyReportEntity();
        e.setUserId(username);
        e.setWeekStart(weekStart);
        e.setSummaryMd("");
        e.setNextPlanMd("");
        e.setStatus(WeeklyReportEntity.STATUS_DRAFT);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return WeeklyReportView.of(weeklyRepo.save(e));
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

    // ---------------- 内部 ----------------

    /**
     * 定位本人 WORKLOG 项目并创建生成会话。节点路由：显式空 → 场景预设空 → 项目亲和节点
     * （M1 创建即锁定）；节点离线/协议 v5 门控/同空间并发冲突由会话层 409 fail-visible 上抛。
     */
    private ReportLaunch launch(String username, String scenarioCode, String prompt) {
        String projectId = workspaceService.ensureFor(username).projectId();
        SessionView s = sessionManager.create(new CreateSessionRequest(
                null, scenarioCode, projectId, null, null, prompt,
                null, null, null, null, null, null, null, null, null));
        log.info("报告生成会话已创建: user={} scenario={} session={}", username, scenarioCode, s.id());
        return new ReportLaunch(s.id(), false);
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
}
