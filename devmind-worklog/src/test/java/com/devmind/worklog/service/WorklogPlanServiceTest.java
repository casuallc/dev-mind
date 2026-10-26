package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.worklog.dto.EntryRequest;
import com.devmind.worklog.dto.PlanItemRequest;
import com.devmind.worklog.dto.PlanItemView;
import com.devmind.worklog.dto.PlanListView;
import com.devmind.worklog.dto.SuggestedPlanItem;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.model.WorklogPlanItemEntity;
import com.devmind.worklog.repo.WorklogEntryRepository;
import com.devmind.worklog.repo.WorklogPlanItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-88 M1 计划项：CRUD/状态机/滚转幂等/条目关联联动。
 * Mockito 直桩仓储与身份（同模块既有测试风格，不起 Spring 上下文）。
 */
class WorklogPlanServiceTest {

    private static final String ME = "alice";
    private static final LocalDate MON = LocalDate.of(2026, 10, 26);   // 周一
    private static final LocalDate WED = LocalDate.of(2026, 10, 28);   // 周三

    private WorklogPlanItemRepository planRepo;
    private WorklogEntryRepository entryRepo;
    private WorklogPlanService service;
    private WorklogEntryService entryService;

    @BeforeEach
    void setUp() {
        planRepo = mock(WorklogPlanItemRepository.class);
        entryRepo = mock(WorklogEntryRepository.class);
        IdentityService identity = mock(IdentityService.class);
        when(identity.currentActor()).thenReturn(ME);
        when(planRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(entryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new WorklogPlanService(planRepo, entryRepo, identity);
        entryService = new WorklogEntryService(entryRepo, planItemRepoArg(), identity, null);
    }

    /** entryService 用同一个 planRepo mock（独立变量名防 setUp 顺序歧义）。 */
    private WorklogPlanItemRepository planItemRepoArg() {
        return planRepo;
    }

    private WorklogPlanItemEntity item(long id, String scope, LocalDate date, String status) {
        WorklogPlanItemEntity e = new WorklogPlanItemEntity();
        e.setId(id);
        e.setUserId(ME);
        e.setScope(scope);
        e.setPlanDate(date);
        e.setTitle("计划" + id);
        e.setPlanType(WorklogPlanItemEntity.SCOPE_DAY.equals(scope) ? "DEV" : "RESEARCH");
        e.setStatus(status);
        e.setSource(WorklogPlanItemEntity.SOURCE_MANUAL);
        e.setSortOrder(0);
        return e;
    }

    @Test
    void 周计划日期归一到周一() {
        PlanItemView v = service.create(new PlanItemRequest("WEEK", WED, "本周目标", null, null, 4.0, null, null, null));
        assertEquals(MON, v.planDate());
        assertEquals("TODO", v.status());
        assertEquals("MANUAL", v.source());
        assertEquals(4.0, v.estimateHours());
    }

    @Test
    void 非法scope与非法类型被拒() {
        assertThrows(DevMindException.class,
                () -> service.create(new PlanItemRequest("MONTH", MON, "x", null, null, null, null, null, null)));
        assertThrows(DevMindException.class,
                () -> service.create(new PlanItemRequest("DAY", WED, "x", null, "BOGUS", null, null, null, null)));
    }

    @Test
    void 列表附上一周期未完成提示() {
        WorklogPlanItemEntity today = item(1, "DAY", WED, "TODO");
        WorklogPlanItemEntity yTodo = item(2, "DAY", WED.minusDays(1), "DOING");
        when(planRepo.findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(ME, "DAY", WED))
                .thenReturn(List.of(today));
        when(planRepo.findByUserIdAndScopeAndPlanDateAndStatusInOrderBySortOrderAscIdAsc(
                ME, "DAY", WED.minusDays(1), WorklogPlanService.UNFINISHED))
                .thenReturn(List.of(yTodo));

        PlanListView view = service.list("DAY", WED);
        assertEquals(1, view.items().size());
        assertEquals(WED.minusDays(1), view.carryoverFrom());
        assertEquals(1, view.carryoverItems().size());
        assertEquals(2L, view.carryoverItems().get(0).id());
    }

    @Test
    void 人工改状态合法值生效_CARRIED不可人工设() {
        WorklogPlanItemEntity e = item(1, "DAY", WED, "DOING");
        when(planRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(e));

        PlanItemView done = service.update(1L, new PlanItemRequest("DAY", WED, "计划1", null, null, null, null, null, "DONE"));
        assertEquals("DONE", done.status());

        assertThrows(DevMindException.class,
                () -> service.update(1L, new PlanItemRequest("DAY", WED, "计划1", null, null, null, null, null, "CARRIED")));
    }

    @Test
    void 已滚转项只读() {
        WorklogPlanItemEntity e = item(1, "DAY", WED, "CARRIED");
        when(planRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(e));
        assertThrows(DevMindException.class,
                () -> service.update(1L, new PlanItemRequest("DAY", WED, "改名", null, null, null, null, null, null)));
    }

    @Test
    void 删计划项清条目引用() {
        WorklogPlanItemEntity e = item(1, "DAY", WED, "TODO");
        WorklogEntryEntity linked = new WorklogEntryEntity();
        linked.setId("abc");
        linked.setPlanItemId(1L);
        when(planRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(e));
        when(entryRepo.findByPlanItemId(1L)).thenReturn(List.of(linked));

        service.delete(1L);
        assertNull(linked.getPlanItemId());
    }

    @Test
    void 滚转生成副本且原项置CARRIED_重复滚转幂等() {
        WorklogPlanItemEntity src = item(1, "WEEK", MON.minusWeeks(1), "DOING");
        AtomicLong idSeq = new AtomicLong(100);
        when(planRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(src));
        when(planRepo.save(any())).thenAnswer(inv -> {
            WorklogPlanItemEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(idSeq.incrementAndGet());
            }
            return e;
        });

        var r1 = service.carry("WEEK", MON, List.of(1L));
        assertEquals(1, r1.carried());
        assertEquals(0, r1.skipped());
        assertEquals("CARRIED", src.getStatus());
        PlanItemView copy = r1.items().get(0);
        assertEquals("TODO", copy.status());
        assertEquals(MON, copy.planDate());
        assertEquals(1L, copy.carriedFromId());

        // 重复滚转同一原项：已 CARRIED → 跳过
        var r2 = service.carry("WEEK", MON, List.of(1L));
        assertEquals(0, r2.carried());
        assertEquals(1, r2.skipped());
    }

    @Test
    void 已完结项不滚转_scope不匹配报400() {
        WorklogPlanItemEntity done = item(1, "WEEK", MON.minusWeeks(1), "DONE");
        WorklogPlanItemEntity dayItem = item(2, "DAY", WED, "TODO");
        when(planRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(done));
        when(planRepo.findByIdAndUserId(2L, ME)).thenReturn(Optional.of(dayItem));

        var r = service.carry("WEEK", MON, List.of(1L));
        assertEquals(0, r.carried());
        assertEquals(1, r.skipped());

        assertThrows(DevMindException.class, () -> service.carry("WEEK", MON, List.of(2L)));
        assertThrows(DevMindException.class, () -> service.carry("WEEK", MON, List.of(999L)));
    }

    @Test
    void 条目关联触发TODO变DOING_他人计划项404() {
        WorklogPlanItemEntity todo = item(7, "DAY", WED, "TODO");
        WorklogPlanItemEntity doing = item(8, "DAY", WED, "DOING");
        when(planRepo.findByIdAndUserId(7L, ME)).thenReturn(Optional.of(todo));
        when(planRepo.findByIdAndUserId(8L, ME)).thenReturn(Optional.of(doing));

        var v1 = entryService.create(new EntryRequest(WED, "干活", null, "DEV", 1.0, null, null, 7L));
        assertEquals(7L, v1.planItemId());
        assertEquals("DOING", todo.getStatus());

        // 已是 DOING 的项不重复回写（状态保持）
        doing.setStatus("DOING");
        var v2 = entryService.create(new EntryRequest(WED, "继续干", null, "DEV", 0.5, null, null, 8L));
        assertEquals(8L, v2.planItemId());
        assertEquals("DOING", doing.getStatus());

        // 他人/不存在的计划项 → 404
        assertThrows(DevMindException.class,
                () -> entryService.create(new EntryRequest(WED, "x", null, "DEV", 1.0, null, null, 999L)));
    }

    @Test
    void linkable排除DROPPED与CARRIED由仓储查询保证_服务层只做周归一() {
        when(planRepo.findLinkable(ME, WED, MON)).thenReturn(List.of(item(1, "DAY", WED, "TODO")));
        List<PlanItemView> v = service.linkable(WED);
        assertEquals(1, v.size());
        assertTrue(v.get(0).planDate().equals(WED));
    }

    // ---------------- CAP-88 M3：AI 建议项落库 ----------------

    @Test
    void 建议项追加落库_标题去重_来源与状态固定() {
        // 目标周已有「收尾登录改造」；同批建议自带重复标题
        WorklogPlanItemEntity existing = item(1, "WEEK", MON, "TODO");
        existing.setTitle("收尾登录改造");
        existing.setSortOrder(3);
        when(planRepo.findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(
                ME, "WEEK", MON)).thenReturn(List.of(existing));

        int inserted = service.insertSuggestions(ME, MON, List.of(
                new SuggestedPlanItem("承接登录改造收尾", "DEV", 120),
                new SuggestedPlanItem("收尾登录改造", "DEV", null),   // 与已有项撞题 → 跳过
                new SuggestedPlanItem("  承接登录改造收尾  ", "DEV", 60), // 同批重复（忽略首尾空白）→ 跳过
                new SuggestedPlanItem("对齐下周评审口径", "MEETING", null)));
        assertEquals(2, inserted);

        ArgumentCaptor<WorklogPlanItemEntity> cap = ArgumentCaptor.forClass(WorklogPlanItemEntity.class);
        verify(planRepo, times(2)).save(cap.capture());
        WorklogPlanItemEntity first = cap.getAllValues().get(0);
        assertEquals("承接登录改造收尾", first.getTitle());
        assertEquals("WEEK", first.getScope());
        assertEquals(MON, first.getPlanDate());
        assertEquals("TODO", first.getStatus());
        assertEquals("AI_SUGGEST", first.getSource());
        assertEquals(Integer.valueOf(120), first.getEstimateMinutes());
        assertEquals(Integer.valueOf(4), first.getSortOrder(), "sortOrder 从已有最大值续排");
        assertEquals(Integer.valueOf(5), cap.getAllValues().get(1).getSortOrder());
    }

    @Test
    void 建议项空产物零写库() {
        assertEquals(0, service.insertSuggestions(ME, MON, List.of()));
        assertEquals(0, service.insertSuggestions(ME, MON, null));
        verify(planRepo, times(0)).save(any());
    }
}
