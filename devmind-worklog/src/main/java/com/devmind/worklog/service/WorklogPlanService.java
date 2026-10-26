package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.worklog.dto.CarryResult;
import com.devmind.worklog.dto.PlanItemRequest;
import com.devmind.worklog.dto.PlanItemView;
import com.devmind.worklog.dto.PlanListView;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.model.WorklogPlanItemEntity;
import com.devmind.worklog.repo.WorklogEntryRepository;
import com.devmind.worklog.repo.WorklogPlanItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.devmind.worklog.model.WorklogPlanItemEntity.SCOPE_DAY;
import static com.devmind.worklog.model.WorklogPlanItemEntity.SCOPE_WEEK;
import static com.devmind.worklog.model.WorklogPlanItemEntity.SOURCE_MANUAL;
import static com.devmind.worklog.model.WorklogPlanItemEntity.STATUS_CARRIED;
import static com.devmind.worklog.model.WorklogPlanItemEntity.STATUS_DOING;
import static com.devmind.worklog.model.WorklogPlanItemEntity.STATUS_DONE;
import static com.devmind.worklog.model.WorklogPlanItemEntity.STATUS_DROPPED;
import static com.devmind.worklog.model.WorklogPlanItemEntity.STATUS_TODO;

/**
 * 计划项 CRUD/状态/滚转（CAP-88 M1：FR-01/02/03/05 + FR-04 的删项清引用）。
 * 按人隔离（仅能改自己的，他人 404，同条目口径）；周计划 plan_date 服务端归一到周一。
 *
 * <p>状态机（FR-03）：自动只到 DOING（条目关联在 {@link WorklogEntryService} 触发）；
 * DONE/DROPPED 必须人工；CARRIED 仅滚转写入，人工不可设、已滚转项不可再改状态。</p>
 */
@Service
public class WorklogPlanService {

    /** 未完成（可滚转）状态集 */
    static final Set<String> UNFINISHED = Set.of(STATUS_TODO, STATUS_DOING);
    /** 人工可设状态（CARRIED 仅滚转写入） */
    private static final Set<String> MANUAL_STATUSES = Set.of(STATUS_TODO, STATUS_DOING, STATUS_DONE, STATUS_DROPPED);
    private static final Set<String> PLAN_TYPES = Set.of(
            WorklogEntryEntity.TYPE_DEV, WorklogEntryEntity.TYPE_SUPPORT, WorklogEntryEntity.TYPE_MEETING,
            WorklogEntryEntity.TYPE_RESEARCH, WorklogEntryEntity.TYPE_OTHER);

    private final WorklogPlanItemRepository planRepo;
    private final WorklogEntryRepository entryRepo;
    private final IdentityService identity;

    public WorklogPlanService(WorklogPlanItemRepository planRepo, WorklogEntryRepository entryRepo,
                              IdentityService identity) {
        this.planRepo = planRepo;
        this.entryRepo = entryRepo;
        this.identity = identity;
    }

    /** 某周期计划项列表 + 上一周期未完成提示（FR-05 前半：提示数据随列表返回）。 */
    public PlanListView list(String scope, LocalDate date) {
        String me = identity.currentActor();
        String sc = validatedScope(scope);
        LocalDate planDate = normalize(sc, date);
        List<PlanItemView> items = planRepo
                .findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(me, sc, planDate)
                .stream().map(PlanItemView::of).toList();
        LocalDate prev = SCOPE_DAY.equals(sc) ? planDate.minusDays(1) : planDate.minusWeeks(1);
        List<PlanItemView> carryover = planRepo
                .findByUserIdAndScopeAndPlanDateAndStatusInOrderBySortOrderAscIdAsc(me, sc, prev, UNFINISHED)
                .stream().map(PlanItemView::of).toList();
        return new PlanListView(items, prev, carryover);
    }

    /** 条目抽屉可关联项（FR-06）：当日日计划 + 当周周计划（DOING/DONE 可选——补录忘挂的执行）。 */
    public List<PlanItemView> linkable(LocalDate date) {
        String me = identity.currentActor();
        return planRepo.findLinkable(me, date, WorklogWeeks.mondayOf(date))
                .stream().map(PlanItemView::of).toList();
    }

    /** 新增（状态恒 TODO、来源 MANUAL；AI_SUGGEST 由 M3 suggest 链路写入）。 */
    public PlanItemView create(PlanItemRequest req) {
        String me = identity.currentActor();
        WorklogPlanItemEntity e = new WorklogPlanItemEntity();
        e.setUserId(me);
        e.setScope(validatedScope(req.scope()));
        e.setPlanDate(normalize(e.getScope(), req.date()));
        e.setStatus(STATUS_TODO);
        e.setSource(SOURCE_MANUAL);
        e.setSortOrder(0);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        e.setCreatedAt(now);
        applyTo(e, req);
        return PlanItemView.of(planRepo.save(e));
    }

    /** 编辑/改状态（他人项 404；CARRIED 项只读——不可改内容也不可改状态）。 */
    public PlanItemView update(Long id, PlanItemRequest req) {
        String me = identity.currentActor();
        WorklogPlanItemEntity e = planRepo.findByIdAndUserId(id, me)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "计划项不存在: " + id));
        if (STATUS_CARRIED.equals(e.getStatus())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "已滚转项不可编辑（副本在目标周期）");
        }
        applyTo(e, req);
        if (req.status() != null && !req.status().isBlank()) {
            String st = req.status().strip();
            if (!MANUAL_STATUSES.contains(st)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "status 仅支持 " + MANUAL_STATUSES + "（CARRIED 由滚转写入）");
            }
            e.setStatus(st);
        }
        return PlanItemView.of(planRepo.save(e));
    }

    /** 删除（FR-04：关联条目 plan_item_id 置空——DB 世界直接清引用，无悬空 id）。 */
    @Transactional
    public void delete(Long id) {
        String me = identity.currentActor();
        WorklogPlanItemEntity e = planRepo.findByIdAndUserId(id, me)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "计划项不存在: " + id));
        List<WorklogEntryEntity> linked = entryRepo.findByPlanItemId(id);
        linked.forEach(en -> en.setPlanItemId(null));
        entryRepo.saveAll(linked);
        planRepo.delete(e);
    }

    /**
     * 一键滚转（FR-05）：目标周期插入各项副本（status=TODO、carried_from_id=原 id），原项置
     * CARRIED——单事务完成。幂等：原项已 CARRIED 或已完结（DONE/DROPPED）即跳过。
     */
    @Transactional
    public CarryResult carry(String scope, LocalDate to, List<Long> ids) {
        String me = identity.currentActor();
        String sc = validatedScope(scope);
        LocalDate target = normalize(sc, to);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<PlanItemView> created = new ArrayList<>();
        int skipped = 0;
        for (Long id : ids) {
            WorklogPlanItemEntity src = planRepo.findByIdAndUserId(id, me)
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "计划项不存在: " + id));
            if (!sc.equals(src.getScope())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "计划项 " + id + " 是" + src.getScope() + " 计划，不能用 " + sc + " 滚转");
            }
            if (!UNFINISHED.contains(src.getStatus())) {
                skipped++;
                continue;
            }
            WorklogPlanItemEntity copy = new WorklogPlanItemEntity();
            copy.setUserId(me);
            copy.setScope(sc);
            copy.setPlanDate(target);
            copy.setTitle(src.getTitle());
            copy.setContent(src.getContent());
            copy.setPlanType(src.getPlanType());
            copy.setEstimateMinutes(src.getEstimateMinutes());
            copy.setStatus(STATUS_TODO);
            copy.setSource(src.getSource());
            copy.setRequirementId(src.getRequirementId());
            copy.setJiraIssueKey(src.getJiraIssueKey());
            copy.setCarriedFromId(src.getId());
            copy.setSortOrder(0);
            copy.setCreatedAt(now);
            copy.setUpdatedAt(now);
            src.setStatus(STATUS_CARRIED);
            src.setUpdatedAt(now);
            planRepo.save(src);
            created.add(PlanItemView.of(planRepo.save(copy)));
        }
        return new CarryResult(created.size(), skipped, created);
    }

    // ---------------- 内部 ----------------

    /** 就地更新可变字段；id/userId/scope/planDate/status/source/carriedFromId/createdAt 保留。 */
    private static void applyTo(WorklogPlanItemEntity e, PlanItemRequest req) {
        e.setTitle(req.title().strip());
        e.setContent(req.content());
        e.setPlanType(validatedType(req.planType()));
        e.setEstimateMinutes(req.hours() == null ? null : WorklogEntryFile.toMinutes(req.hours()));
        e.setRequirementId(req.requirementId());
        e.setJiraIssueKey(req.jiraIssueKey());
        e.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private static String validatedScope(String scope) {
        if (!SCOPE_DAY.equals(scope) && !SCOPE_WEEK.equals(scope)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "scope 仅支持 DAY/WEEK");
        }
        return scope;
    }

    /** WEEK 归一到周一（接口对调用方宽容：周内任意日均可）；DAY 原样。 */
    private static LocalDate normalize(String scope, LocalDate date) {
        return SCOPE_WEEK.equals(scope) ? WorklogWeeks.mondayOf(date) : date;
    }

    private static String validatedType(String type) {
        String t = type == null || type.isBlank() ? WorklogEntryEntity.TYPE_DEV : type;
        if (!PLAN_TYPES.contains(t)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "planType 仅支持 " + PLAN_TYPES);
        }
        return t;
    }
}
