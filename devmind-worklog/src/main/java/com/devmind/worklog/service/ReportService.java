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
import com.devmind.worklog.model.WorklogPlanItemEntity;
import com.devmind.worklog.repo.DailyReportRepository;
import com.devmind.worklog.repo.WeeklyReportRepository;
import com.devmind.worklog.repo.WorklogEntryRepository;
import com.devmind.worklog.repo.WorklogPlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-28 FR-05/06：日报/周报查询、手动空白稿、编辑保存；CAP-41 FR-03：AI 生成改真实会话；
 * CAP-89：存储回 DB（CAP-74 的服务端 git 仓链路整体退役）——daily_reports/weekly_reports
 * 重新成为事实源。防覆盖语义不变：自动路径（定时/手动非 force）在目标行已存在时跳过；
 * force 显式覆盖（旧版不留历史——已定稿接受的代价，见 CAP-89 §10）。
 *
 * <p>生成 = 在本人 WORKLOG 项目下创建 kind=worklog 会话（场景 worklog-daily / worklog-weekly，
 * prompt = 渲染后的格式模板 + 素材快照），claude 在 runner 草稿区把成稿写到
 * .devmind/output/ 回传；落库由 {@link WorklogOutputMirror} 在会话结束时消费回传完成。</p>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** 周报正文分节标记（视图拆分/重组与默认模板共用，改动需同步） */
    static final String SUMMARY_HEADING = "## 上周总结";
    static final String PLAN_HEADING = "## 下周计划";

    /** 周报视图/周报素材读全文的上限（截断只是兜底防爆） */
    private static final int FULL_READ_CHARS = 500_000;

    private final WorklogEntryService entryService;
    private final GitLogScanner gitScanner;
    private final WorklogSettingsService settingsService;
    private final WorklogWorkspaceService workspaceService;
    private final SessionManagerService sessionManager;
    private final DailyReportRepository dailyRepo;
    private final WeeklyReportRepository weeklyRepo;
    private final WorklogPlanItemRepository planRepo;
    private final WorklogEntryRepository entryRepo;

    public ReportService(WorklogEntryService entryService,
                         GitLogScanner gitScanner,
                         WorklogSettingsService settingsService,
                         WorklogWorkspaceService workspaceService,
                         SessionManagerService sessionManager,
                         DailyReportRepository dailyRepo,
                         WeeklyReportRepository weeklyRepo,
                         WorklogPlanItemRepository planRepo,
                         WorklogEntryRepository entryRepo) {
        this.entryService = entryService;
        this.gitScanner = gitScanner;
        this.settingsService = settingsService;
        this.workspaceService = workspaceService;
        this.sessionManager = sessionManager;
        this.dailyRepo = dailyRepo;
        this.weeklyRepo = weeklyRepo;
        this.planRepo = planRepo;
        this.entryRepo = entryRepo;
    }

    // ---------------- 日报 ----------------

    public DailyReportView getDaily(String username, LocalDate date) {
        return dailyRepo.findByUserIdAndWorkDate(username, date)
                .map(e -> new DailyReportView(true, date, e.getContentMd(), e.getSessionId(),
                        e.getCreatedAt(), e.getUpdatedAt()))
                .orElse(null);
    }

    /** 最近 days 天内有报告的日报（新日期在前，contentMd 为前 1000 字预览），前端「最近两周」列表用。 */
    public List<DailyReportView> recentDaily(String username, int days) {
        LocalDate to = LocalDate.now();
        return dailyRepo.findByUserIdAndWorkDateBetweenOrderByWorkDateDesc(
                        username, to.minusDays(days - 1L), to)
                .stream()
                .map(e -> new DailyReportView(true, e.getWorkDate(), preview(e.getContentMd(), 1000),
                        e.getSessionId(), e.getCreatedAt(), e.getUpdatedAt()))
                .toList();
    }

    /** 某周（weekStart=周一）7 天内有报告的日报（日期升序，contentMd 为预览），日报周视图的周日选择条用。 */
    public List<DailyReportView> weekDaily(String username, LocalDate weekStart) {
        return dailyRepo.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(
                        username, weekStart, weekStart.plusDays(6))
                .stream()
                .map(e -> new DailyReportView(true, e.getWorkDate(), preview(e.getContentMd(), 1000),
                        e.getSessionId(), e.getCreatedAt(), e.getUpdatedAt()))
                .toList();
    }

    /**
     * 手动生成前同步预检（控制器调用）：已存在且非 force → 直接复用无需素材；
     * 无素材 → 400。让「点生成却永远没有结果」的场景立即报错，而不是起会话后空跑。
     */
    public void precheckDaily(String username, LocalDate date, boolean force) {
        boolean exists = dailyRepo.existsByUserIdAndWorkDate(username, date);
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
     * 成稿随会话结束回传落库（{@link WorklogOutputMirror}）。
     * 已存在：非 force 复用（reused=true，不起会话）；force 显式覆盖（旧版不留，见类注释）。
     */
    public ReportLaunch generateDaily(String username, LocalDate date, boolean force, String hint) {
        boolean exists = dailyRepo.existsByUserIdAndWorkDate(username, date);
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
        return launch(username, WorklogScenarios.DAILY,
                ReportPrompts.daily(date, commits, entries, dayPlanBlock(username, date), template, hint));
    }

    /**
     * CAP-88 M2 FR-07 日报计划对照素材：当日日计划 + 当周周计划（linkable 同口径）+ 真实状态 +
     * 关联条目；无计划项渲染「省略」指令，存量日期生成行为不变。
     */
    String dayPlanBlock(String username, LocalDate date) {
        List<WorklogPlanItemEntity> plans = new ArrayList<>(
                planRepo.findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(
                        username, WorklogPlanItemEntity.SCOPE_DAY, date));
        plans.addAll(planRepo.findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(
                username, WorklogPlanItemEntity.SCOPE_WEEK, WorklogWeeks.mondayOf(date)));
        return ReportPrompts.formatPlanItems(plans, linkedEntries(plans));
    }

    /** 手动创建空白日报（不经 AI、不要求素材）。幂等：已存在直接返回，不覆盖已有内容。 */
    public DailyReportView createDaily(String username, LocalDate date) {
        if (!dailyRepo.existsByUserIdAndWorkDate(username, date)) {
            DailyReportEntity e = new DailyReportEntity();
            e.setUserId(username);
            e.setWorkDate(date);
            e.setContentMd("");
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            e.setCreatedAt(now);
            e.setUpdatedAt(now);
            dailyRepo.save(e);
        }
        return getDaily(username, date);
    }

    /**
     * 编辑保存日报（按日期寻址，保存即生效）。
     * status 字段已随状态机删除——传了即 400（防旧前端静默误以为确认生效）。
     */
    public DailyReportView updateDaily(String username, LocalDate date, UpdateDailyRequest req) {
        rejectStatus(req.status());
        DailyReportEntity e = dailyRepo.findByUserIdAndWorkDate(username, date)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "日报不存在: " + date));
        if (req.contentMd() != null) {
            e.setContentMd(req.contentMd());
            e.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
            dailyRepo.save(e);
        }
        return getDaily(username, date);
    }

    // ---------------- 周报 ----------------

    public WeeklyReportView getWeekly(String username, LocalDate weekStart) {
        return weeklyRepo.findByUserIdAndWeekStart(username, weekStart)
                .map(e -> new WeeklyReportView(true, weekStart, e.getSummaryMd(), e.getNextPlanMd(),
                        e.getSessionId(), e.getCreatedAt(), e.getUpdatedAt()))
                .orElse(null);
    }

    /** 最近 weeks 个周（含本周，周一为界）有报告的周报（新周在前），前端「最近一个月」列表用。 */
    public List<WeeklyReportView> recentWeekly(String username, int weeks) {
        LocalDate thisMonday = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        return weeklyRepo.findByUserIdAndWeekStartBetweenOrderByWeekStartDesc(
                        username, thisMonday.minusWeeks(weeks - 1L), thisMonday)
                .stream()
                .map(e -> new WeeklyReportView(true, e.getWeekStart(), e.getSummaryMd(), e.getNextPlanMd(),
                        e.getSessionId(), e.getCreatedAt(), e.getUpdatedAt()))
                .toList();
    }

    /** 周报手动生成预检：同 {@link #precheckDaily}，素材 = 该周日报或原始条目。 */
    public void precheckWeekly(String username, LocalDate weekStart, boolean force) {
        boolean exists = weeklyRepo.existsByUserIdAndWeekStart(username, weekStart);
        if (exists && !force) {
            return;
        }
        LocalDate weekEnd = weekStart.plusDays(6);
        boolean noDaily = dailyRepo.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(
                username, weekStart, weekEnd).isEmpty();
        boolean noEntry = entryService.listRangeEntries(username, weekStart, weekEnd).isEmpty();
        if (noDaily && noEntry) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    weekStart + " 周无工作素材（无日报且无条目），周报未生成");
        }
    }

    /**
     * 生成周报：创建 worklog 会话（场景 worklog-weekly）。素材 = 该周日报全文（草稿区无历史文件
     * 可回读，prompt 携带的日报全文是唯一素材口径；无日报回退该周原始条目）+ 该周 git 提交。
     */
    public ReportLaunch generateWeekly(String username, LocalDate weekStart, boolean force, String hint) {
        boolean exists = weeklyRepo.existsByUserIdAndWeekStart(username, weekStart);
        if (exists && !force) {
            return new ReportLaunch(null, true);
        }

        LocalDate weekEnd = weekStart.plusDays(6);
        List<ReportPrompts.DailyMaterial> dailies = dailyRepo
                .findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(username, weekStart, weekEnd)
                .stream()
                .map(e -> new ReportPrompts.DailyMaterial(e.getWorkDate(),
                        preview(e.getContentMd(), FULL_READ_CHARS)))
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
                ReportPrompts.weekly(weekStart, dailies, entries, commits,
                        lastWeekPlan(username, weekStart), thisWeekPlanBlock(username, weekStart),
                        template, hint));
    }

    /**
     * 上周计划素材（CAP-88 M2 FR-08）：上周有计划项 → 结构化清单（计划项 + 真实状态 + 关联条目）；
     * 无计划项 → 回退上周周报 next_plan_md 文本（FR-09 存量兼容，{{lastWeekPlan}} 语义不变只升级来源）；
     * 两者皆无 → 空串（模板据此省略跟进块）。
     */
    String lastWeekPlan(String username, LocalDate weekStart) {
        LocalDate prevMonday = weekStart.minusWeeks(1);
        List<WorklogPlanItemEntity> items = planRepo
                .findByUserIdAndPlanDateBetweenOrderByPlanDateAscSortOrderAscIdAsc(
                        username, prevMonday, prevMonday.plusDays(6));
        if (!items.isEmpty()) {
            return ReportPrompts.formatPlanItems(items, linkedEntries(items));
        }
        return weeklyRepo.findByUserIdAndWeekStart(username, prevMonday)
                .map(WeeklyReportEntity::getNextPlanMd)
                .orElse("");
    }

    /** CAP-88 M2 FR-08：本周已排计划项一并注入，供「下周计划」节呼应；未排渲染「省略」指令。 */
    String thisWeekPlanBlock(String username, LocalDate weekStart) {
        List<WorklogPlanItemEntity> items = planRepo
                .findByUserIdAndPlanDateBetweenOrderByPlanDateAscSortOrderAscIdAsc(
                        username, weekStart, weekStart.plusDays(6));
        return ReportPrompts.formatPlanItems(items, linkedEntries(items));
    }

    /** 批量取计划项的关联条目并按 planItemId 分组（禁 Map.of 空兜底——见平台坑录）。 */
    private Map<Long, List<WorklogEntryEntity>> linkedEntries(List<WorklogPlanItemEntity> items) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<WorklogEntryEntity>> byPlanId = new HashMap<>();
        for (WorklogEntryEntity e : entryRepo.findByPlanItemIdIn(
                items.stream().map(WorklogPlanItemEntity::getId).toList())) {
            byPlanId.computeIfAbsent(e.getPlanItemId(), k -> new ArrayList<>()).add(e);
        }
        return byPlanId;
    }

    /** 手动创建空白周报：同 {@link #createDaily}，幂等。 */
    public WeeklyReportView createWeekly(String username, LocalDate weekStart) {
        if (!weeklyRepo.existsByUserIdAndWeekStart(username, weekStart)) {
            WeeklyReportEntity e = new WeeklyReportEntity();
            e.setUserId(username);
            e.setWeekStart(weekStart);
            e.setSummaryMd("");
            e.setNextPlanMd("");
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            e.setCreatedAt(now);
            e.setUpdatedAt(now);
            weeklyRepo.save(e);
        }
        return getWeekly(username, weekStart);
    }

    /**
     * 编辑保存周报（按 weekStart 寻址）：分节字段直写两列（未提供的节保留现状）。
     * status 传了即 400（同 {@link #updateDaily}）。
     */
    public WeeklyReportView updateWeekly(String username, LocalDate weekStart, UpdateWeeklyRequest req) {
        rejectStatus(req.status());
        WeeklyReportEntity e = weeklyRepo.findByUserIdAndWeekStart(username, weekStart)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "周报不存在: " + weekStart));
        if (req.summaryMd() != null || req.nextPlanMd() != null) {
            if (req.summaryMd() != null) {
                e.setSummaryMd(req.summaryMd());
            }
            if (req.nextPlanMd() != null) {
                e.setNextPlanMd(req.nextPlanMd());
            }
            e.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
            weeklyRepo.save(e);
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
            throw new DevMindException(ErrorCode.CONFLICT,
                    "无平台默认执行节点，无法创建工作日志生成会话（请先注册 runner 节点并设为默认；读/编辑不受影响）");
        }
        SessionView s = sessionManager.create(new CreateSessionRequest(
                null, scenarioCode, projectId, null, null, prompt,
                null, null, null, null, null, null, null, null, null));
        log.info("报告生成会话已创建: user={} scenario={} session={}", username, scenarioCode, s.id());
        return new ReportLaunch(s.id(), false);
    }

    /** 列表 preview / 全文上限截断（null 安全）。 */
    static String preview(String content, int chars) {
        if (content == null) {
            return "";
        }
        return content.length() <= chars ? content : content.substring(0, chars);
    }

    /** 周报分节重组正文（plan 空省略尾部，往返幂等：splitWeekly(assembleWeekly(s,p)) == [s,p]）。
     *  存量仓文件迁移与成稿回传落库（全文 → 两列）共用。 */
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
                    "status 字段已废弃（去状态机，保存即生效），请不要再传");
        }
    }
}
