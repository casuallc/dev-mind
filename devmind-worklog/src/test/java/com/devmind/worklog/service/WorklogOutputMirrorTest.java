package com.devmind.worklog.service;

import com.devmind.common.event.DomainEvent;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionOutputEntity;
import com.devmind.worklog.model.DailyReportEntity;
import com.devmind.worklog.model.WeeklyReportEntity;
import com.devmind.worklog.model.WorklogQuarterlyReportEntity;
import com.devmind.worklog.repo.DailyReportRepository;
import com.devmind.worklog.repo.WeeklyReportRepository;
import com.devmind.worklog.repo.WorklogQuarterlyReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CAP-89 WorklogOutputMirror 单测（DB 口径）：成稿落库 upsert（userId/date/内容/sessionId 追溯）、
 * 缺回传降级通知、ISO 周换算、mirrorAll 三道闸（无行写入/同内容静默/已有不同内容跳过不覆盖）。
 * 两 repository 走 Mockito mock（落库 SQL 由 E2E 真库覆盖）。
 */
class WorklogOutputMirrorTest {

    private final FakePublisher publisher = new FakePublisher();
    private DailyReportRepository dailyRepo;
    private WeeklyReportRepository weeklyRepo;
    private WorklogPlanService planService;
    private WorklogQuarterlyReportRepository quarterlyRepo;
    private OkrSuggestService okrSuggestService;
    private WorklogOutputMirror mirror;

    @BeforeEach
    void setUp() {
        dailyRepo = mock(DailyReportRepository.class);
        weeklyRepo = mock(WeeklyReportRepository.class);
        quarterlyRepo = mock(WorklogQuarterlyReportRepository.class);
        planService = mock(WorklogPlanService.class);
        okrSuggestService = mock(OkrSuggestService.class);
        mirror = new WorklogOutputMirror(null, null, null, dailyRepo, weeklyRepo, publisher,
                planService, quarterlyRepo, okrSuggestService);
    }

    static class FakePublisher extends DomainEventPublisher {
        final List<DomainEvent> events = new ArrayList<>();

        FakePublisher() {
            super(null);
        }

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
    }

    private static SessionEntity session(String id) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        return s;
    }

    private static SessionOutputEntity output(String fileName, String content) {
        SessionOutputEntity o = new SessionOutputEntity();
        o.setSessionId("s1");
        o.setFileName(fileName);
        o.setContent(content);
        return o;
    }

    @Test
    void 日报成稿落库并发事件() {
        mirror.mirrorDaily(session("s1"), "u1",
                List.of(output("daily-2026-09-14.md", "# 日报正文")));
        ArgumentCaptor<DailyReportEntity> cap = ArgumentCaptor.forClass(DailyReportEntity.class);
        verify(dailyRepo).save(cap.capture());
        DailyReportEntity saved = cap.getValue();
        assertEquals("u1", saved.getUserId());
        assertEquals(LocalDate.of(2026, 9, 14), saved.getWorkDate());
        assertEquals("# 日报正文", saved.getContentMd());
        assertEquals("s1", saved.getSessionId());
        assertEquals(1, publisher.events.size());
        DomainEvent ev = publisher.events.get(0);
        assertEquals("worklog.daily.generated", ev.type());
        assertEquals("2026-09-14", ((SimpleDomainEvent) ev).entityId(), "entityId 为日期字符串");
    }

    @Test
    void 场景路径已有行覆盖写() {
        // 场景生成无条件 upsert（force 语义已在 generate 闸过）；已有行更新而非插入
        DailyReportEntity existing = new DailyReportEntity();
        existing.setUserId("u1");
        existing.setWorkDate(LocalDate.of(2026, 9, 14));
        existing.setContentMd("旧稿");
        when(dailyRepo.findByUserIdAndWorkDate("u1", LocalDate.of(2026, 9, 14)))
                .thenReturn(Optional.of(existing));
        mirror.mirrorDaily(session("s2"), "u1", List.of(output("daily-2026-09-14.md", "新稿")));
        ArgumentCaptor<DailyReportEntity> cap = ArgumentCaptor.forClass(DailyReportEntity.class);
        verify(dailyRepo).save(cap.capture());
        assertEquals("新稿", cap.getValue().getContentMd());
        assertEquals("s2", cap.getValue().getSessionId());
        assertEquals(1, publisher.events.size());
    }

    @Test
    void 缺回传发降级通知不落库() {
        mirror.mirrorDaily(session("s1"), "u1", List.of(output("other.md", "x")));
        verifyNoInteractions(dailyRepo);
        assertEquals(1, publisher.events.size());
        DomainEvent ev = publisher.events.get(0);
        assertEquals("worklog.report.failed", ev.type());
        assertEquals(Boolean.FALSE, ((SimpleDomainEvent) ev).success());
    }

    @Test
    void 周报按ISO周拆两列落库() {
        mirror.mirrorWeekly(session("s1"), "u1", List.of(output("weekly-2026-W37.md",
                ReportService.SUMMARY_HEADING + "\n- 完成 A\n" + ReportService.PLAN_HEADING + "\n- 继续 B\n")));
        // W37 周一 = 2026-09-07；全文按分节标记拆 summary/nextPlan 两列
        ArgumentCaptor<WeeklyReportEntity> cap = ArgumentCaptor.forClass(WeeklyReportEntity.class);
        verify(weeklyRepo).save(cap.capture());
        WeeklyReportEntity saved = cap.getValue();
        assertEquals("u1", saved.getUserId());
        assertEquals(LocalDate.of(2026, 9, 7), saved.getWeekStart());
        assertEquals("- 完成 A", saved.getSummaryMd());
        assertEquals("- 继续 B", saved.getNextPlanMd());
        assertEquals("s1", saved.getSessionId());
        assertEquals("worklog.weekly.generated", publisher.events.get(0).type());
        assertEquals("2026-09-07", ((SimpleDomainEvent) publisher.events.get(0)).entityId());
    }

    @Test
    void 周报跨年周换算() {
        // weekly-2026-W01.md 的周一落在 2025-12-29（ISO 周年）
        mirror.mirrorWeekly(session("s1"), "u1", List.of(output("weekly-2026-W01.md", "跨年周")));
        ArgumentCaptor<WeeklyReportEntity> cap = ArgumentCaptor.forClass(WeeklyReportEntity.class);
        verify(weeklyRepo).save(cap.capture());
        assertEquals(LocalDate.of(2025, 12, 29), cap.getValue().getWeekStart());
    }

    @Test
    void 周报缺回传发降级通知() {
        mirror.mirrorWeekly(session("s1"), "u1", List.of());
        verifyNoInteractions(weeklyRepo);
        assertEquals("worklog.report.failed", publisher.events.get(0).type());
    }

    @Test
    void 非法文件名不命中() {
        assertNull(findMatch(List.of(output("daily-2026-9-4.md", "x"),
                        output("weekly-2026-w37.md", "x")), WorklogOutputMirror.DAILY_FILE),
                "不严格的日期/小写 W 不应命中");
    }

    @Test
    void 手工对话mirrorAll无行全部落库() {
        // 两 repository 默认返回 Optional.empty()（Mockito 默认答案）→ 全部 WRITE
        var r = mirror.mirrorAll(session("s1"), "u1", List.of(
                output("daily-2026-09-15.md", "日报v1"),
                output("notes.md", "非契约文件忽略"),
                output("weekly-2026-W38.md", "周报v1")));
        assertEquals(2, r.mirrored().size());
        assertTrue(r.skipped().isEmpty(), "库中无既有行时无跳过");
        verify(dailyRepo).save(any(DailyReportEntity.class));
        verify(weeklyRepo).save(any(WeeklyReportEntity.class));
    }

    @Test
    void mirrorAll镜像本会话全部成稿() {
        // 镜像单位 = 当前会话产出的全部成稿，多天日报逐份落库而非只挑日期最新的一份
        var r = mirror.mirrorAll(session("s1"), "u1", List.of(
                output("daily-2026-09-15.md", "15号稿"),
                output("daily-2026-09-17.md", "17号稿"),
                output("daily-2026-09-16.md", "16号稿")));
        assertEquals(3, r.mirrored().size());
        assertTrue(r.skipped().isEmpty());
        verify(dailyRepo, times(3)).save(any(DailyReportEntity.class));
    }

    @Test
    void mirrorAll内容一致静默跳过() {
        // 库里已有同内容日报（旧稿重复搬运）→ 不写、不进 mirrored/skipped
        DailyReportEntity existing = new DailyReportEntity();
        existing.setUserId("u1");
        existing.setWorkDate(LocalDate.of(2026, 9, 15));
        existing.setContentMd("日报v1");
        when(dailyRepo.findByUserIdAndWorkDate("u1", LocalDate.of(2026, 9, 15)))
                .thenReturn(Optional.of(existing));
        var r = mirror.mirrorAll(session("s1"), "u1", List.of(output("daily-2026-09-15.md", "日报v1")));
        assertTrue(r.mirrored().isEmpty(), "内容一致零写库，消除「推了好多天」的假象");
        assertTrue(r.skipped().isEmpty());
        verify(dailyRepo, times(0)).save(any());
    }

    @Test
    void mirrorAll已有不同内容跳过不覆盖() {
        // CAP-89「自动不覆盖」：库中已有不同内容（无论来源）→ 跳过，要覆盖走 force 重新生成
        DailyReportEntity existing = new DailyReportEntity();
        existing.setUserId("u1");
        existing.setWorkDate(LocalDate.of(2026, 9, 15));
        existing.setContentMd("手动改过的内容");
        when(dailyRepo.findByUserIdAndWorkDate("u1", LocalDate.of(2026, 9, 15)))
                .thenReturn(Optional.of(existing));
        var r = mirror.mirrorAll(session("s1"), "u1", List.of(output("daily-2026-09-15.md", "旧成稿")));
        assertTrue(r.mirrored().isEmpty());
        assertEquals(1, r.skipped().size());
        assertTrue(r.skipped().get(0).contains("2026-09-15") && r.skipped().get(0).contains("未覆盖"),
                "skipped 带原因: " + r.skipped());
        verify(dailyRepo, times(0)).save(any());
    }

    @Test
    void mirrorAll周报同内容按拆分列比对() {
        WeeklyReportEntity existing = new WeeklyReportEntity();
        existing.setUserId("u1");
        existing.setWeekStart(LocalDate.of(2026, 9, 14));
        existing.setSummaryMd("- 完成 A");
        existing.setNextPlanMd("- 继续 B");
        when(weeklyRepo.findByUserIdAndWeekStart("u1", LocalDate.of(2026, 9, 14)))
                .thenReturn(Optional.of(existing));
        String same = ReportService.assembleWeekly("- 完成 A", "- 继续 B");
        var r = mirror.mirrorAll(session("s1"), "u1", List.of(output("weekly-2026-W38.md", same)));
        assertTrue(r.mirrored().isEmpty());
        assertTrue(r.skipped().isEmpty());
        verify(weeklyRepo, times(0)).save(any());
    }

    private static SessionOutputEntity findMatch(List<SessionOutputEntity> outputs, java.util.regex.Pattern p) {
        for (SessionOutputEntity o : outputs) {
            if (p.matcher(o.getFileName()).matches()) {
                return o;
            }
        }
        return null;
    }

    // ---------------- CAP-88 M3：建议计划镜像 ----------------

    @Test
    void 建议计划产物解析落库并发事件() {
        when(planService.insertSuggestions(any(), any(), any())).thenReturn(2);
        mirror.mirrorSuggest(session("s9"), "u1", List.of(output("plan-suggest-2026-W43.md",
                "- [DEV] 承接上周未完成的登录改造（预估 4h）\n- [RESEARCH] 调研分诊模型替换\n")));
        // W43 周一 = 2026-10-19；解析出 2 条，插库委托 planService（去重/追加语义有其单测覆盖）
        ArgumentCaptor<LocalDate> weekCap = ArgumentCaptor.forClass(LocalDate.class);
        verify(planService).insertSuggestions(org.mockito.ArgumentMatchers.eq("u1"), weekCap.capture(), any());
        assertEquals(LocalDate.of(2026, 10, 19), weekCap.getValue());
        assertEquals(1, publisher.events.size());
        DomainEvent ev = publisher.events.get(0);
        assertEquals("worklog.plan.suggested", ev.type());
        assertEquals("2026-10-19", ((SimpleDomainEvent) ev).entityId());
        verifyNoInteractions(dailyRepo, weeklyRepo);
    }

    @Test
    void 建议计划缺回传发降级通知() {
        mirror.mirrorSuggest(session("s9"), "u1", List.of(output("other.md", "x")));
        verifyNoInteractions(planService, dailyRepo, weeklyRepo);
        assertEquals("worklog.report.failed", publisher.events.get(0).type());
        assertTrue(((SimpleDomainEvent) publisher.events.get(0)).summary().contains("建议计划"));
    }

    @Test
    void 建议计划产物无可解析行发降级通知() {
        mirror.mirrorSuggest(session("s9"), "u1", List.of(output("plan-suggest-2026-W43.md",
                "# 抱歉我无法给出建议\n没有任何合规建议行")));
        verifyNoInteractions(planService, dailyRepo, weeklyRepo);
        assertEquals("worklog.report.failed", publisher.events.get(0).type());
    }

    // ---------------- CAP-91 M2：季度复盘镜像 ----------------

    @Test
    void 季度复盘成稿落库并发事件() {
        mirror.mirrorQuarterly(session("s1"), "u1",
                List.of(output("quarterly-review-2026-Q3.md", "# 复盘正文")));
        ArgumentCaptor<WorklogQuarterlyReportEntity> cap =
                ArgumentCaptor.forClass(WorklogQuarterlyReportEntity.class);
        verify(quarterlyRepo).save(cap.capture());
        WorklogQuarterlyReportEntity saved = cap.getValue();
        assertEquals("u1", saved.getUserId());
        assertEquals("2026-Q3", saved.getQuarter());
        assertEquals("# 复盘正文", saved.getContentMd());
        assertEquals("s1", saved.getSessionId());
        assertEquals(1, publisher.events.size());
        DomainEvent ev = publisher.events.get(0);
        assertEquals("worklog.quarterly.generated", ev.type());
        assertEquals("2026-Q3", ((SimpleDomainEvent) ev).entityId(), "entityId 为季度 period");
    }

    @Test
    void 季度复盘缺回传发降级通知() {
        mirror.mirrorQuarterly(session("s1"), "u1", List.of(output("other.md", "x")));
        verifyNoInteractions(quarterlyRepo);
        assertEquals(1, publisher.events.size());
        DomainEvent ev = publisher.events.get(0);
        assertEquals("worklog.report.failed", ev.type());
        assertTrue(((SimpleDomainEvent) ev).summary().contains("季度复盘"));
    }

    @Test
    void mirrorAll季度复盘已有不同内容跳过不覆盖() {
        // 同日报闸：库中已有不同内容（无论来源）→ 跳过，要覆盖走 force 重新生成
        WorklogQuarterlyReportEntity existing = new WorklogQuarterlyReportEntity();
        existing.setUserId("u1");
        existing.setQuarter("2026-Q3");
        existing.setContentMd("人工改过的复盘");
        when(quarterlyRepo.findByUserIdAndQuarter("u1", "2026-Q3"))
                .thenReturn(Optional.of(existing));
        var r = mirror.mirrorAll(session("s1"), "u1",
                List.of(output("quarterly-review-2026-Q3.md", "旧成稿")));
        assertTrue(r.mirrored().isEmpty());
        assertEquals(1, r.skipped().size());
        assertTrue(r.skipped().get(0).contains("2026-Q3") && r.skipped().get(0).contains("未覆盖"),
                "skipped 带原因: " + r.skipped());
        verify(quarterlyRepo, times(0)).save(any());
    }

    @Test
    void mirrorAll季度复盘同内容静默跳过() {
        WorklogQuarterlyReportEntity existing = new WorklogQuarterlyReportEntity();
        existing.setUserId("u1");
        existing.setQuarter("2026-Q3");
        existing.setContentMd("复盘v1");
        when(quarterlyRepo.findByUserIdAndQuarter("u1", "2026-Q3"))
                .thenReturn(Optional.of(existing));
        var r = mirror.mirrorAll(session("s1"), "u1",
                List.of(output("quarterly-review-2026-Q3.md", "复盘v1")));
        assertTrue(r.mirrored().isEmpty(), "内容一致零写库");
        assertTrue(r.skipped().isEmpty());
        verify(quarterlyRepo, times(0)).save(any());
    }

    // ---------------- CAP-93：OKR 草稿镜像 ----------------

    @Test
    void okr产物解析落草稿并发事件() {
        mirror.mirrorOkrSuggest(session("s10"), "u1", List.of(output("okr-suggest-2026-Q4.md",
                "- [O] 提升交付质量（权重 60%）\n  - [KR] 缺陷率降至 1%（权重 30%）\n- [O] 第二个目标\n")));
        ArgumentCaptor<List> itemsCap = ArgumentCaptor.forClass(List.class);
        verify(okrSuggestService).saveDraft(org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.eq("2026-Q4"), any(), itemsCap.capture(),
                org.mockito.ArgumentMatchers.eq("s10"));
        assertEquals(2, itemsCap.getValue().size());
        assertEquals(1, publisher.events.size());
        DomainEvent ev = publisher.events.get(0);
        assertEquals("worklog.okr.suggested", ev.type());
        assertEquals("2026-Q4", ((SimpleDomainEvent) ev).entityId());
        verifyNoInteractions(dailyRepo, weeklyRepo, quarterlyRepo, planService);
    }

    @Test
    void okr缺回传发降级通知() {
        mirror.mirrorOkrSuggest(session("s10"), "u1", List.of(output("other.md", "x")));
        verifyNoInteractions(okrSuggestService);
        assertEquals("worklog.report.failed", publisher.events.get(0).type());
        assertTrue(((SimpleDomainEvent) publisher.events.get(0)).summary().contains("OKR"));
    }

    @Test
    void okr产物无可解析行发降级通知() {
        mirror.mirrorOkrSuggest(session("s10"), "u1", List.of(output("okr-suggest-2027.md",
                "# 无法给出建议\n没有任何合规目标行")));
        verifyNoInteractions(okrSuggestService);
        assertEquals("worklog.report.failed", publisher.events.get(0).type());
    }
}
