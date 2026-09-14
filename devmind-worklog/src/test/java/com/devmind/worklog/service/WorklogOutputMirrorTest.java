package com.devmind.worklog.service;

import com.devmind.common.event.DomainEvent;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionOutputEntity;
import com.devmind.session.repo.SessionOutputRepository;
import com.devmind.session.service.SessionOutputService;
import com.devmind.worklog.model.DailyReportEntity;
import com.devmind.worklog.model.WeeklyReportEntity;
import com.devmind.worklog.repo.DailyReportRepository;
import com.devmind.worklog.repo.WeeklyReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorklogOutputMirror 单测：成稿 upsert（新落/覆盖 DRAFT/跳过 CONFIRMED）、
 * 缺回传降级通知、ISO 周换算。无 Mockito（模块惯例），JPA 仓库走 JDK 动态代理内存 fake。
 * 监听入口的 projectService 解析为薄壳不测，核心走 mirrorDaily/mirrorWeekly 直测。
 */
class WorklogOutputMirrorTest {

    private final Map<String, DailyReportEntity> dailyTable = new LinkedHashMap<>();
    private final Map<String, WeeklyReportEntity> weeklyTable = new LinkedHashMap<>();
    private final FakePublisher publisher = new FakePublisher();
    private WorklogOutputMirror mirror;

    @BeforeEach
    void setUp() {
        mirror = new WorklogOutputMirror(null, null, null,
                fakeDailyRepo(), fakeWeeklyRepo(), publisher);
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

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private DailyReportRepository fakeDailyRepo() {
        return proxy(DailyReportRepository.class, (p, m, args) -> switch (m.getName()) {
            case "findByUserIdAndWorkDate" ->
                    Optional.ofNullable(dailyTable.get(args[0] + ":" + args[1]));
            case "save" -> {
                DailyReportEntity e = (DailyReportEntity) args[0];
                dailyTable.put(e.getUserId() + ":" + e.getWorkDate(), e);
                yield e;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private WeeklyReportRepository fakeWeeklyRepo() {
        return proxy(WeeklyReportRepository.class, (p, m, args) -> switch (m.getName()) {
            case "findByUserIdAndWeekStart" ->
                    Optional.ofNullable(weeklyTable.get(args[0] + ":" + args[1]));
            case "save" -> {
                WeeklyReportEntity e = (WeeklyReportEntity) args[0];
                weeklyTable.put(e.getUserId() + ":" + e.getWeekStart(), e);
                yield e;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
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
    void 日报成稿落镜像并发事件() {
        mirror.mirrorDaily(session("s1"), "u1",
                List.of(output("daily-2026-09-14.md", "# 日报正文")));
        DailyReportEntity e = dailyTable.get("u1:2026-09-14");
        assertEquals("# 日报正文", e.getContentMd());
        assertEquals(DailyReportEntity.STATUS_DRAFT, e.getStatus());
        assertEquals("s1", e.getSessionId());
        assertEquals(1, publisher.events.size());
        assertEquals("worklog.daily.generated", publisher.events.get(0).type());
    }

    @Test
    void 日报覆盖草稿但不动已确认() {
        mirror.mirrorDaily(session("s1"), "u1", List.of(output("daily-2026-09-14.md", "v1")));
        mirror.mirrorDaily(session("s2"), "u1", List.of(output("daily-2026-09-14.md", "v2")));
        assertEquals("v2", dailyTable.get("u1:2026-09-14").getContentMd());
        assertEquals("s2", dailyTable.get("u1:2026-09-14").getSessionId());

        dailyTable.get("u1:2026-09-14").setStatus(DailyReportEntity.STATUS_CONFIRMED);
        int eventsBefore = publisher.events.size();
        mirror.mirrorDaily(session("s3"), "u1", List.of(output("daily-2026-09-14.md", "v3")));
        assertEquals("v2", dailyTable.get("u1:2026-09-14").getContentMd(), "CONFIRMED 不覆盖");
        assertEquals(eventsBefore, publisher.events.size(), "跳过不发事件");
    }

    @Test
    void 缺回传发降级通知不落空镜像() {
        mirror.mirrorDaily(session("s1"), "u1", List.of(output("other.md", "x")));
        assertTrue(dailyTable.isEmpty(), "不落空镜像");
        assertEquals(1, publisher.events.size());
        DomainEvent ev = publisher.events.get(0);
        assertEquals("worklog.report.failed", ev.type());
        assertEquals(Boolean.FALSE, ((com.devmind.common.event.SimpleDomainEvent) ev).success());
    }

    @Test
    void 周报按ISO周落库并分节解析() {
        mirror.mirrorWeekly(session("s1"), "u1", List.of(output("weekly-2026-W37.md",
                ReportService.SUMMARY_HEADING + "\n- 完成 A\n" + ReportService.PLAN_HEADING + "\n- 继续 B\n")));
        WeeklyReportEntity e = weeklyTable.get("u1:2026-09-07");
        assertEquals(LocalDate.of(2026, 9, 7), e.getWeekStart(), "W37 周一应为 2026-09-07");
        assertEquals("- 完成 A", e.getSummaryMd());
        assertEquals("- 继续 B", e.getNextPlanMd());
        assertEquals(1, publisher.events.size());
        assertEquals("worklog.weekly.generated", publisher.events.get(0).type());
    }

    @Test
    void 周报缺回传发降级通知() {
        mirror.mirrorWeekly(session("s1"), "u1", List.of());
        assertTrue(weeklyTable.isEmpty());
        assertEquals("worklog.report.failed", publisher.events.get(0).type());
    }

    @Test
    void iso周换算与非法文件名() {
        assertEquals(LocalDate.of(2026, 9, 7), WorklogOutputMirror.isoWeekMonday(2026, 37));
        assertNull(WorklogOutputMirrorTest.findMatch(List.of(output("daily-2026-9-4.md", "x"),
                output("weekly-2026-w37.md", "x")), WorklogOutputMirror.DAILY_FILE),
                "不严格的日期/小写 W 不应命中");
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
