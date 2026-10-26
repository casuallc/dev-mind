package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.worklog.dto.GoalCarryResult;
import com.devmind.worklog.dto.GoalRequest;
import com.devmind.worklog.dto.GoalTreeView;
import com.devmind.worklog.dto.GoalView;
import com.devmind.worklog.dto.LinkableKrView;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.model.WorklogGoalEntity;
import com.devmind.worklog.model.WorklogPlanItemEntity;
import com.devmind.worklog.repo.WorklogEntryRepository;
import com.devmind.worklog.repo.WorklogGoalRepository;
import com.devmind.worklog.repo.WorklogPlanItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.devmind.worklog.model.WorklogGoalEntity.FRAMEWORK_OKR;
import static com.devmind.worklog.model.WorklogGoalEntity.KIND_KEY_RESULT;
import static com.devmind.worklog.model.WorklogGoalEntity.KIND_OBJECTIVE;
import static com.devmind.worklog.model.WorklogGoalEntity.SCOPE_QUARTER;
import static com.devmind.worklog.model.WorklogGoalEntity.SCOPE_YEAR;
import static com.devmind.worklog.model.WorklogGoalEntity.STATUS_ACTIVE;
import static com.devmind.worklog.model.WorklogGoalEntity.STATUS_CARRIED;
import static com.devmind.worklog.model.WorklogGoalEntity.STATUS_DONE;
import static com.devmind.worklog.model.WorklogGoalEntity.STATUS_DROPPED;

/**
 * 目标 CRUD/树/滚转（CAP-91 M1：FR-01~FR-07）。按人隔离（仅能改自己的，他人 404，同条目口径）。
 *
 * <p>层级规则：KR 必挂同 scope+period 的 O；QUARTER O 可选挂同年 YEAR O；YEAR O 必空。
 * 删除：O 有子项 400；删 KR 清引用它的周计划项 kr_id（同删计划项清条目引用口径）。
 * 派生进度（FR-07）只在 tree 视图计算展示，不回写 progress（人工域）。</p>
 */
@Service
public class WorklogGoalService {

    /** 人工可设状态（CARRIED 仅滚转写入） */
    private static final Set<String> MANUAL_STATUSES = Set.of(STATUS_ACTIVE, STATUS_DONE, STATUS_DROPPED);

    private final WorklogGoalRepository goalRepo;
    private final WorklogPlanItemRepository planRepo;
    private final WorklogEntryRepository entryRepo;
    private final IdentityService identity;

    public WorklogGoalService(WorklogGoalRepository goalRepo, WorklogPlanItemRepository planRepo,
                              WorklogEntryRepository entryRepo, IdentityService identity) {
        this.goalRepo = goalRepo;
        this.planRepo = planRepo;
        this.entryRepo = entryRepo;
        this.identity = identity;
    }

    /** 整年目标树（FR-02/07）：年度 O（嵌套年度 KR）+ Q1~Q4 四季区（O 嵌套 KR，KR 附派生数据）。 */
    public GoalTreeView tree(int year) {
        String me = identity.currentActor();
        List<String> periods = new ArrayList<>();
        periods.add(String.valueOf(year));
        for (int q = 1; q <= 4; q++) {
            periods.add(year + "-Q" + q);
        }
        List<WorklogGoalEntity> all = goalRepo.findByUserIdAndPeriodInOrderBySortOrderAscIdAsc(me, periods);
        Map<Long, String> titles = new HashMap<>();
        for (WorklogGoalEntity g : all) {
            titles.put(g.getId(), g.getTitle());
        }
        // 派生数据：KR → 周期内关联计划项与 DONE 条目工时（批量两趟，禁逐 KR 查库）
        List<WorklogGoalEntity> krs = all.stream().filter(g -> KIND_KEY_RESULT.equals(g.getKind())).toList();
        Map<Long, int[]> planAgg = new HashMap<>();   // krId → [total, done]
        Map<Long, Integer> hoursAgg = new HashMap<>(); // krId → DONE 条目分钟合计
        if (!krs.isEmpty()) {
            List<WorklogPlanItemEntity> linked = planRepo.findByKrIdIn(
                    krs.stream().map(WorklogGoalEntity::getId).toList());
            Map<Long, WorklogPlanItemEntity> planById = new HashMap<>();
            Map<Long, List<Long>> planIdsByKr = new HashMap<>();
            for (WorklogPlanItemEntity p : linked) {
                planById.put(p.getId(), p);
                planIdsByKr.computeIfAbsent(p.getKrId(), k -> new ArrayList<>()).add(p.getId());
            }
            for (WorklogGoalEntity kr : krs) {
                LocalDate[] range = periodRange(kr);
                int total = 0;
                int done = 0;
                List<Long> inRangeIds = new ArrayList<>();
                for (Long pid : planIdsByKr.getOrDefault(kr.getId(), List.of())) {
                    WorklogPlanItemEntity p = planById.get(pid);
                    if (p.getPlanDate().isBefore(range[0]) || p.getPlanDate().isAfter(range[1])) {
                        continue; // 周期外（周跨季挂错季等）不计派生
                    }
                    inRangeIds.add(pid);
                    if (WorklogPlanItemEntity.STATUS_DROPPED.equals(p.getStatus())
                            || WorklogPlanItemEntity.STATUS_CARRIED.equals(p.getStatus())) {
                        continue; // 已放弃/已滚转不计入分母（同周计划完成度口径）
                    }
                    total++;
                    if (WorklogPlanItemEntity.STATUS_DONE.equals(p.getStatus())) {
                        done++;
                    }
                }
                planAgg.put(kr.getId(), new int[]{total, done});
                if (!inRangeIds.isEmpty()) {
                    int minutes = 0;
                    for (WorklogEntryEntity e : entryRepo.findByPlanItemIdIn(inRangeIds)) {
                        if (WorklogEntryEntity.STATUS_DONE.equals(e.getStatus()) && e.getMinutes() != null) {
                            minutes += e.getMinutes();
                        }
                    }
                    hoursAgg.put(kr.getId(), minutes);
                }
            }
        }
        List<GoalTreeView.GoalNode> yearObjectives = nodes(all, titles, planAgg, hoursAgg, SCOPE_YEAR, String.valueOf(year));
        List<GoalTreeView.QuarterSection> quarters = new ArrayList<>();
        for (int q = 1; q <= 4; q++) {
            String period = year + "-Q" + q;
            quarters.add(new GoalTreeView.QuarterSection(period,
                    nodes(all, titles, planAgg, hoursAgg, SCOPE_QUARTER, period)));
        }
        return new GoalTreeView(yearObjectives, quarters);
    }

    /** 一个 scope+period 下的 O 节点列表（各嵌套其 KR，KR 附派生数据）。 */
    private List<GoalTreeView.GoalNode> nodes(List<WorklogGoalEntity> all, Map<Long, String> titles,
                                              Map<Long, int[]> planAgg, Map<Long, Integer> hoursAgg,
                                              String scope, String period) {
        Map<Long, List<GoalView>> krsByParent = new HashMap<>();
        for (WorklogGoalEntity g : all) {
            if (!KIND_KEY_RESULT.equals(g.getKind()) || !scope.equals(g.getScope())
                    || !period.equals(g.getPeriod())) {
                continue;
            }
            int[] agg = planAgg.getOrDefault(g.getId(), new int[]{0, 0});
            Integer minutes = hoursAgg.get(g.getId());
            GoalView v = GoalView.of(g, g.getParentId() == null ? null : titles.get(g.getParentId()))
                    .withDerivation(agg[0], agg[1], minutes == null ? 0 : minutes / 60.0,
                            agg[0] == 0 ? null : (int) Math.round(agg[1] * 100.0 / agg[0]));
            krsByParent.computeIfAbsent(g.getParentId(), k -> new ArrayList<>()).add(v);
        }
        List<GoalTreeView.GoalNode> result = new ArrayList<>();
        for (WorklogGoalEntity g : all) {
            if (!KIND_OBJECTIVE.equals(g.getKind()) || !scope.equals(g.getScope())
                    || !period.equals(g.getPeriod())) {
                continue;
            }
            result.add(new GoalTreeView.GoalNode(
                    GoalView.of(g, g.getParentId() == null ? null : titles.get(g.getParentId())),
                    krsByParent.getOrDefault(g.getId(), List.of())));
        }
        return result;
    }

    /** KR 派生统计的日期区间：QUARTER=季度区间；YEAR=全年。 */
    private static LocalDate[] periodRange(WorklogGoalEntity kr) {
        if (SCOPE_QUARTER.equals(kr.getScope())) {
            return WorklogQuarters.rangeOf(kr.getPeriod());
        }
        int year = Integer.parseInt(kr.getPeriod());
        return new LocalDate[]{LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)};
    }

    /** 新增（状态恒 ACTIVE、进度恒 0；framework 空 = OKR）。 */
    public GoalView create(GoalRequest req) {
        String me = identity.currentActor();
        WorklogGoalEntity e = new WorklogGoalEntity();
        e.setUserId(me);
        e.setScope(validatedScope(req.scope()));
        e.setFramework(validatedFramework(req.framework()));
        e.setKind(validatedKind(req.kind()));
        e.setPeriod(WorklogQuarters.validatedPeriod(e.getScope(), req.period()));
        e.setParentId(validatedParent(me, e.getScope(), e.getPeriod(), e.getKind(), req.parentId()));
        e.setStatus(STATUS_ACTIVE);
        e.setProgress(0);
        e.setSortOrder(0);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        e.setCreatedAt(now);
        applyTo(e, req);
        return GoalView.of(goalRepo.save(e), parentTitle(me, e.getParentId()));
    }

    /** 编辑/改状态/改进度（他人 404；CARRIED 只读；scope/period/kind 不可改——变了请新建）。 */
    public GoalView update(Long id, GoalRequest req) {
        String me = identity.currentActor();
        WorklogGoalEntity e = goalRepo.findByIdAndUserId(id, me)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "目标不存在: " + id));
        if (STATUS_CARRIED.equals(e.getStatus())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "已滚转目标不可编辑（副本在目标周期）");
        }
        if (!e.getScope().equals(req.scope()) || !e.getPeriod().equals(req.period())
                || !e.getKind().equals(req.kind())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "scope/period/kind 不可改（目标的身份属性），请新建目标");
        }
        // framework 同为身份属性：传 null/空白 = 不变；传不同值 400
        if (req.framework() != null && !req.framework().isBlank()
                && !e.getFramework().equals(req.framework().strip())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "framework 不可改（目标的身份属性），请新建目标");
        }
        e.setParentId(validatedParent(me, e.getScope(), e.getPeriod(), e.getKind(), req.parentId()));
        applyTo(e, req);
        if (req.progress() != null) {
            e.setProgress(req.progress());
        }
        if (req.status() != null && !req.status().isBlank()) {
            String st = req.status().strip();
            if (!MANUAL_STATUSES.contains(st)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "status 仅支持 " + MANUAL_STATUSES + "（CARRIED 由滚转写入）");
            }
            e.setStatus(st);
        }
        return GoalView.of(goalRepo.save(e), parentTitle(me, e.getParentId()));
    }

    /** 删除（FR-02：O 有子项 400；删 KR 清引用它的周计划项 kr_id）。 */
    @Transactional
    public void delete(Long id) {
        String me = identity.currentActor();
        WorklogGoalEntity e = goalRepo.findByIdAndUserId(id, me)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "目标不存在: " + id));
        if (KIND_OBJECTIVE.equals(e.getKind())
                && !goalRepo.findByUserIdAndParentId(me, id).isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "该目标下仍有子项（KR/季度目标），请先删除或改挂子项");
        }
        List<WorklogPlanItemEntity> linked = planRepo.findByKrId(id);
        linked.forEach(p -> p.setKrId(null));
        planRepo.saveAll(linked);
        goalRepo.delete(e);
    }

    /**
     * 跨周期滚转（FR-04）：目标周期插入副本（status=ACTIVE、carried_from_id=原 id），原项置
     * CARRIED——单事务。幂等：原项非 ACTIVE 跳过。KR 新父解析：krParentMap 显式指定 → 同批
     * 携带父 O 的副本 → 皆无 400。toPeriod 与各源项 scope 格式各自匹配（YEAR→'2027'，
     * QUARTER→'2027-Q1'，混批各自校验）。
     */
    @Transactional
    public GoalCarryResult carry(String toPeriod, List<Long> ids, Map<Long, Long> krParentMap) {
        String me = identity.currentActor();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<Long, Long> parentOverride = krParentMap == null ? Map.of() : krParentMap;
        List<WorklogGoalEntity> sources = new ArrayList<>();
        int skipped = 0;
        for (Long id : new HashSet<>(ids)) {
            WorklogGoalEntity src = goalRepo.findByIdAndUserId(id, me)
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "目标不存在: " + id));
            WorklogQuarters.validatedPeriod(src.getScope(), toPeriod);
            if (!STATUS_ACTIVE.equals(src.getStatus())) {
                skipped++;
                continue;
            }
            sources.add(src);
        }
        // 两阶段：先 O 后 KR（KR 新父可能指向同批 O 的副本）
        Map<Long, Long> copyIdOf = new HashMap<>();
        List<GoalView> created = new ArrayList<>();
        for (WorklogGoalEntity src : sources) {
            if (KIND_OBJECTIVE.equals(src.getKind())) {
                created.add(GoalView.of(carryOne(me, src, toPeriod, parentOverride.get(src.getId()),
                        copyIdOf, now), null));
            }
        }
        for (WorklogGoalEntity src : sources) {
            if (!KIND_KEY_RESULT.equals(src.getKind())) {
                continue;
            }
            Long newParent = parentOverride.get(src.getId());
            if (newParent == null) {
                newParent = copyIdOf.get(src.getParentId());
            }
            if (newParent == null) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "KR「" + src.getTitle() + "」滚转缺少目标周期的父 O：请同批携带其父 O，或用 krParentMap 指定");
            }
            created.add(GoalView.of(carryOne(me, src, toPeriod, newParent, copyIdOf, now), null));
        }
        return new GoalCarryResult(created.size(), skipped, created);
    }

    /** 单项目标滚转复制 + 原项置 CARRIED；O 的 parentOverride 仅对 QUARTER O 有效（挂新年 O）。 */
    private WorklogGoalEntity carryOne(String me, WorklogGoalEntity src, String toPeriod, Long parentOverride,
                                       Map<Long, Long> copyIdOf, Instant now) {
        Long newParent;
        if (KIND_KEY_RESULT.equals(src.getKind())) {
            if (copyIdOf.containsValue(parentOverride)) {
                // 同批携带父 O 的副本：scope/kind/period 天然合规（同 scope 复制、周期=toPeriod），免查库
                newParent = parentOverride;
            } else {
                WorklogGoalEntity parent = goalRepo.findByIdAndUserId(parentOverride, me)
                        .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST,
                                "krParentMap 指定的父 O 不存在: " + parentOverride));
                if (!KIND_OBJECTIVE.equals(parent.getKind()) || !src.getScope().equals(parent.getScope())
                        || !toPeriod.equals(parent.getPeriod())) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST,
                            "krParentMap 指定的父 O 必须是目标周期 " + toPeriod + " 的 OBJECTIVE");
                }
                newParent = parent.getId();
            }
        } else if (SCOPE_QUARTER.equals(src.getScope()) && parentOverride != null) {
            WorklogGoalEntity parent = goalRepo.findByIdAndUserId(parentOverride, me)
                    .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST,
                            "指定的年度父目标不存在: " + parentOverride));
            if (!KIND_OBJECTIVE.equals(parent.getKind()) || !SCOPE_YEAR.equals(parent.getScope())
                    || !WorklogQuarters.yearOf(toPeriod).equals(parent.getPeriod())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "季度 O 的父必须是 " + WorklogQuarters.yearOf(toPeriod) + " 的年度目标");
            }
            newParent = parent.getId();
        } else {
            newParent = null;
        }
        WorklogGoalEntity copy = new WorklogGoalEntity();
        copy.setUserId(me);
        copy.setScope(src.getScope());
        copy.setPeriod(toPeriod);
        copy.setKind(src.getKind());
        copy.setFramework(src.getFramework());
        copy.setParentId(newParent);
        copy.setTitle(src.getTitle());
        copy.setContent(src.getContent());
        copy.setWeight(src.getWeight());
        copy.setProgress(0);
        copy.setStatus(STATUS_ACTIVE);
        copy.setCarriedFromId(src.getId());
        copy.setSortOrder(0);
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        src.setStatus(STATUS_CARRIED);
        src.setUpdatedAt(now);
        goalRepo.save(src);
        WorklogGoalEntity saved = goalRepo.save(copy);
        copyIdOf.put(src.getId(), saved.getId());
        return saved;
    }

    /** 计划项抽屉可关联 KR（FR-06）：该日所在季的 ACTIVE KEY_RESULT + 所属 O 标题。 */
    public List<LinkableKrView> linkableKrs(LocalDate date) {
        String me = identity.currentActor();
        String period = WorklogQuarters.quarterOf(date);
        List<WorklogGoalEntity> krs = goalRepo
                .findByUserIdAndScopeAndPeriodAndKindAndStatusOrderBySortOrderAscIdAsc(
                        me, SCOPE_QUARTER, period, KIND_KEY_RESULT, STATUS_ACTIVE);
        Map<Long, String> oTitles = new HashMap<>();
        for (WorklogGoalEntity kr : krs) {
            if (kr.getParentId() != null && !oTitles.containsKey(kr.getParentId())) {
                goalRepo.findByIdAndUserId(kr.getParentId(), me)
                        .ifPresent(o -> oTitles.put(o.getId(), o.getTitle()));
            }
        }
        return krs.stream()
                .map(kr -> new LinkableKrView(kr.getId(), kr.getTitle(),
                        kr.getParentId() == null ? null : oTitles.get(kr.getParentId()), period))
                .toList();
    }

    // ---------------- 内部 ----------------

    /** 就地更新可变字段；id/userId/scope/period/kind/status/progress/carriedFromId/createdAt 保留。 */
    private static void applyTo(WorklogGoalEntity e, GoalRequest req) {
        e.setTitle(req.title().strip());
        e.setContent(req.content());
        e.setWeight(req.weight());
        e.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private static String validatedScope(String scope) {
        if (!SCOPE_YEAR.equals(scope) && !SCOPE_QUARTER.equals(scope)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "scope 仅支持 YEAR/QUARTER");
        }
        return scope;
    }

    /**
     * 目标体系校验（CAP-91 M4）：null/空白 = OKR（默认）；其余一律 400。
     * 后续接入新形式时在此登记，并为 validatedKind/validatedParent 挂该体系的规则集
     * （OKR 规则 = O/KR 两层、KR 必挂同周期 O、QUARTER O 可选挂同年 YEAR O）。
     */
    private static String validatedFramework(String framework) {
        if (framework == null || framework.isBlank()) {
            return FRAMEWORK_OKR;
        }
        String f = framework.strip();
        if (!FRAMEWORK_OKR.equals(f)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "目前仅支持 OKR 目标体系: " + f);
        }
        return f;
    }

    private static String validatedKind(String kind) {
        if (!KIND_OBJECTIVE.equals(kind) && !KIND_KEY_RESULT.equals(kind)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "kind 仅支持 OBJECTIVE/KEY_RESULT");
        }
        return kind;
    }

    /** 层级规则（FR-01）：KR 必挂同 scope+period 的 O；QUARTER O 可选挂同年 YEAR O；YEAR O 必空。 */
    private Long validatedParent(String me, String scope, String period, String kind, Long parentId) {
        if (KIND_OBJECTIVE.equals(kind) && SCOPE_YEAR.equals(scope)) {
            if (parentId != null) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "年度目标不能有父目标");
            }
            return null;
        }
        if (KIND_OBJECTIVE.equals(kind)) { // QUARTER O：可选挂同年 YEAR O
            if (parentId == null) {
                return null;
            }
            WorklogGoalEntity parent = goalRepo.findByIdAndUserId(parentId, me)
                    .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST, "父目标不存在: " + parentId));
            if (!KIND_OBJECTIVE.equals(parent.getKind()) || !SCOPE_YEAR.equals(parent.getScope())
                    || !WorklogQuarters.yearOf(period).equals(parent.getPeriod())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "季度 O 的父必须是 " + WorklogQuarters.yearOf(period) + " 的年度目标");
            }
            return parentId;
        }
        // KEY_RESULT：必挂同 scope+period 的 O
        if (parentId == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "KR 必须挂在同周期的 O 下（parentId 必填）");
        }
        WorklogGoalEntity parent = goalRepo.findByIdAndUserId(parentId, me)
                .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST, "父目标不存在: " + parentId));
        if (!KIND_OBJECTIVE.equals(parent.getKind()) || !scope.equals(parent.getScope())
                || !period.equals(parent.getPeriod())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "KR 的父必须是同周期 " + period + " 的 OBJECTIVE");
        }
        return parentId;
    }

    private String parentTitle(String me, Long parentId) {
        if (parentId == null) {
            return null;
        }
        return goalRepo.findByIdAndUserId(parentId, me).map(WorklogGoalEntity::getTitle).orElse(null);
    }
}
