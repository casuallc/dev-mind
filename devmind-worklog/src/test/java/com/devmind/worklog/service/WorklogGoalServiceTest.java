package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-91 M1 目标（FR-01~FR-07）：层级校验/CRUD/删除清引用/两阶段滚转/派生进度/可关联 KR。
 * Mockito 直桩仓储与身份（同模块既有测试风格，不起 Spring 上下文）。
 */
class WorklogGoalServiceTest {

    private static final String ME = "alice";

    private WorklogGoalRepository goalRepo;
    private WorklogPlanItemRepository planRepo;
    private WorklogEntryRepository entryRepo;
    private WorklogGoalService service;

    @BeforeEach
    void setUp() {
        goalRepo = mock(WorklogGoalRepository.class);
        planRepo = mock(WorklogPlanItemRepository.class);
        entryRepo = mock(WorklogEntryRepository.class);
        IdentityService identity = mock(IdentityService.class);
        when(identity.currentActor()).thenReturn(ME);
        when(goalRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new WorklogGoalService(goalRepo, planRepo, entryRepo, identity);
    }

    private WorklogGoalEntity goal(long id, String scope, String period, String kind, Long parentId) {
        WorklogGoalEntity e = new WorklogGoalEntity();
        e.setId(id);
        e.setUserId(ME);
        e.setScope(scope);
        e.setPeriod(period);
        e.setKind(kind);
        e.setParentId(parentId);
        e.setTitle("目标" + id);
        e.setStatus(WorklogGoalEntity.STATUS_ACTIVE);
        e.setProgress(0);
        e.setSortOrder(0);
        return e;
    }

    // ---------------- FR-01/02：层级与 CRUD ----------------

    @Test
    void 层级规则_KR必挂同周期O_QUARTER_O可挂同年YEAR_O_YEAR_O必空() {
        WorklogGoalEntity yearO = goal(1, "YEAR", "2026", "OBJECTIVE", null);
        WorklogGoalEntity qO = goal(2, "QUARTER", "2026-Q4", "OBJECTIVE", 1L);
        when(goalRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(yearO));
        when(goalRepo.findByIdAndUserId(2L, ME)).thenReturn(Optional.of(qO));

        // YEAR O 带父 → 400
        assertThrows(DevMindException.class, () -> service.create(
                new GoalRequest("YEAR", "2026", "OBJECTIVE", 1L, "x", null, null, null, null, null)));
        // KR 无父 → 400
        assertThrows(DevMindException.class, () -> service.create(
                new GoalRequest("QUARTER", "2026-Q4", "KEY_RESULT", null, "x", null, null, null, null, null)));
        // KR 父跨周期（年 O 不是 2026-Q4 的 O）→ 400
        assertThrows(DevMindException.class, () -> service.create(
                new GoalRequest("QUARTER", "2026-Q4", "KEY_RESULT", 1L, "x", null, null, null, null, null)));
        // QUARTER O 挂同年 YEAR O → 放行
        GoalView qo = service.create(
                new GoalRequest("QUARTER", "2026-Q4", "OBJECTIVE", 1L, "Q4 目标", null, null, null, null, null));
        assertEquals(1L, qo.parentId());
        assertEquals("目标1", qo.parentTitle());
        // QUARTER O 挂错年 YEAR O → 400
        WorklogGoalEntity yearO2025 = goal(9, "YEAR", "2025", "OBJECTIVE", null);
        when(goalRepo.findByIdAndUserId(9L, ME)).thenReturn(Optional.of(yearO2025));
        assertThrows(DevMindException.class, () -> service.create(
                new GoalRequest("QUARTER", "2026-Q4", "OBJECTIVE", 9L, "x", null, null, null, null, null)));
        // KR 挂同周期 O → 放行，默认 ACTIVE/0
        GoalView kr = service.create(
                new GoalRequest("QUARTER", "2026-Q4", "KEY_RESULT", 2L, "KR1", null, 50, 80, null, null));
        assertEquals(2L, kr.parentId());
        assertEquals("ACTIVE", kr.status());
        assertEquals(0, kr.progress(), "创建恒 progress=0（人工域，请求值不生效）");
    }

    @Test
    void scope_kind_period_非法全400() {
        assertThrows(DevMindException.class, () -> service.create(
                new GoalRequest("MONTH", "2026-10", "OBJECTIVE", null, "x", null, null, null, null, null)));
        assertThrows(DevMindException.class, () -> service.create(
                new GoalRequest("YEAR", "2026", "EPIC", null, "x", null, null, null, null, null)));
        assertThrows(DevMindException.class, () -> service.create(
                new GoalRequest("YEAR", "26", "OBJECTIVE", null, "x", null, null, null, null, null)));
        assertThrows(DevMindException.class, () -> service.create(
                new GoalRequest("QUARTER", "2026-Q5", "OBJECTIVE", null, "x", null, null, null, null, null)));
    }

    @Test
    void framework默认OKR_非OKR400_更新不可改() {
        // 空 = 默认 OKR；显式 OKR 放行
        GoalView v = service.create(
                new GoalRequest("YEAR", "2026", "OBJECTIVE", null, "年度方向", null, null, null, null, null));
        assertEquals("OKR", v.framework());
        GoalView v2 = service.create(
                new GoalRequest("YEAR", "2026", "OBJECTIVE", null, "年度方向2", null, null, null, null, "OKR"));
        assertEquals("OKR", v2.framework());
        // 非 OKR → 400（后续体系的扩展点，当前一律拒）
        assertThrows(DevMindException.class, () -> service.create(
                new GoalRequest("YEAR", "2026", "OBJECTIVE", null, "x", null, null, null, null, "KPI")));
        // 更新：framework 传不同值 400（身份属性）；传 null/同值放行
        WorklogGoalEntity e = goal(1, "YEAR", "2026", "OBJECTIVE", null);
        when(goalRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(e));
        assertThrows(DevMindException.class, () -> service.update(1L,
                new GoalRequest("YEAR", "2026", "OBJECTIVE", null, "x", null, null, null, null, "KPI")));
        GoalView updated = service.update(1L,
                new GoalRequest("YEAR", "2026", "OBJECTIVE", null, "改名", null, null, null, null, null));
        assertEquals("OKR", updated.framework());
        GoalView updated2 = service.update(1L,
                new GoalRequest("YEAR", "2026", "OBJECTIVE", null, "改名2", null, null, null, null, "OKR"));
        assertEquals("OKR", updated2.framework());
    }

    @Test
    void 更新身份属性400_CARRIED只读_他人404_进度人工可改() {
        WorklogGoalEntity e = goal(1, "QUARTER", "2026-Q4", "OBJECTIVE", null);
        when(goalRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(e));

        // scope/period/kind 不可改
        assertThrows(DevMindException.class, () -> service.update(1L,
                new GoalRequest("YEAR", "2026", "OBJECTIVE", null, "x", null, null, null, null, null)));

        // progress/status 人工可改
        GoalView v = service.update(1L,
                new GoalRequest("QUARTER", "2026-Q4", "OBJECTIVE", null, "改名", null, null, 60, "DONE", null));
        assertEquals(60, v.progress());
        assertEquals("DONE", v.status());
        // CARRIED 人工不可设
        assertThrows(DevMindException.class, () -> service.update(1L,
                new GoalRequest("QUARTER", "2026-Q4", "OBJECTIVE", null, "x", null, null, null, "CARRIED", null)));

        // CARRIED 只读
        WorklogGoalEntity carried = goal(2, "QUARTER", "2026-Q3", "OBJECTIVE", null);
        carried.setStatus(WorklogGoalEntity.STATUS_CARRIED);
        when(goalRepo.findByIdAndUserId(2L, ME)).thenReturn(Optional.of(carried));
        assertThrows(DevMindException.class, () -> service.update(2L,
                new GoalRequest("QUARTER", "2026-Q3", "OBJECTIVE", null, "x", null, null, null, null, null)));

        // 他人目标 404
        assertThrows(DevMindException.class, () -> service.update(999L,
                new GoalRequest("QUARTER", "2026-Q4", "OBJECTIVE", null, "x", null, null, null, null, null)));
    }

    @Test
    void 删除_O有子项400_删KR清计划项krId() {
        WorklogGoalEntity o = goal(1, "QUARTER", "2026-Q4", "OBJECTIVE", null);
        when(goalRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(o));
        when(goalRepo.findByUserIdAndParentId(ME, 1L))
                .thenReturn(List.of(goal(2, "QUARTER", "2026-Q4", "KEY_RESULT", 1L)));
        assertThrows(DevMindException.class, () -> service.delete(1L));

        WorklogGoalEntity kr = goal(2, "QUARTER", "2026-Q4", "KEY_RESULT", 1L);
        WorklogPlanItemEntity plan = new WorklogPlanItemEntity();
        plan.setId(7L);
        plan.setKrId(2L);
        when(goalRepo.findByIdAndUserId(2L, ME)).thenReturn(Optional.of(kr));
        when(planRepo.findByKrId(2L)).thenReturn(List.of(plan));
        service.delete(2L);
        assertNull(plan.getKrId());
    }

    // ---------------- FR-04：跨周期滚转 ----------------

    @Test
    void 滚转两阶段_同批父O副本承接KR_原项置CARRIED_幂等() {
        WorklogGoalEntity o = goal(1, "QUARTER", "2026-Q3", "OBJECTIVE", null);
        WorklogGoalEntity kr = goal(2, "QUARTER", "2026-Q3", "KEY_RESULT", 1L);
        when(goalRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(o));
        when(goalRepo.findByIdAndUserId(2L, ME)).thenReturn(Optional.of(kr));
        AtomicLong idSeq = new AtomicLong(100);
        when(goalRepo.save(any())).thenAnswer(inv -> {
            WorklogGoalEntity g = inv.getArgument(0);
            if (g.getId() == null) {
                g.setId(idSeq.incrementAndGet());
            }
            return g;
        });

        GoalCarryResult r = service.carry("2026-Q4", List.of(1L, 2L), null);
        assertEquals(2, r.carried());
        assertEquals(0, r.skipped());
        assertEquals(WorklogGoalEntity.STATUS_CARRIED, o.getStatus());
        assertEquals(WorklogGoalEntity.STATUS_CARRIED, kr.getStatus());
        GoalView krCopy = r.items().stream().filter(v -> "KEY_RESULT".equals(v.kind())).findFirst().orElseThrow();
        assertEquals("2026-Q4", krCopy.period());
        assertEquals("OKR", krCopy.framework(), "滚转复制 framework");
        assertEquals(WorklogGoalEntity.STATUS_ACTIVE, krCopy.status());
        assertEquals(2L, krCopy.carriedFromId());
        // KR 副本的新父 = 同批 O 副本的 id（不是原父 1L）
        GoalView oCopy = r.items().stream().filter(v -> "OBJECTIVE".equals(v.kind())).findFirst().orElseThrow();
        assertEquals(oCopy.id(), krCopy.parentId());

        // 重复滚转：已 CARRIED → 跳过（幂等）
        GoalCarryResult r2 = service.carry("2026-Q4", List.of(1L, 2L), null);
        assertEquals(0, r2.carried());
        assertEquals(2, r2.skipped());
    }

    @Test
    void 单独滚转KR_无映射400_krParentMap校验目标周期O() {
        WorklogGoalEntity kr = goal(2, "QUARTER", "2026-Q3", "KEY_RESULT", 1L);
        when(goalRepo.findByIdAndUserId(2L, ME)).thenReturn(Optional.of(kr));

        // 单独 KR、父 O 不同批且无 krParentMap → 400
        assertThrows(DevMindException.class, () -> service.carry("2026-Q4", List.of(2L), null));

        // krParentMap 指向非目标周期 O → 400
        WorklogGoalEntity oldQO = goal(3, "QUARTER", "2026-Q3", "OBJECTIVE", null);
        when(goalRepo.findByIdAndUserId(3L, ME)).thenReturn(Optional.of(oldQO));
        assertThrows(DevMindException.class, () -> service.carry("2026-Q4", List.of(2L), Map.of(2L, 3L)));

        // krParentMap 指向目标周期 O → 放行
        WorklogGoalEntity newQO = goal(4, "QUARTER", "2026-Q4", "OBJECTIVE", null);
        when(goalRepo.findByIdAndUserId(4L, ME)).thenReturn(Optional.of(newQO));
        GoalCarryResult r = service.carry("2026-Q4", List.of(2L), Map.of(2L, 4L));
        assertEquals(1, r.carried());
        assertEquals(4L, r.items().get(0).parentId());
    }

    @Test
    void 滚转toPeriod格式按源scope校验() {
        WorklogGoalEntity yearO = goal(1, "YEAR", "2026", "OBJECTIVE", null);
        when(goalRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(yearO));
        // YEAR 源滚到季度格式 → 400
        assertThrows(DevMindException.class, () -> service.carry("2027-Q1", List.of(1L), null));
    }

    // ---------------- FR-07：派生进度 ----------------

    private WorklogPlanItemEntity plan(long id, long krId, LocalDate date, String status) {
        WorklogPlanItemEntity p = new WorklogPlanItemEntity();
        p.setId(id);
        p.setUserId(ME);
        p.setKrId(krId);
        p.setPlanDate(date);
        p.setStatus(status);
        return p;
    }

    @Test
    void 派生进度_完成度与工时口径() {
        WorklogGoalEntity o = goal(1, "QUARTER", "2026-Q4", "OBJECTIVE", null);
        WorklogGoalEntity kr = goal(2, "QUARTER", "2026-Q4", "KEY_RESULT", 1L);
        WorklogGoalEntity krEmpty = goal(3, "QUARTER", "2026-Q4", "KEY_RESULT", 1L);
        when(goalRepo.findByUserIdAndPeriodInOrderBySortOrderAscIdAsc(any(), any()))
                .thenReturn(List.of(o, kr, krEmpty));

        // Q4 区间 [2026-10-01, 2026-12-31]：2 个在期内（1 DONE + 1 TODO），
        // 1 个 DROPPED 不计分母，1 个期外（Q3）不计
        WorklogPlanItemEntity pDone = plan(11, 2, LocalDate.of(2026, 10, 5), "DONE");
        WorklogPlanItemEntity pTodo = plan(12, 2, LocalDate.of(2026, 11, 2), "TODO");
        WorklogPlanItemEntity pDropped = plan(13, 2, LocalDate.of(2026, 10, 5), "DROPPED");
        WorklogPlanItemEntity pOut = plan(14, 2, LocalDate.of(2026, 9, 28), "DONE");
        when(planRepo.findByKrIdIn(any())).thenReturn(List.of(pDone, pTodo, pDropped, pOut));

        WorklogEntryEntity eDone = new WorklogEntryEntity();
        eDone.setPlanItemId(11L);
        eDone.setStatus(WorklogEntryEntity.STATUS_DONE);
        eDone.setMinutes(180);
        WorklogEntryEntity eTodo = new WorklogEntryEntity();
        eTodo.setPlanItemId(12L);
        eTodo.setStatus(WorklogEntryEntity.STATUS_TODO);
        eTodo.setMinutes(60);
        when(entryRepo.findByPlanItemIdIn(any())).thenReturn(List.of(eDone, eTodo));

        GoalTreeView tree = service.tree(2026);
        GoalTreeView.QuarterSection q4 = tree.quarters().stream()
                .filter(s -> "2026-Q4".equals(s.period())).findFirst().orElseThrow();
        List<GoalView> krs = q4.objectives().get(0).krs();
        GoalView kv = krs.stream().filter(v -> v.id() == 2L).findFirst().orElseThrow();
        assertEquals(2, kv.linkedPlanTotal(), "DROPPED/期外不计分母");
        assertEquals(1, kv.linkedPlanDone());
        assertEquals(50, kv.suggestedProgress());
        assertEquals(3.0, kv.linkedHours(), "工时只计 DONE 条目");

        GoalView kvEmpty = krs.stream().filter(v -> v.id() == 3L).findFirst().orElseThrow();
        assertEquals(0, kvEmpty.linkedPlanTotal());
        assertNull(kvEmpty.suggestedProgress(), "无关联项建议进度为 null");
        // 派生值不回写：实体 progress 仍是人工值
        assertEquals(0, kr.getProgress());
    }

    // ---------------- FR-06：可关联 KR ----------------

    @Test
    void linkableKrs_当季ACTIVE_KR附父O标题() {
        WorklogGoalEntity qO = goal(1, "QUARTER", "2026-Q4", "OBJECTIVE", null);
        WorklogGoalEntity kr = goal(2, "QUARTER", "2026-Q4", "KEY_RESULT", 1L);
        when(goalRepo.findByUserIdAndScopeAndPeriodAndKindAndStatusOrderBySortOrderAscIdAsc(
                ME, "QUARTER", "2026-Q4", "KEY_RESULT", "ACTIVE")).thenReturn(List.of(kr));
        when(goalRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(qO));

        List<LinkableKrView> v = service.linkableKrs(LocalDate.of(2026, 10, 26));
        assertEquals(1, v.size());
        assertEquals(2L, v.get(0).id());
        assertEquals("2026-Q4", v.get(0).period());
        assertEquals("目标1", v.get(0).objectiveTitle());
    }
}
