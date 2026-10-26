package com.devmind.worklog.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.requirement.WipRequirementProvider;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.SessionView;
import com.devmind.session.service.SessionManagerService;
import com.devmind.worklog.dto.GoalTreeView;
import com.devmind.worklog.dto.GoalView;
import com.devmind.worklog.dto.UpdateQuarterlyRequest;
import com.devmind.worklog.model.WeeklyReportEntity;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.model.WorklogGoalEntity;
import com.devmind.worklog.model.WorklogPlanItemEntity;
import com.devmind.worklog.model.WorklogQuarterlyReportEntity;
import com.devmind.worklog.repo.WeeklyReportRepository;
import com.devmind.worklog.repo.WorklogEntryRepository;
import com.devmind.worklog.repo.WorklogGoalRepository;
import com.devmind.worklog.repo.WorklogPlanItemRepository;
import com.devmind.worklog.repo.WorklogQuarterlyReportRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
                planRepo, null, null, null, null, null);

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
        linked.setStatus(WorklogEntryEntity.STATUS_DONE);
        linked.setPlanItemId(7L);
        when(entryRepo.findByPlanItemIdIn(List.of(7L))).thenReturn(List.of(linked));

        ReportService service = new ReportService(null, null, null, null, null, null, weeklyRepo,
                planRepo, entryRepo, null, null, null, null);

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
                planRepo, null, null, null, null, null);
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
        // CAP-91 M3：目标周所在季（2026-Q4）一条进行中 KR → 进素材清单；DONE 的不进
        WorklogGoalRepository goalRepo = mock(WorklogGoalRepository.class);
        WorklogGoalEntity kr = new WorklogGoalEntity();
        kr.setId(12L);
        kr.setUserId("alice");
        kr.setKind("KEY_RESULT");
        kr.setPeriod("2026-Q4");
        kr.setTitle("完成 CAP-91 上线");
        kr.setStatus("ACTIVE");
        kr.setProgress(60);
        WorklogGoalEntity doneKr = new WorklogGoalEntity();
        doneKr.setId(13L);
        doneKr.setUserId("alice");
        doneKr.setKind("KEY_RESULT");
        doneKr.setPeriod("2026-Q4");
        doneKr.setTitle("已盖棺KR");
        doneKr.setStatus("DONE");
        when(goalRepo.findByUserIdAndPeriodInOrderBySortOrderAscIdAsc("alice", List.of("2026-Q4")))
                .thenReturn(List.of(kr, doneKr));

        WorklogWorkspaceService workspaceService = mock(WorklogWorkspaceService.class);
        when(workspaceService.ensureFor("alice"))
                .thenReturn(new com.devmind.worklog.dto.WorkspaceView(true, "p-wl", null, null));
        SessionManagerService sessionManager = mock(SessionManagerService.class);
        ArgumentCaptor<CreateSessionRequest> reqCap = ArgumentCaptor.forClass(CreateSessionRequest.class);
        SessionView created = mock(SessionView.class);
        when(created.id()).thenReturn("s-1");
        when(sessionManager.create(reqCap.capture())).thenReturn(created);

        ReportService service = new ReportService(null, null, null, workspaceService, sessionManager,
                null, null, planRepo, entryRepo, wipProvider, null, goalRepo, null);
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
        assertTrue(prompt.contains("KR#12") && prompt.contains("完成 CAP-91 上线"),
                "当季进行中 KR 须进素材: " + prompt);
        assertFalse(prompt.contains("已盖棺KR"), "DONE 的 KR 不进清单: " + prompt);
        assertTrue(prompt.contains("多排评审"), "hint 未注入");
    }

    // ---------------- CAP-90 M2：素材口径切换 + 下周计划投影 ----------------

    @Test
    void 日报计划对照块改由当日未完成条目与周计划驱动() {
        WorklogEntryRepository entryRepo = mock(WorklogEntryRepository.class);
        WorklogPlanItemRepository planRepo = mock(WorklogPlanItemRepository.class);
        LocalDate wed = LocalDate.of(2026, 10, 28);
        LocalDate mon = LocalDate.of(2026, 10, 26);
        WorklogEntryEntity unfinished = new WorklogEntryEntity();
        unfinished.setId("e1");
        unfinished.setWorkDate(wed);
        unfinished.setTitle("修登录页");
        unfinished.setEntryType("DEV");
        unfinished.setStatus("TODO");
        unfinished.setEstimateMinutes(120);
        when(entryRepo.findByUserIdAndWorkDateAndStatusInOrderByCreatedAtAsc(
                "alice", wed, WorklogEntryService.UNFINISHED)).thenReturn(List.of(unfinished));
        WorklogPlanItemEntity week = wipItem(5L, "TODO");
        week.setPlanDate(mon);
        when(planRepo.findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(
                "alice", "WEEK", mon)).thenReturn(List.of(week));
        when(entryRepo.findByPlanItemIdIn(List.of(5L))).thenReturn(List.of());

        ReportService service = new ReportService(null, null, null, null, null, null, null,
                planRepo, entryRepo, null, null, null, null);
        String block = service.dayPlanBlock("alice", wed);
        assertTrue(block.contains("当日未完成条目"), "缺未完成条目小节: " + block);
        assertTrue(block.contains("修登录页"), "缺条目行: " + block);
        assertTrue(block.contains("当周周计划项"), "缺周计划小节: " + block);
        assertTrue(block.contains("计划5"), "缺周计划行: " + block);
    }

    @Test
    void 下周计划预览确定性渲染_无计划项返回空() {
        WorklogPlanItemRepository planRepo = mock(WorklogPlanItemRepository.class);
        LocalDate mon = LocalDate.of(2026, 10, 26);
        WorklogPlanItemEntity next = wipItem(9L, "TODO");
        next.setPlanDate(mon.plusWeeks(1));
        next.setTitle("收尾周报链路");
        next.setEstimateMinutes(180);
        when(planRepo.findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(
                "alice", "WEEK", mon.plusWeeks(1))).thenReturn(List.of(next));

        ReportService service = new ReportService(null, null, null, null, null, null, null,
                planRepo, null, null, null, null, null);
        var preview = service.nextPlanPreview("alice", mon);
        assertEquals(1, preview.itemCount());
        assertTrue(preview.markdown().contains("1. [DEV] 收尾周报链路（预估 3h）"), preview.markdown());

        when(planRepo.findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(
                "alice", "WEEK", mon.plusWeeks(1))).thenReturn(List.of());
        var empty = service.nextPlanPreview("alice", mon);
        assertEquals(0, empty.itemCount());
        assertEquals("", empty.markdown());
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

    // ---------------- CAP-91 M2：季度复盘 ----------------

    @Test
    void 季度复盘非force已有行幂等跳过() {
        WorklogQuarterlyReportRepository quarterlyRepo = mock(WorklogQuarterlyReportRepository.class);
        when(quarterlyRepo.existsByUserIdAndQuarter("alice", "2026-Q4")).thenReturn(true);
        ReportService service = new ReportService(null, null, null, null, null, null, null,
                null, null, null, quarterlyRepo, null, null);
        var launch = service.generateQuarterly("alice", "2026-Q4", false, null);
        assertTrue(launch.reused(), "已有行非 force 须幂等跳过");
        assertNull(launch.sessionId());
    }

    @Test
    void 季度复盘预检无目标且无完成条目时400() {
        WorklogQuarterlyReportRepository quarterlyRepo = mock(WorklogQuarterlyReportRepository.class);
        WorklogGoalRepository goalRepo = mock(WorklogGoalRepository.class);
        WorklogEntryRepository entryRepo = mock(WorklogEntryRepository.class);
        when(goalRepo.findByUserIdAndPeriodInOrderBySortOrderAscIdAsc("alice", List.of("2026-Q4")))
                .thenReturn(List.of());
        WorklogEntryEntity todo = new WorklogEntryEntity();
        todo.setStatus("TODO");
        when(entryRepo.findRange("alice", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(todo));
        ReportService service = new ReportService(null, null, null, null, null, null, null,
                null, entryRepo, null, quarterlyRepo, goalRepo, null);
        DevMindException ex = assertThrows(DevMindException.class,
                () -> service.precheckQuarterly("alice", "2026-Q4", false));
        assertTrue(ex.getMessage().contains("无复盘素材"), ex.getMessage());
    }

    @Test
    void 季度复盘generate起会话场景与prompt契约() {
        WorklogQuarterlyReportRepository quarterlyRepo = mock(WorklogQuarterlyReportRepository.class);
        WorklogGoalService goalService = mock(WorklogGoalService.class);
        WorklogEntryRepository entryRepo = mock(WorklogEntryRepository.class);
        when(goalService.tree(2026)).thenReturn(new GoalTreeView(List.of(), List.of()));
        when(entryRepo.findRange("alice", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of());
        WorklogWorkspaceService workspaceService = mock(WorklogWorkspaceService.class);
        when(workspaceService.ensureFor("alice"))
                .thenReturn(new com.devmind.worklog.dto.WorkspaceView(true, "p-wl", null, null));
        SessionManagerService sessionManager = mock(SessionManagerService.class);
        ArgumentCaptor<CreateSessionRequest> reqCap = ArgumentCaptor.forClass(CreateSessionRequest.class);
        SessionView created = mock(SessionView.class);
        when(created.id()).thenReturn("s-q1");
        when(sessionManager.create(reqCap.capture())).thenReturn(created);

        ReportService service = new ReportService(null, null, null, workspaceService, sessionManager,
                null, null, null, entryRepo, null, quarterlyRepo, null, goalService);
        var launch = service.generateQuarterly("alice", "2026-Q4", false, "重点写 KR 风险");
        assertEquals("s-q1", launch.sessionId());
        assertFalse(launch.reused());

        CreateSessionRequest req = reqCap.getValue();
        assertEquals(WorklogScenarios.QUARTERLY_REVIEW, req.scenarioCode());
        String prompt = req.taskSpec();
        assertTrue(prompt.contains("2026-Q4（2026-10-01 ~ 2026-12-31）"), "季度区间缺失: " + prompt);
        assertTrue(prompt.contains("（当季无 OKR 目标"), "空树省略指令缺失: " + prompt);
        assertTrue(prompt.contains("（季内无计划外已完成条目）"), "空聚合占位缺失: " + prompt);
        assertTrue(prompt.contains("重点写 KR 风险"), "hint 未注入: " + prompt);
    }

    @Test
    void 复盘预览确定性渲染OKR对照与计划外聚合() {
        WorklogGoalService goalService = mock(WorklogGoalService.class);
        WorklogPlanItemRepository planRepo = mock(WorklogPlanItemRepository.class);
        WorklogEntryRepository entryRepo = mock(WorklogEntryRepository.class);
        // 树：2026-Q4 区一个 O + 一个 KR（KR#11，带派生数据）
        GoalView kr = new GoalView(11L, "QUARTER", "2026-Q4", "KEY_RESULT", 10L, null,
                "完成 CAP-91 上线", null, null, 60, "ACTIVE", null, 0, null, null,
                3, 2, 5.0, 67);
        GoalView o = new GoalView(10L, "QUARTER", "2026-Q4", "OBJECTIVE", null, null,
                "平台能力跃迁", null, null, 50, "ACTIVE", null, 0, null, null,
                null, null, null, null);
        when(goalService.tree(2026)).thenReturn(new GoalTreeView(List.of(), List.of(
                new GoalTreeView.QuarterSection("2026-Q4",
                        List.of(new GoalTreeView.GoalNode(o, List.of(kr)))))));
        // KR#11 关联计划：季内一条 DONE + 季外一条（周跨季挂错季，不计）
        WorklogPlanItemEntity inQ = wipItem(21L, "DONE");
        inQ.setKrId(11L);
        inQ.setTitle("季度内计划");
        inQ.setPlanDate(LocalDate.of(2026, 11, 2));
        WorklogPlanItemEntity outQ = wipItem(22L, "DONE");
        outQ.setKrId(11L);
        outQ.setTitle("季度外计划");
        outQ.setPlanDate(LocalDate.of(2026, 9, 28));
        when(planRepo.findByKrIdIn(List.of(11L))).thenReturn(List.of(inQ, outQ));
        when(entryRepo.findByPlanItemIdIn(any())).thenReturn(List.of());
        // 计划外聚合：季内 DONE 三条——一条挂 KR 计划（对齐不计）、两条未对齐
        WorklogEntryEntity aligned = doneEntry("对齐条目", "DEV", 60);
        aligned.setPlanItemId(21L);
        WorklogEntryEntity un1 = doneEntry("临时支持工单", "SUPPORT", 90);
        WorklogEntryEntity un2 = doneEntry("月度例会", "MEETING", 60);
        when(entryRepo.findRange("alice", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(aligned, un1, un2));
        when(planRepo.findAllById(any())).thenReturn(List.of(inQ));

        ReportService service = new ReportService(null, null, null, null, null, null, null,
                planRepo, entryRepo, null, null, null, goalService);
        String md = service.reviewPreview("alice", "2026-Q4").markdown();
        assertTrue(md.contains("### O：平台能力跃迁"), "缺 O 行: " + md);
        assertTrue(md.contains("KR#11 完成 CAP-91 上线"), "缺 KR 行: " + md);
        assertTrue(md.contains("建议进度 67%"), "缺派生进度: " + md);
        assertTrue(md.contains("季度内计划"), "季内计划项须进对照块: " + md);
        assertFalse(md.contains("季度外计划"), "季外计划项不计: " + md);
        assertTrue(md.contains("SUPPORT ×1") && md.contains("临时支持工单"), "缺计划外聚合: " + md);
        assertTrue(md.contains("MEETING ×1"), "缺 MEETING 聚合: " + md);
        assertFalse(md.contains("对齐条目"), "已对齐 KR 的条目不算计划外: " + md);
    }

    @Test
    void 季度复盘手动创建幂等与编辑保存() {
        WorklogQuarterlyReportRepository quarterlyRepo = mock(WorklogQuarterlyReportRepository.class);
        ReportService service = new ReportService(null, null, null, null, null, null, null,
                null, null, null, quarterlyRepo, null, null);
        service.createQuarterly("alice", "2026-Q4");
        ArgumentCaptor<WorklogQuarterlyReportEntity> cap =
                ArgumentCaptor.forClass(WorklogQuarterlyReportEntity.class);
        verify(quarterlyRepo).save(cap.capture());
        assertEquals("alice", cap.getValue().getUserId());
        assertEquals("2026-Q4", cap.getValue().getQuarter());

        // 已存在 → 幂等不再插
        when(quarterlyRepo.existsByUserIdAndQuarter("alice", "2026-Q4")).thenReturn(true);
        service.createQuarterly("alice", "2026-Q4");
        verify(quarterlyRepo, times(1)).save(any());

        // 编辑保存（按 period 寻址直写）
        WorklogQuarterlyReportEntity row = new WorklogQuarterlyReportEntity();
        row.setUserId("alice");
        row.setQuarter("2026-Q4");
        row.setContentMd("旧稿");
        when(quarterlyRepo.findByUserIdAndQuarter("alice", "2026-Q4"))
                .thenReturn(Optional.of(row));
        service.updateQuarterly("alice", "2026-Q4", new UpdateQuarterlyRequest("新稿"));
        assertEquals("新稿", row.getContentMd());
        verify(quarterlyRepo, times(2)).save(any());
    }

    @Test
    void 季度period非法400() {
        ReportService service = new ReportService(null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        assertThrows(DevMindException.class, () -> service.getQuarterly("alice", "2026-Q5"));
        assertThrows(DevMindException.class, () -> service.reviewPreview("alice", "2026"));
    }

    private static WorklogEntryEntity doneEntry(String title, String type, int minutes) {
        WorklogEntryEntity e = new WorklogEntryEntity();
        e.setTitle(title);
        e.setEntryType(type);
        e.setMinutes(minutes);
        e.setStatus(WorklogEntryEntity.STATUS_DONE);
        e.setWorkDate(LocalDate.of(2026, 11, 5));
        return e;
    }
}
