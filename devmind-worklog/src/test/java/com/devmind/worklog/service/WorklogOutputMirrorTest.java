package com.devmind.worklog.service;

import com.devmind.common.event.DomainEvent;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionOutputEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CAP-74 WorklogOutputMirror 单测：成稿写仓（WriteReport 参数：owner/kind/date/内容/AI 来源/
 * 会话追溯）、覆盖写（无 CONFIRMED 跳过）、缺回传降级通知、ISO 周换算。
 * WorklogRepoService 走 Mockito mock（写仓本身由 WorklogRepoServiceTest 真 git 覆盖）。
 * 监听入口的 projectService 解析为薄壳不测，核心走 mirrorDaily/mirrorWeekly/mirrorAll 直测。
 */
class WorklogOutputMirrorTest {

    private final FakePublisher publisher = new FakePublisher();
    private WorklogRepoService repoService;
    private WorklogOutputMirror mirror;

    @BeforeEach
    void setUp() {
        repoService = mock(WorklogRepoService.class);
        mirror = new WorklogOutputMirror(null, null, null, repoService, publisher);
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
    void 日报成稿写仓并发事件() {
        mirror.mirrorDaily(session("s1"), "u1",
                List.of(output("daily-2026-09-14.md", "# 日报正文")));
        verify(repoService).writeReport(eq("u1"), eq(WorklogRepoService.ReportKind.DAILY),
                eq(LocalDate.of(2026, 9, 14)), eq("# 日报正文"),
                eq(WorklogRepoService.CommitSource.AI), eq("s1"), isNull());
        assertEquals(1, publisher.events.size());
        DomainEvent ev = publisher.events.get(0);
        assertEquals("worklog.daily.generated", ev.type());
        assertEquals("2026-09-14", ((SimpleDomainEvent) ev).entityId(), "entityId 由 DB id 改日期字符串");
    }

    @Test
    void 覆盖写仓无确认态跳过() {
        mirror.mirrorDaily(session("s1"), "u1", List.of(output("daily-2026-09-14.md", "v1")));
        mirror.mirrorDaily(session("s2"), "u1", List.of(output("daily-2026-09-14.md", "v2")));
        // CAP-74 去状态机：后写覆盖前写（防误覆盖靠 git 历史），两次都写仓都发事件
        verify(repoService, times(2)).writeReport(eq("u1"), eq(WorklogRepoService.ReportKind.DAILY),
                eq(LocalDate.of(2026, 9, 14)), any(), eq(WorklogRepoService.CommitSource.AI),
                any(), isNull());
        assertEquals(2, publisher.events.size());
    }

    @Test
    void 缺回传发降级通知不写仓() {
        mirror.mirrorDaily(session("s1"), "u1", List.of(output("other.md", "x")));
        verifyNoInteractions(repoService);
        assertEquals(1, publisher.events.size());
        DomainEvent ev = publisher.events.get(0);
        assertEquals("worklog.report.failed", ev.type());
        assertEquals(Boolean.FALSE, ((SimpleDomainEvent) ev).success());
    }

    @Test
    void 周报按ISO周写仓() {
        mirror.mirrorWeekly(session("s1"), "u1", List.of(output("weekly-2026-W37.md",
                ReportService.SUMMARY_HEADING + "\n- 完成 A\n" + ReportService.PLAN_HEADING + "\n- 继续 B\n")));
        // W37 周一 = 2026-09-07；文件全文原样写仓（分节只是视图层行为）
        verify(repoService).writeReport(eq("u1"), eq(WorklogRepoService.ReportKind.WEEKLY),
                eq(LocalDate.of(2026, 9, 7)), any(), eq(WorklogRepoService.CommitSource.AI),
                eq("s1"), isNull());
        assertEquals("worklog.weekly.generated", publisher.events.get(0).type());
        assertEquals("2026-09-07", ((SimpleDomainEvent) publisher.events.get(0)).entityId());
    }

    @Test
    void 周报跨年周换算() {
        // weekly-2026-W01.md 的周一落在 2025-12-29（ISO 周年）
        mirror.mirrorWeekly(session("s1"), "u1", List.of(output("weekly-2026-W01.md", "跨年周")));
        ArgumentCaptor<LocalDate> date = ArgumentCaptor.forClass(LocalDate.class);
        verify(repoService).writeReport(eq("u1"), eq(WorklogRepoService.ReportKind.WEEKLY),
                date.capture(), any(), any(), any(), isNull());
        assertEquals(LocalDate.of(2025, 12, 29), date.getValue());
    }

    @Test
    void 周报缺回传发降级通知() {
        mirror.mirrorWeekly(session("s1"), "u1", List.of());
        verifyNoInteractions(repoService);
        assertEquals("worklog.report.failed", publisher.events.get(0).type());
    }

    @Test
    void 非法文件名不命中() {
        assertNull(WorklogOutputMirrorTest.findMatch(List.of(output("daily-2026-9-4.md", "x"),
                        output("weekly-2026-w37.md", "x")), WorklogOutputMirror.DAILY_FILE),
                "不严格的日期/小写 W 不应命中");
    }

    @Test
    void 手工对话mirrorAll静默写仓全部成稿() {
        var r = mirror.mirrorAll(session("s1"), "u1", List.of(
                output("daily-2026-09-15.md", "日报v1"),
                output("notes.md", "非契约文件忽略"),
                output("weekly-2026-W38.md", "周报v1")));
        assertEquals(2, r.mirrored().size());
        assertTrue(r.skipped().isEmpty(), "CAP-74 无确认态，skipped 恒空");
        verify(repoService).writeReport(eq("u1"), eq(WorklogRepoService.ReportKind.DAILY),
                eq(LocalDate.of(2026, 9, 15)), eq("日报v1"), any(), any(), isNull());
        verify(repoService).writeReport(eq("u1"), eq(WorklogRepoService.ReportKind.WEEKLY),
                eq(LocalDate.of(2026, 9, 14)), eq("周报v1"), any(), any(), isNull());
    }

    private static SessionOutputEntity findMatch(List<SessionOutputEntity> outputs, java.util.regex.Pattern p) {
        for (SessionOutputEntity o : outputs) {
            if (p.matcher(o.getFileName()).matches()) {
                return o;
            }
        }
        return null;
    }
}
