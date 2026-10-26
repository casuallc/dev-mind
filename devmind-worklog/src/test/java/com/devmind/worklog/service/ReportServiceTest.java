package com.devmind.worklog.service;

import com.devmind.common.requirement.WipRequirementProvider;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.SessionView;
import com.devmind.session.service.SessionManagerService;
import com.devmind.worklog.model.WeeklyReportEntity;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.model.WorklogPlanItemEntity;
import com.devmind.worklog.repo.WeeklyReportRepository;
import com.devmind.worklog.repo.WorklogEntryRepository;
import com.devmind.worklog.repo.WorklogPlanItemRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    @Test
    void splitWeeklySplitsByPlanHeading() {
        String body = ReportService.SUMMARY_HEADING + "\n- 完成 A\n- 推进 B\n\n"
                + ReportService.PLAN_HEADING + "\n- 继续 B\n";
        String[] parts = ReportService.splitWeekly(body);
        assertEquals("- 完成 A\n- 推进 B", parts[0]);
        assertEquals("- 继续 B", parts[1]);
    }

    @Test
    void splitWeeklyFallbackAllToSummary() {
        String[] parts = ReportService.splitWeekly("- 完成 A");
        assertEquals("- 完成 A", parts[0]);
        assertEquals("", parts[1]);
        assertEquals("", ReportService.splitWeekly(null)[0]);
    }

    @Test
    void splitWeekly去掉孤立总结标题() {
        // 迁移/回传往返命根子：正文只有总结节标题时也要剥掉，否则 reassemble 后标题翻倍
        String[] parts = ReportService.splitWeekly(ReportService.SUMMARY_HEADING + "\n\n- 完成 A\n");
        assertEquals("- 完成 A", parts[0]);
        assertEquals("", parts[1]);
    }

    @Test
    void assembleWeekly与splitWeekly往返一致() {
        String assembled = ReportService.assembleWeekly("- 完成 A", "- 继续 B");
        String[] parts = ReportService.splitWeekly(assembled);
        assertEquals("- 完成 A", parts[0]);
        assertEquals("- 继续 B", parts[1]);
        // plan 为空省略尾部，且再拆不丢内容
        String noPlan = ReportService.assembleWeekly("- 完成 A", "");
        String[] p2 = ReportService.splitWeekly(noPlan);
        assertEquals("- 完成 A", p2[0]);
        assertEquals("", p2[1]);
    }

    @Test
    void 上周无计划项时计划文本回退上周周报nextPlan列() {
        WeeklyReportRepository weeklyRepo = mock(WeeklyReportRepository.class);
        WorklogPlanItemRepository planRepo = mock(WorklogPlanItemRepository.class);
        when(planRepo.findByUserIdAndPlanDateBetweenOrderByPlanDateAscSortOrderAscIdAsc(
                anyString(), any(), any())).thenReturn(List.of());
        WeeklyReportEntity withPlan = new WeeklyReportEntity();
        withPlan.setSummaryMd("- 完成 A");
        withPlan.setNextPlanMd("- 完成 B\n- 推进 C");
        WeeklyReportEntity noPlan = new WeeklyReportEntity();
        noPlan.setSummaryMd("只有总结");
        noPlan.setNextPlanMd("");
        when(weeklyRepo.findByUserIdAndWeekStart("alice", LocalDate.of(2026, 10, 12)))
                .thenReturn(Optional.of(withPlan));
        when(weeklyRepo.findByUserIdAndWeekStart("alice", LocalDate.of(2026, 10, 5)))
                .thenReturn(Optional.of(noPlan));
        when(weeklyRepo.findByUserIdAndWeekStart("alice", LocalDate.of(2026, 9, 28)))
                .thenReturn(Optional.empty());

        ReportService service = new ReportService(null, null, null, null, null, null, weeklyRepo,
                planRepo, null, null);

        assertEquals("- 完成 B\n- 推进 C", service.lastWeekPlan("alice", LocalDate.of(2026, 10, 19)));
        // 上周周报没有计划时返回空串，模板据此省略跟进块
        assertEquals("", service.lastWeekPlan("alice", LocalDate.of(2026, 10, 12)));
        // 上周无周报同样空串
        assertEquals("", service.lastWeekPlan("alice", LocalDate.of(2026, 10, 5)));
    }

    @Test
    void 上周有计划项时lastWeekPlan升级为结构化清单() {
        WeeklyReportRepository weeklyRepo = mock(WeeklyReportRepository.class);
        WorklogPlanItemRepository planRepo = mock(WorklogPlanItemRepository.class);
        WorklogEntryRepository entryRepo = mock(WorklogEntryRepository.class);
        LocalDate prevMonday = LocalDate.of(2026, 10, 12);
        WorklogPlanItemEntity item = new WorklogPlanItemEntity();
        item.setId(7L);
        item.setScope(WorklogPlanItemEntity.SCOPE_WEEK);
        item.setPlanDate(prevMonday);
        item.setTitle("收尾登录改造");
        item.setPlanType("DEV");
        item.setStatus(WorklogPlanItemEntity.STATUS_DONE);
        when(planRepo.findByUserIdAndPlanDateBetweenOrderByPlanDateAscSortOrderAscIdAsc(
                "alice", prevMonday, prevMonday.plusDays(6))).thenReturn(List.of(item));
        WorklogEntryEntity linked = new WorklogEntryEntity();
        linked.setWorkDate(LocalDate.of(2026, 10, 13));
        linked.setTitle("执行条目");
        linked.setMinutes(60);
        linked.setPlanItemId(7L);
        when(entryRepo.findByPlanItemIdIn(List.of(7L))).thenReturn(List.of(linked));

        ReportService service = new ReportService(null, null, null, null, null, null, weeklyRepo,
                planRepo, entryRepo, null);

        String block = service.lastWeekPlan("alice", LocalDate.of(2026, 10, 19));
        assertTrue(block.contains("[周计划] [状态=已完成] 收尾登录改造"), "缺结构化计划项: " + block);
        assertTrue(block.contains("关联条目：执行条目"), "缺关联条目: " + block);
    }

    // ---------------- CAP-88 M3：AI 建议计划 ----------------

    @Test
    void 目标周已有计划项时suggest幂等跳过() {
        WorklogPlanItemRepository planRepo = mock(WorklogPlanItemRepository.class);
        LocalDate mon = LocalDate.of(2026, 11, 2);
        when(planRepo.findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(
                "alice", "WEEK", mon)).thenReturn(List.of(new WorklogPlanItemEntity()));
        ReportService service = new ReportService(null, null, null, null, null, null, null,
                planRepo, null, null);
        var launch = service.suggestPlan("alice", LocalDate.of(2026, 11, 4), false, null);
        assertTrue(launch.reused(), "已有项非 force 须幂等跳过");
        assertNull(launch.sessionId());
    }

    @Test
    void suggest素材只承接上周未完成与滚转项_缺席SPI降级() {
        WorklogPlanItemRepository planRepo = mock(WorklogPlanItemRepository.class);
        WorklogEntryRepository entryRepo = mock(WorklogEntryRepository.class);
        LocalDate targetMon = LocalDate.of(2026, 11, 2);
        LocalDate prevMon = LocalDate.of(2026, 10, 26);
        // 目标周无项（走生成）；上周四项：TODO/CARRIED/DONE/DROPPED 各一
        WorklogPlanItemEntity todo = wipItem(1L, "TODO");
        WorklogPlanItemEntity carried = wipItem(2L, "CARRIED");
        WorklogPlanItemEntity done = wipItem(3L, "DONE");
        WorklogPlanItemEntity dropped = wipItem(4L, "DROPPED");
        when(planRepo.findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(
                "alice", "WEEK", targetMon)).thenReturn(List.of());
        when(planRepo.findByUserIdAndPlanDateBetweenOrderByPlanDateAscSortOrderAscIdAsc(
                "alice", prevMon, prevMon.plusDays(6)))
                .thenReturn(List.of(todo, carried, done, dropped));
        when(entryRepo.findByPlanItemIdIn(any())).thenReturn(List.of());
        // SPI 缺席（getIfAvailable → null）：降级只用未完成项素材
        @SuppressWarnings("unchecked")
        ObjectProvider<WipRequirementProvider> wipProvider = mock(ObjectProvider.class);
        when(wipProvider.getIfAvailable()).thenReturn(null);

        WorklogWorkspaceService workspaceService = mock(WorklogWorkspaceService.class);
        when(workspaceService.ensureFor("alice"))
                .thenReturn(new com.devmind.worklog.dto.WorkspaceView(true, "p-wl", null, null));
        SessionManagerService sessionManager = mock(SessionManagerService.class);
        ArgumentCaptor<CreateSessionRequest> reqCap = ArgumentCaptor.forClass(CreateSessionRequest.class);
        SessionView created = mock(SessionView.class);
        when(created.id()).thenReturn("s-1");
        when(sessionManager.create(reqCap.capture())).thenReturn(created);

        ReportService service = new ReportService(null, null, null, workspaceService, sessionManager,
                null, null, planRepo, entryRepo, wipProvider);
        var launch = service.suggestPlan("alice", LocalDate.of(2026, 11, 3), false, "多排评审");
        assertEquals("s-1", launch.sessionId());
        assertFalse(launch.reused());

        CreateSessionRequest req = reqCap.getValue();
        assertEquals(WorklogScenarios.PLAN_SUGGEST, req.scenarioCode());
        String prompt = req.taskSpec();
        assertTrue(prompt.contains("2026-11-02 周"), "目标周归一到周一: " + prompt);
        assertTrue(prompt.contains("计划1") && prompt.contains("计划2"), "TODO/CARRIED 须进素材");
        assertFalse(prompt.contains("计划3") || prompt.contains("计划4"), "DONE/DROPPED 不得进素材");
        assertTrue(prompt.contains("（无在办需求数据）"), "SPI 缺席降级提示");
        assertTrue(prompt.contains("多排评审"), "hint 未注入");
    }

    private static WorklogPlanItemEntity wipItem(long id, String status) {
        WorklogPlanItemEntity e = new WorklogPlanItemEntity();
        e.setId(id);
        e.setScope("WEEK");
        e.setPlanDate(LocalDate.of(2026, 10, 26));
        e.setTitle("计划" + id);
        e.setPlanType("DEV");
        e.setStatus(status);
        return e;
    }
}
