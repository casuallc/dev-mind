package com.devmind.worklog.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.requirement.WipRequirementProvider;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.SessionView;
import com.devmind.session.service.SessionManagerService;
import com.devmind.worklog.dto.DailyReportView;
import com.devmind.worklog.dto.GitCommitView;
import com.devmind.worklog.dto.GoalTreeView;
import com.devmind.worklog.dto.GoalView;
import com.devmind.worklog.dto.NextPlanPreview;
import com.devmind.worklog.dto.QuarterlyReportView;
import com.devmind.worklog.dto.ReportLaunch;
import com.devmind.worklog.dto.ReviewPreview;
import com.devmind.worklog.dto.UpdateDailyRequest;
import com.devmind.worklog.dto.UpdateQuarterlyRequest;
import com.devmind.worklog.dto.UpdateWeeklyRequest;
import com.devmind.worklog.dto.WeeklyReportView;
import com.devmind.worklog.model.DailyReportEntity;
import com.devmind.worklog.model.WeeklyReportEntity;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.model.WorklogGoalEntity;
import com.devmind.worklog.model.WorklogPlanItemEntity;
import com.devmind.worklog.model.WorklogQuarterlyReportEntity;
import com.devmind.worklog.repo.DailyReportRepository;
import com.devmind.worklog.repo.WeeklyReportRepository;
import com.devmind.worklog.repo.WorklogEntryRepository;
import com.devmind.worklog.repo.WorklogGoalRepository;
import com.devmind.worklog.repo.WorklogPlanItemRepository;
import com.devmind.worklog.repo.WorklogQuarterlyReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final ObjectProvider<WipRequirementProvider> wipProvider;
    private final WorklogQuarterlyReportRepository quarterlyRepo;
    private final WorklogGoalRepository goalRepo;
    private final WorklogGoalService goalService;

    public ReportService(WorklogEntryService entryService,
                         GitLogScanner gitScanner,
                         WorklogSettingsService settingsService,
                         WorklogWorkspaceService workspaceService,
                         SessionManagerService sessionManager,
                         DailyReportRepository dailyRepo,
                         WeeklyReportRepository weeklyRepo,
                         WorklogPlanItemRepository planRepo,
                         WorklogEntryRepository entryRepo,
                         ObjectProvider<WipRequirementProvider> wipProvider,
                         WorklogQuarterlyReportRepository quarterlyRepo,
                         WorklogGoalRepository goalRepo,
                         WorklogGoalService goalService) {
        this.entryService = entryService;
        this.gitScanner = gitScanner;
        this.settingsService = settingsService;
        this.workspaceService = workspaceService;
        this.sessionManager = sessionManager;
        this.dailyRepo = dailyRepo;
        this.weeklyRepo = weeklyRepo;
        this.planRepo = planRepo;
        this.entryRepo = entryRepo;
        this.wipProvider = wipProvider;
        this.quarterlyRepo = quarterlyRepo;
        this.goalRepo = goalRepo;
        this.goalService = goalService;
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
     * CAP-90 FR-04 日报计划对照素材（数据源切换：日计划已并入条目）：当日未完成条目
     * （TODO/DOING，计划态即日计划）+ 当周周计划项 + 真实状态 + 关联条目；两者皆无渲染
     * 「省略」指令，存量日期生成行为不变。
     */
    String dayPlanBlock(String username, LocalDate date) {
        List<WorklogEntryEntity> unfinished = entryRepo
                .findByUserIdAndWorkDateAndStatusInOrderByCreatedAtAsc(
                        username, date, WorklogEntryService.UNFINISHED);
        List<WorklogPlanItemEntity> weekPlans = planRepo
                .findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(
                        username, WorklogPlanItemEntity.SCOPE_WEEK, WorklogWeeks.mondayOf(date));
        return ReportPrompts.formatDayPlanBlock(unfinished, weekPlans, linkedEntries(weekPlans));
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
                        nextWeekPlanBlock(username, weekStart), template, hint));
    }

    /**
     * CAP-90 FR-09「下周计划」素材：下周（weekStart+7d）WEEK 计划项的结构化清单
     * （{{nextWeekPlan}} 占位；下周计划项是唯一事实源，周报文本是其单向叙述投影）；
     * 未排 → 空串（weekly() 渲染为「按本周总结合理推断」指令，维持存量行为）。
     */
    String nextWeekPlanBlock(String username, LocalDate weekStart) {
        List<WorklogPlanItemEntity> items = planRepo
                .findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(
                        username, WorklogPlanItemEntity.SCOPE_WEEK, weekStart.plusWeeks(1));
        if (items.isEmpty()) {
            return "";
        }
        return ReportPrompts.formatPlanItems(items, linkedEntries(items));
    }

    /**
     * CAP-90 FR-10「从计划项重建」预览：下周计划项确定性渲染为 markdown（不经 LLM），
     * 前端确认后填入周报编辑框（人工保存才落库——「自动草稿 + 人工确认」哲学）。
     */
    public NextPlanPreview nextPlanPreview(String username, LocalDate weekStart) {
        List<WorklogPlanItemEntity> items = planRepo
                .findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(
                        username, WorklogPlanItemEntity.SCOPE_WEEK, weekStart.plusWeeks(1));
        return new NextPlanPreview(ReportPrompts.renderNextPlanMarkdown(items), items.size());
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

    /** 批量取计划项的关联条目并按 planItemId 分组（禁 Map.of 空兜底——见平台坑录）。
     *  CAP-90 FR-04：素材口径只取 DONE 事实条目（计划态关联不算执行证据）。 */
    private Map<Long, List<WorklogEntryEntity>> linkedEntries(List<WorklogPlanItemEntity> items) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<WorklogEntryEntity>> byPlanId = new HashMap<>();
        for (WorklogEntryEntity e : entryRepo.findByPlanItemIdIn(
                items.stream().map(WorklogPlanItemEntity::getId).toList())) {
            if (!WorklogEntryEntity.STATUS_DONE.equals(e.getStatus())) {
                continue;
            }
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

    // ---------------- CAP-88 M3：AI 建议计划 ----------------

    /**
     * 「AI 建议下周计划」（FR-10）：目标周已有 WEEK 计划项且非 force → 幂等跳过
     * （reused=true，不起会话）；否则走既有 worklog 会话生成链路（场景 worklog-plan-suggest，
     * 素材 = 上周未完成/滚转项 + 在办需求 SPI 探测 + hint），产物解析落库由
     * {@link WorklogOutputMirror} 在会话结束时完成（只追加不覆盖）。
     */
    public ReportLaunch suggestPlan(String username, LocalDate weekStart, boolean force, String hint) {
        LocalDate target = WorklogWeeks.mondayOf(weekStart);
        boolean hasItems = !planRepo.findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(
                username, WorklogPlanItemEntity.SCOPE_WEEK, target).isEmpty();
        if (hasItems && !force) {
            log.info("建议计划幂等跳过（目标周已有计划项）: user={} weekStart={}", username, target);
            return new ReportLaunch(null, true);
        }

        // 素材 1：上周未完成/滚转项（TODO/DOING/CARRIED——DONE/DROPPED 已盖棺不承接）
        LocalDate prevMonday = target.minusWeeks(1);
        List<WorklogPlanItemEntity> lastWeek = planRepo
                .findByUserIdAndPlanDateBetweenOrderByPlanDateAscSortOrderAscIdAsc(
                        username, prevMonday, prevMonday.plusDays(6))
                .stream()
                .filter(p -> WorklogPlanItemEntity.STATUS_TODO.equals(p.getStatus())
                        || WorklogPlanItemEntity.STATUS_DOING.equals(p.getStatus())
                        || WorklogPlanItemEntity.STATUS_CARRIED.equals(p.getStatus()))
                .toList();
        String lastWeekBlock = ReportPrompts.formatPlanItems(lastWeek, linkedEntries(lastWeek));
        // 素材 2：在办需求（CAP-13 SPI 探测，缺席降级为「无数据」提示）
        WipRequirementProvider provider = wipProvider.getIfAvailable();
        String wipBlock = ReportPrompts.formatWipRequirements(
                provider == null ? List.of() : provider.findWip(username, 20));
        // 素材 3（CAP-91 M3 FR-11）：目标周所在季度的进行中 KR 清单（建议项可选标注对齐）
        String krBlock = ReportPrompts.formatCurrentKrs(currentKrs(username, target));

        return launch(username, WorklogScenarios.PLAN_SUGGEST,
                ReportPrompts.planSuggest(target, lastWeekBlock, wipBlock, krBlock, hint));
    }

    /** 目标周所在季度的进行中 KR（O 标题回填 parentTitle，供 `- KR#id [O标题] 标题` 渲染）。 */
    private List<GoalView> currentKrs(String username, LocalDate targetWeekStart) {
        String quarter = WorklogQuarters.quarterOf(targetWeekStart);
        List<WorklogGoalEntity> goals = goalRepo
                .findByUserIdAndPeriodInOrderBySortOrderAscIdAsc(username, List.of(quarter));
        Map<Long, String> oTitles = new HashMap<>();
        for (WorklogGoalEntity g : goals) {
            if (WorklogGoalEntity.KIND_OBJECTIVE.equals(g.getKind())) {
                oTitles.put(g.getId(), g.getTitle());
            }
        }
        return goals.stream()
                .filter(g -> WorklogGoalEntity.KIND_KEY_RESULT.equals(g.getKind())
                        && WorklogGoalEntity.STATUS_ACTIVE.equals(g.getStatus()))
                .map(g -> GoalView.of(g, g.getParentId() == null ? null : oTitles.get(g.getParentId())))
                .toList();
    }

    // ---------------- CAP-93：AI 生成 OKR（草稿确认落库） ----------------

    /**
     * 生成 OKR 草稿（FR-01）：场景 worklog-okr-suggest，素材 = 既有目标进展 + 上一周期周报
     * 摘要 + 上一周期条目类型聚合（YEAR 目标另加各季复盘摘要），产物解析进
     * worklog_okr_drafts（{@link WorklogOutputMirror}），人工采纳才建目标。
     * 幂等同 suggestPlan：目标周期已有目标且非 force 跳过（reused=true）；无任何素材 400。
     * FR-07 textOnly=true（纯文本模式）：不聚合任何平台素材，hint 必填且为唯一
     * 生成依据（不再作为「补充说明」二次注入 prompt）。
     */
    public ReportLaunch suggestOkr(String username, String scope, String period, boolean force,
                                   String hint, boolean textOnly) {
        if (!WorklogGoalEntity.SCOPE_YEAR.equals(scope) && !WorklogGoalEntity.SCOPE_QUARTER.equals(scope)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "scope 仅支持 YEAR/QUARTER");
        }
        String p = WorklogQuarters.validatedPeriod(scope, period);
        boolean exists = !goalRepo
                .findByUserIdAndPeriodInOrderBySortOrderAscIdAsc(username, List.of(p)).isEmpty();
        if (exists && !force) {
            log.info("OKR 生成幂等跳过（目标周期已有目标）: user={} period={}", username, p);
            return new ReportLaunch(null, true);
        }
        boolean quarter = WorklogGoalEntity.SCOPE_QUARTER.equals(scope);
        String materials;
        String promptHint = hint;
        if (textOnly) {
            String text = hint == null ? "" : hint.strip();
            if (text.isEmpty()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "纯文本模式下依据文本必填（未提供生成依据，OKR 草稿未生成）");
            }
            materials = "## 素材：本人提供的说明文本（本次生成的唯一依据）\n\n" + text;
            promptHint = null;   // 文本已在素材块内，避免 appendHint 二次注入
        } else {
            materials = quarter
                    ? okrQuarterMaterials(username, p) : okrYearMaterials(username, p);
            if (materials == null) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        p + " 无生成素材（参考周期无目标、无周报且无已完成条目），OKR 草稿未生成");
            }
        }
        String linkableYearOs = quarter
                ? ReportPrompts.formatYearOsForLinking(
                        goalService.tree(Integer.parseInt(WorklogQuarters.yearOf(p))).yearObjectives())
                : null;
        return launch(username, WorklogScenarios.OKR_SUGGEST,
                ReportPrompts.okrSuggest(scope, p, materials, linkableYearOs, promptHint, textOnly));
    }

    /**
     * QUARTER 素材：上一季度 OKR 进展（紧凑树）+ 上一季度周报摘要 + 上一季度条目类型聚合。
     * 全空返回 null（调用方 400）。
     */
    private String okrQuarterMaterials(String username, String period) {
        LocalDate prevEnd = WorklogQuarters.rangeOf(period)[0].minusDays(1);
        String prevQuarter = WorklogQuarters.quarterOf(prevEnd);
        GoalTreeView refTree = goalService.tree(Integer.parseInt(WorklogQuarters.yearOf(prevQuarter)));
        GoalTreeView.QuarterSection prevSection = refTree.quarters().stream()
                .filter(s -> prevQuarter.equals(s.period())).findFirst().orElse(null);
        String prevOkr = ReportPrompts.formatGoalTreeCompact(
                prevSection == null ? List.of() : prevSection.objectives());
        LocalDate[] prevRange = WorklogQuarters.rangeOf(prevQuarter);
        String weeklies = ReportPrompts.formatWeeklySummaries(
                weeklyRepo.findByUserIdAndWeekStartBetweenOrderByWeekStartAsc(
                        username, prevRange[0], prevRange[1]));
        String entries = entryTypeAggBlock(username, prevRange[0], prevRange[1]);
        boolean noOkr = prevSection == null || prevSection.objectives().isEmpty();
        if (noOkr && weeklies.startsWith("（无") && entries.startsWith("（无")) {
            return null;
        }
        return "## 素材：上一季度（" + prevQuarter + "）OKR 进展（未完成的可承接滚转）\n\n" + prevOkr
                + "\n\n## 素材：上一季度周报摘要\n\n" + weeklies
                + "\n\n## 素材：上一季度工作聚合（已完成条目按类型）\n\n" + entries;
    }

    /**
     * YEAR 素材：上一年度目标进展 + 上一年各季度 OKR 进展 + 上一年各季度复盘摘要 +
     * 上一年条目类型聚合。全空返回 null（调用方 400）。
     */
    private String okrYearMaterials(String username, String period) {
        int refYear = Integer.parseInt(period) - 1;
        GoalTreeView refTree = goalService.tree(refYear);
        StringBuilder quarters = new StringBuilder();
        boolean anyQuarter = false;
        for (GoalTreeView.QuarterSection s : refTree.quarters()) {
            if (s.objectives().isEmpty()) {
                continue;
            }
            anyQuarter = true;
            quarters.append("### ").append(s.period()).append('\n')
                    .append(ReportPrompts.formatGoalTreeCompact(s.objectives())).append("\n\n");
        }
        StringBuilder reviews = new StringBuilder();
        for (int q = 1; q <= 4; q++) {
            quarterlyRepo.findByUserIdAndQuarter(username, refYear + "-Q" + q)
                    .filter(r -> r.getContentMd() != null && !r.getContentMd().isBlank())
                    .ifPresent(r -> reviews.append("### ").append(r.getQuarter()).append('\n')
                            .append(preview(r.getContentMd(), 800)).append("\n\n"));
        }
        String entries = entryTypeAggBlock(username,
                LocalDate.of(refYear, 1, 1), LocalDate.of(refYear, 12, 31));
        if (refTree.yearObjectives().isEmpty() && !anyQuarter
                && reviews.length() == 0 && entries.startsWith("（无")) {
            return null;
        }
        return "## 素材：" + refYear + " 年度目标进展\n\n"
                + ReportPrompts.formatGoalTreeCompact(refTree.yearObjectives())
                + "\n\n## 素材：" + refYear + " 各季度 OKR 进展\n\n"
                + (anyQuarter ? quarters.toString().strip() : "（无季度目标）")
                + "\n\n## 素材：" + refYear + " 各季度复盘摘要\n\n"
                + (reviews.length() == 0 ? "（无复盘数据）" : reviews.toString().strip())
                + "\n\n## 素材：" + refYear + " 全年工作聚合（已完成条目按类型）\n\n" + entries;
    }

    /** 条目按类型聚合素材（OKR 生成：范围内 DONE 条目全量按类型聚，含标题抽样）。 */
    private String entryTypeAggBlock(String username, LocalDate from, LocalDate to) {
        Map<String, List<WorklogEntryEntity>> byType = new LinkedHashMap<>();
        for (WorklogEntryEntity e : entryRepo.findRange(username, from, to)) {
            if (WorklogEntryEntity.STATUS_DONE.equals(e.getStatus())) {
                byType.computeIfAbsent(e.getEntryType(), k -> new ArrayList<>()).add(e);
            }
        }
        return ReportPrompts.formatEntryTypeAgg(byType.entrySet().stream()
                .map(en -> new ReportPrompts.UnalignedGroup(en.getKey(), en.getValue().size(),
                        en.getValue().stream().mapToInt(
                                e -> e.getMinutes() == null ? 0 : e.getMinutes()).sum(),
                        en.getValue().stream().map(WorklogEntryEntity::getTitle).limit(5).toList()))
                .toList());
    }

    // ---------------- CAP-91 M2：季度复盘 ----------------

    public QuarterlyReportView getQuarterly(String username, String period) {
        String p = validatedQuarter(period);
        return quarterlyRepo.findByUserIdAndQuarter(username, p)
                .map(e -> new QuarterlyReportView(true, p, e.getContentMd(), e.getSessionId(),
                        e.getCreatedAt(), e.getUpdatedAt()))
                .orElse(null);
    }

    /** 季度复盘手动生成预检（同 {@link #precheckDaily}）：素材 = 当季目标 或 季内 DONE 条目。 */
    public void precheckQuarterly(String username, String period, boolean force) {
        String p = validatedQuarter(period);
        if (quarterlyRepo.existsByUserIdAndQuarter(username, p) && !force) {
            return;
        }
        LocalDate[] range = WorklogQuarters.rangeOf(p);
        boolean noGoals = goalRepo
                .findByUserIdAndPeriodInOrderBySortOrderAscIdAsc(username, List.of(p)).isEmpty();
        boolean noEntries = entryRepo.findRange(username, range[0], range[1]).stream()
                .noneMatch(e -> WorklogEntryEntity.STATUS_DONE.equals(e.getStatus()));
        if (noGoals && noEntries) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    p + " 无复盘素材（当季无目标且无已完成条目），季度复盘未生成");
        }
    }

    /**
     * 生成季度复盘（FR-09）：场景 worklog-quarterly-review，素材 = OKR 对照块（确定性渲染，
     * 与 {@link #reviewPreview} 同源）+ 计划外工作聚合 + hint；prompt 固定结构不走用户模板。
     * 防覆盖语义同日报：非 force 已有行复用，force 显式覆盖。
     */
    public ReportLaunch generateQuarterly(String username, String period, boolean force, String hint) {
        String p = validatedQuarter(period);
        boolean exists = quarterlyRepo.existsByUserIdAndQuarter(username, p);
        if (exists && !force) {
            return new ReportLaunch(null, true);
        }
        LocalDate[] range = WorklogQuarters.rangeOf(p);
        return launch(username, WorklogScenarios.QUARTERLY_REVIEW,
                ReportPrompts.quarterlyReview(p, range[0], range[1],
                        okrBlock(username, p), unalignedBlock(username, p), hint));
    }

    /**
     * 「从数据重建」预览（FR-10）：OKR 对照块 + 计划外聚合确定性渲染为 markdown（不经 LLM）；
     * 前端确认后填入编辑框，人工保存才落库（同 CAP-90 FR-10 哲学）。
     */
    public ReviewPreview reviewPreview(String username, String period) {
        String p = validatedQuarter(period);
        return new ReviewPreview("## OKR 对照\n\n" + okrBlock(username, p)
                + "\n\n## 计划外工作\n\n" + unalignedBlock(username, p));
    }

    /** 手动创建空白季度复盘：同 {@link #createDaily}，幂等。 */
    public QuarterlyReportView createQuarterly(String username, String period) {
        String p = validatedQuarter(period);
        if (!quarterlyRepo.existsByUserIdAndQuarter(username, p)) {
            WorklogQuarterlyReportEntity e = new WorklogQuarterlyReportEntity();
            e.setUserId(username);
            e.setQuarter(p);
            e.setContentMd("");
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            e.setCreatedAt(now);
            e.setUpdatedAt(now);
            quarterlyRepo.save(e);
        }
        return getQuarterly(username, p);
    }

    /** 编辑保存季度复盘（按 period 寻址，保存即生效，同日报口径）。 */
    public QuarterlyReportView updateQuarterly(String username, String period, UpdateQuarterlyRequest req) {
        String p = validatedQuarter(period);
        WorklogQuarterlyReportEntity e = quarterlyRepo.findByUserIdAndQuarter(username, p)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "季度复盘不存在: " + p));
        if (req.contentMd() != null) {
            e.setContentMd(req.contentMd());
            e.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
            quarterlyRepo.save(e);
        }
        return getQuarterly(username, p);
    }

    /**
     * OKR 对照素材（FR-09）：复用 {@link WorklogGoalService#tree} 的派生口径取当季区，
     * 再补 KR 关联计划项逐项明细（期外项不计，同派生过滤口径）。
     */
    String okrBlock(String username, String period) {
        GoalTreeView tree = goalService.tree(Integer.parseInt(WorklogQuarters.yearOf(period)));
        GoalTreeView.QuarterSection section = tree.quarters().stream()
                .filter(s -> period.equals(s.period())).findFirst().orElse(null);
        if (section == null || section.objectives().isEmpty()) {
            return ReportPrompts.formatOkrTree(List.of(), null, null);
        }
        List<Long> krIds = section.objectives().stream()
                .flatMap(n -> n.krs().stream()).map(GoalView::id).toList();
        Map<Long, List<WorklogPlanItemEntity>> plansByKr = new HashMap<>();
        if (!krIds.isEmpty()) {
            LocalDate[] range = WorklogQuarters.rangeOf(period);
            for (WorklogPlanItemEntity p : planRepo.findByKrIdIn(krIds)) {
                if (p.getPlanDate().isBefore(range[0]) || p.getPlanDate().isAfter(range[1])) {
                    continue; // 周期外（周跨季挂错季等）不计，同派生口径
                }
                plansByKr.computeIfAbsent(p.getKrId(), k -> new ArrayList<>()).add(p);
            }
        }
        List<WorklogPlanItemEntity> all = plansByKr.values().stream()
                .flatMap(List::stream).toList();
        return ReportPrompts.formatOkrTree(section.objectives(), plansByKr, linkedEntries(all));
    }

    /** 计划外工作聚合素材（FR-09）：季内 DONE 且未对齐任何 KR 的条目，按类型聚合 + 标题抽样。 */
    String unalignedBlock(String username, String period) {
        LocalDate[] range = WorklogQuarters.rangeOf(period);
        List<WorklogEntryEntity> done = entryRepo.findRange(username, range[0], range[1]).stream()
                .filter(e -> WorklogEntryEntity.STATUS_DONE.equals(e.getStatus()))
                .toList();
        if (done.isEmpty()) {
            return ReportPrompts.formatUnaligned(List.of());
        }
        Set<Long> planIds = done.stream().map(WorklogEntryEntity::getPlanItemId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, WorklogPlanItemEntity> planById = new HashMap<>();
        if (!planIds.isEmpty()) {
            planRepo.findAllById(planIds).forEach(p -> planById.put(p.getId(), p));
        }
        Map<String, List<WorklogEntryEntity>> byType = new LinkedHashMap<>();
        for (WorklogEntryEntity e : done) {
            WorklogPlanItemEntity p = e.getPlanItemId() == null ? null : planById.get(e.getPlanItemId());
            if (p != null && p.getKrId() != null) {
                continue; // 已对齐 KR 的不算计划外
            }
            byType.computeIfAbsent(e.getEntryType(), k -> new ArrayList<>()).add(e);
        }
        List<ReportPrompts.UnalignedGroup> groups = byType.entrySet().stream()
                .map(en -> new ReportPrompts.UnalignedGroup(en.getKey(), en.getValue().size(),
                        en.getValue().stream().mapToInt(
                                e -> e.getMinutes() == null ? 0 : e.getMinutes()).sum(),
                        en.getValue().stream().map(WorklogEntryEntity::getTitle).limit(5).toList()))
                .toList();
        return ReportPrompts.formatUnaligned(groups);
    }

    /** 季度 period 校验（'2026-Q1'，非法 400）。 */
    private static String validatedQuarter(String period) {
        return WorklogQuarters.validatedPeriod("QUARTER", period);
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
                null, null, null, null, null, null, null, null));
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
