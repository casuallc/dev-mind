package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.worklog.dto.EntryRequest;
import com.devmind.worklog.dto.PlanItemRequest;
import com.devmind.worklog.dto.PlanItemView;
import com.devmind.worklog.dto.PlanListView;
import com.devmind.worklog.dto.SuggestedPlanItem;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.model.WorklogGoalEntity;
import com.devmind.worklog.model.WorklogPlanItemEntity;
import com.devmind.worklog.repo.WorklogEntryRepository;
import com.devmind.worklog.repo.WorklogGoalRepository;
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
 * CAP-88 M1 计划项：CRUD/状态机/滚转幂等/条目关联联动；CAP-90 M1：DAY scope 退役（全 400）、
 * 条目状态语义与日粒度滚转。Mockito 直桩仓储与身份（同模块既有测试风格，不起 Spring 上下文）。
 */
class WorklogPlanServiceTest {

    private static final String ME = "alice";
    private static final LocalDate MON = LocalDate.of(2026, 10, 26);   // 周一
    private static final LocalDate WED = LocalDate.of(2026, 10, 28);   // 周三

    private WorklogPlanItemRepository planRepo;
    private WorklogEntryRepository entryRepo;
    private WorklogGoalRepository goalRepo;
    private WorklogPlanService service;
    private WorklogEntryService entryService;

    @BeforeEach
    void setUp() {
        planRepo = mock(WorklogPlanItemRepository.class);
        entryRepo = mock(WorklogEntryRepository.class);
        goalRepo = mock(WorklogGoalRepository.class);
        IdentityService identity = mock(IdentityService.class);
        when(identity.currentActor()).thenReturn(ME);
        when(planRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(entryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new WorklogPlanService(planRepo, entryRepo, goalRepo, identity);
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
        PlanItemView v = service.create(new PlanItemRequest("WEEK", WED, "本周目标", null, null, 4.0, null, null, null, null));
        assertEquals(MON, v.planDate());
        assertEquals("TODO", v.status());
        assertEquals("MANUAL", v.source());
        assertEquals(4.0, v.estimateHours());
    }

    @Test
    void CAP90日计划退役_DAY与非法scope全400() {
        // DAY 已并入条目：create/update/carry 全拒绝
        assertThrows(DevMindException.class,
                () -> service.create(new PlanItemRequest("DAY", WED, "x", null, null, null, null, null, null, null)));
        assertThrows(DevMindException.class,
                () -> service.create(new PlanItemRequest("MONTH", MON, "x", null, null, null, null, null, null, null)));
        assertThrows(DevMindException.class,
                () -> service.create(new PlanItemRequest("WEEK", WED, "x", null, "BOGUS", null, null, null, null, null)));
        assertThrows(DevMindException.class,
                () -> service.update(1L, new PlanItemRequest("DAY", WED, "x", null, null, null, null, null, null, null)));
        assertThrows(DevMindException.class,
                () -> service.carry("DAY", WED, List.of(1L)));
        assertThrows(DevMindException.class, () -> service.list("DAY", WED));
    }

    @Test
    void 列表附上一周期未完成提示() {
        WorklogPlanItemEntity thisWeek = item(1, "WEEK", MON, "TODO");
        WorklogPlanItemEntity prevTodo = item(2, "WEEK", MON.minusWeeks(1), "DOING");
        when(planRepo.findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(ME, "WEEK", MON))
                .thenReturn(List.of(thisWeek));
        when(planRepo.findByUserIdAndScopeAndPlanDateAndStatusInOrderBySortOrderAscIdAsc(
                ME, "WEEK", MON.minusWeeks(1), WorklogPlanService.UNFINISHED))
                .thenReturn(List.of(prevTodo));

        PlanListView view = service.list("WEEK", MON);
        assertEquals(1, view.items().size());
        assertEquals(MON.minusWeeks(1), view.carryoverFrom());
        assertEquals(1, view.carryoverItems().size());
        assertEquals(2L, view.carryoverItems().get(0).id());
    }

    @Test
    void 人工改状态合法值生效_CARRIED不可人工设() {
        WorklogPlanItemEntity e = item(1, "WEEK", MON, "DOING");
        when(planRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(e));

        PlanItemView done = service.update(1L, new PlanItemRequest("WEEK", MON, "计划1", null, null, null, null, null, null, "DONE"));
        assertEquals("DONE", done.status());

        assertThrows(DevMindException.class,
                () -> service.update(1L, new PlanItemRequest("WEEK", MON, "计划1", null, null, null, null, null, null, "CARRIED")));
    }

    @Test
    void 已滚转项只读() {
        WorklogPlanItemEntity e = item(1, "WEEK", MON, "CARRIED");
        when(planRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(e));
        assertThrows(DevMindException.class,
                () -> service.update(1L, new PlanItemRequest("WEEK", MON, "改名", null, null, null, null, null, null, null)));
    }

    @Test
    void 删计划项清条目引用() {
        WorklogPlanItemEntity e = item(1, "WEEK", MON, "TODO");
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
    void 已完结项不滚转_不存在报404() {
        WorklogPlanItemEntity done = item(1, "WEEK", MON.minusWeeks(1), "DONE");
        when(planRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(done));

        var r = service.carry("WEEK", MON, List.of(1L));
        assertEquals(0, r.carried());
        assertEquals(1, r.skipped());

        assertThrows(DevMindException.class, () -> service.carry("WEEK", MON, List.of(999L)));
    }

    @Test
    void 条目关联触发TODO变DOING_他人计划项404_DAY项拒挂() {
        WorklogPlanItemEntity todo = item(7, "WEEK", MON, "TODO");
        WorklogPlanItemEntity doing = item(8, "WEEK", MON, "DOING");
        WorklogPlanItemEntity dayItem = item(9, "DAY", WED, "TODO");
        when(planRepo.findByIdAndUserId(7L, ME)).thenReturn(Optional.of(todo));
        when(planRepo.findByIdAndUserId(8L, ME)).thenReturn(Optional.of(doing));
        when(planRepo.findByIdAndUserId(9L, ME)).thenReturn(Optional.of(dayItem));

        var v1 = entryService.create(new EntryRequest(WED, "干活", null, "DEV", 1.0, null, null, 7L, null, null));
        assertEquals(7L, v1.planItemId());
        assertEquals("DOING", todo.getStatus());

        // 已是 DOING 的项不重复回写（状态保持）
        var v2 = entryService.create(new EntryRequest(WED, "继续干", null, "DEV", 0.5, null, null, 8L, null, null));
        assertEquals(8L, v2.planItemId());
        assertEquals("DOING", doing.getStatus());

        // 他人/不存在的计划项 → 404
        assertThrows(DevMindException.class,
                () -> entryService.create(new EntryRequest(WED, "x", null, "DEV", 1.0, null, null, 999L, null, null)));
        // CAP-90：DAY 项不可关联（日计划已并入条目）
        assertThrows(DevMindException.class,
                () -> entryService.create(new EntryRequest(WED, "x", null, "DEV", 1.0, null, null, 9L, null, null)));
    }

    @Test
    void linkable只列当周周计划由仓储查询保证_服务层只做周归一() {
        when(planRepo.findLinkable(ME, MON)).thenReturn(List.of(item(1, "WEEK", MON, "TODO")));
        List<PlanItemView> v = service.linkable(WED);
        assertEquals(1, v.size());
        assertTrue(v.get(0).planDate().equals(MON));
    }

    // ---------------- CAP-90 M1：条目状态语义 ----------------

    @Test
    void 新建条目默认DONE_显式TODO落计划态_CARRIED人工不可设() {
        var done = entryService.create(new EntryRequest(WED, "补录昨天", null, "DEV", 1.0, null, null, null, null, null));
        assertEquals("DONE", done.status(), "手动新建默认 DONE（补录已发生的）");
        assertNull(done.estimateHours());

        var todo = entryService.create(new EntryRequest(WED, "明天要修", null, "DEV", 0.0, null, null, null, "TODO", 2.0));
        assertEquals("TODO", todo.status());
        assertEquals(2.0, todo.estimateHours());

        assertThrows(DevMindException.class,
                () -> entryService.create(new EntryRequest(WED, "x", null, "DEV", 0.0, null, null, null, "CARRIED", null)));
    }

    @Test
    void GIT与AGENT来源条目恒DONE_转非DONE报400() {
        WorklogEntryEntity git = new WorklogEntryEntity();
        git.setId("g1");
        git.setUserId(ME);
        git.setSource(WorklogEntryEntity.SOURCE_GIT);
        git.setStatus(WorklogEntryEntity.STATUS_DONE);
        when(entryRepo.findByIdAndUserId("g1", ME)).thenReturn(Optional.of(git));

        assertThrows(DevMindException.class,
                () -> entryService.update("g1", new EntryRequest(WED, "x", null, "DEV", 1.0, null, null, null, "TODO", null)));
        // 显式传 DONE / 不传 status 均放行
        entryService.update("g1", new EntryRequest(WED, "x", null, "DEV", 1.0, null, null, null, "DONE", null));
        entryService.update("g1", new EntryRequest(WED, "x", null, "DEV", 1.0, null, null, null, null, null));
    }

    // ---------------- CAP-90 M1：日粒度滚转 ----------------

    private WorklogEntryEntity entry(String id, LocalDate date, String status) {
        WorklogEntryEntity e = new WorklogEntryEntity();
        e.setId(id);
        e.setUserId(ME);
        e.setWorkDate(date);
        e.setTitle("条目" + id);
        e.setEntryType("DEV");
        e.setMinutes(0);
        e.setEstimateMinutes(120);
        e.setStatus(status);
        e.setSource(WorklogEntryEntity.SOURCE_MANUAL);
        return e;
    }

    @Test
    void 条目滚转生成TODO副本带链_原行置CARRIED_幂等() {
        WorklogEntryEntity src = entry("e1", WED.minusDays(1), "DOING");
        when(entryRepo.findByIdAndUserId("e1", ME)).thenReturn(Optional.of(src));

        var r1 = entryService.carry(WED, List.of("e1"));
        assertEquals(1, r1.carried());
        assertEquals(0, r1.skipped());
        assertEquals("CARRIED", src.getStatus());
        var copy = r1.items().get(0);
        assertEquals("TODO", copy.status());
        assertEquals(WED, copy.workDate());
        assertEquals("e1", copy.carriedFromId());
        assertEquals(2.0, copy.estimateHours(), "estimate 沿用");
        assertEquals(0.0, copy.hours(), "副本工时归零");
        assertTrue(!"e1".equals(copy.id()), "副本须新 uuid");

        // 重复滚转：已 CARRIED → 跳过（幂等）
        var r2 = entryService.carry(WED, List.of("e1"));
        assertEquals(0, r2.carried());
        assertEquals(1, r2.skipped());
    }

    @Test
    void 已完结条目不滚转_他人条目404() {
        WorklogEntryEntity done = entry("e2", WED.minusDays(1), "DONE");
        when(entryRepo.findByIdAndUserId("e2", ME)).thenReturn(Optional.of(done));

        var r = entryService.carry(WED, List.of("e2"));
        assertEquals(0, r.carried());
        assertEquals(1, r.skipped());

        assertThrows(DevMindException.class, () -> entryService.carry(WED, List.of("ghost")));
    }

    @Test
    void 日滚转提示取昨日未完成() {
        WorklogEntryEntity yTodo = entry("e3", WED.minusDays(1), "TODO");
        when(entryRepo.findByUserIdAndWorkDateAndStatusInOrderByCreatedAtAsc(
                ME, WED.minusDays(1), WorklogEntryService.UNFINISHED)).thenReturn(List.of(yTodo));
        List<com.devmind.worklog.dto.EntryView> hint = entryService.dayCarryover(WED);
        assertEquals(1, hint.size());
        assertEquals("e3", hint.get(0).id());
    }

    // ---------------- CAP-91 M1：计划项 KR 对齐 ----------------

    @Test
    void 计划项挂KR_非KR或他人目标400_null放行() {
        // null krId = 不对齐，放行
        PlanItemView v = service.create(new PlanItemRequest("WEEK", WED, "无对齐", null, null, null, null, null, null, null));
        assertNull(v.krId());

        // 指向 OBJECTIVE → 400
        WorklogGoalEntity objective = new WorklogGoalEntity();
        objective.setId(50L);
        objective.setUserId(ME);
        objective.setKind(WorklogGoalEntity.KIND_OBJECTIVE);
        when(goalRepo.findByIdAndUserId(50L, ME)).thenReturn(Optional.of(objective));
        assertThrows(DevMindException.class,
                () -> service.create(new PlanItemRequest("WEEK", WED, "x", null, null, null, null, null, 50L, null)));

        // 他人/不存在 → 400
        assertThrows(DevMindException.class,
                () -> service.create(new PlanItemRequest("WEEK", WED, "x", null, null, null, null, null, 999L, null)));

        // 合法 KR → 落库
        WorklogGoalEntity kr = new WorklogGoalEntity();
        kr.setId(51L);
        kr.setUserId(ME);
        kr.setKind(WorklogGoalEntity.KIND_KEY_RESULT);
        when(goalRepo.findByIdAndUserId(51L, ME)).thenReturn(Optional.of(kr));
        PlanItemView ok = service.create(new PlanItemRequest("WEEK", WED, "对齐KR", null, null, null, null, null, 51L, null));
        assertEquals(51L, ok.krId());
    }

    @Test
    void 列表响应附krTitles映射() {
        WorklogPlanItemEntity withKr = item(1, "WEEK", MON, "TODO");
        withKr.setKrId(51L);
        when(planRepo.findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(ME, "WEEK", MON))
                .thenReturn(List.of(withKr));
        WorklogGoalEntity kr = new WorklogGoalEntity();
        kr.setId(51L);
        kr.setTitle("KR1 覆盖率 80%");
        when(goalRepo.findAllById(java.util.Set.of(51L))).thenReturn(List.of(kr));

        PlanListView view = service.list("WEEK", MON);
        assertEquals("KR1 覆盖率 80%", view.krTitles().get(51L));
    }

    @Test
    void 滚转副本沿用krId() {
        WorklogPlanItemEntity src = item(1, "WEEK", MON.minusWeeks(1), "DOING");
        src.setKrId(51L);
        when(planRepo.findByIdAndUserId(1L, ME)).thenReturn(Optional.of(src));

        var r = service.carry("WEEK", MON, List.of(1L));
        assertEquals(51L, r.items().get(0).krId());
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
                new SuggestedPlanItem("承接登录改造收尾", "DEV", 120, null),
                new SuggestedPlanItem("收尾登录改造", "DEV", null, null),   // 与已有项撞题 → 跳过
                new SuggestedPlanItem("  承接登录改造收尾  ", "DEV", 60, null), // 同批重复（忽略首尾空白）→ 跳过
                new SuggestedPlanItem("对齐下周评审口径", "MEETING", null, null)));
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

    @Test
    void 建议项KR标注校验归属_非法置空不炸批() {
        // MON=2026-10-26 → 当季 2026-Q4；合法 = 本人 + KEY_RESULT + 当季，其余一律置空
        when(goalRepo.findAllById(any())).thenReturn(List.of(
                goal(51L, ME, "KEY_RESULT", "2026-Q4"),
                goal(52L, "bob", "KEY_RESULT", "2026-Q4"),   // 他人 KR
                goal(53L, ME, "OBJECTIVE", "2026-Q4"),      // O 不是 KR
                goal(54L, ME, "KEY_RESULT", "2026-Q3")));   // 他季 KR
        int inserted = service.insertSuggestions(ME, MON, List.of(
                new SuggestedPlanItem("对齐合法KR", "DEV", null, 51L),
                new SuggestedPlanItem("标注他人KR", "DEV", null, 52L),
                new SuggestedPlanItem("标注O", "DEV", null, 53L),
                new SuggestedPlanItem("标注他季KR", "DEV", null, 54L),
                new SuggestedPlanItem("标注不存在KR", "DEV", null, 99L),
                new SuggestedPlanItem("不标注", "DEV", null, null)));
        assertEquals(6, inserted, "非法标注不炸整批");
        ArgumentCaptor<WorklogPlanItemEntity> cap = ArgumentCaptor.forClass(WorklogPlanItemEntity.class);
        verify(planRepo, times(6)).save(cap.capture());
        List<WorklogPlanItemEntity> saved = cap.getAllValues();
        assertEquals(51L, saved.get(0).getKrId(), "合法标注落 krId");
        for (int i = 1; i < saved.size(); i++) {
            assertNull(saved.get(i).getKrId(), "非法/未标注置空: " + saved.get(i).getTitle());
        }
    }

    private static WorklogGoalEntity goal(long id, String userId, String kind, String period) {
        WorklogGoalEntity g = new WorklogGoalEntity();
        g.setId(id);
        g.setUserId(userId);
        g.setKind(kind);
        g.setPeriod(period);
        return g;
    }
}
