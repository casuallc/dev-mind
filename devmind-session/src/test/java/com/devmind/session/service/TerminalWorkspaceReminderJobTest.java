package com.devmind.session.service;

import com.devmind.common.notification.NotificationEvent;
import com.devmind.notification.NotificationPublisher;
import com.devmind.session.config.TerminalWorkspaceProperties;
import com.devmind.session.model.TerminalWorkspaceEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-115 M3 闲置提醒巡检单测：超龄 OPEN 提醒一次（remindedAt 24h 去重）、
 * 未超龄/非 OPEN 不提醒、idleRemindDays=0 关闭（全内存 fake，不起服务）。
 */
class TerminalWorkspaceReminderJobTest {

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private static class Harness {
        final Map<String, TerminalWorkspaceEntity> store = new HashMap<>();
        final List<NotificationEvent> events = new ArrayList<>();
        final TerminalWorkspaceProperties props = new TerminalWorkspaceProperties();
        final TerminalWorkspaceReminderJob job;

        Harness() {
            NotificationPublisher publisher = proxy(NotificationPublisher.class,
                    (p, m, args) -> {
                        events.add((NotificationEvent) args[0]);
                        return null;
                    });
            job = new TerminalWorkspaceReminderJob(
                    TerminalWorkspaceServiceTest.twRepo(store), props, publisher);
        }

        TerminalWorkspaceEntity openRow(String key, Instant lastActiveAt) {
            TerminalWorkspaceEntity e = new TerminalWorkspaceEntity();
            e.setId(java.util.UUID.randomUUID().toString());
            e.setProjectId("p1");
            e.setWorkspaceKey(key);
            e.setState(TerminalWorkspaceService.STATE_OPEN);
            e.setCreatedBy("u1");
            e.setCreatedAt(lastActiveAt);
            e.setLastActiveAt(lastActiveAt);
            store.put(e.getId(), e);
            return e;
        }
    }

    @Test
    void 闲置超龄OPEN提醒一次且24h内去重() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-idle0001",
                Instant.now().minus(4, ChronoUnit.DAYS));

        h.job.runOnce();
        assertEquals(1, h.events.size());
        NotificationEvent ev = h.events.get(0);
        assertEquals(TerminalWorkspaceReminderJob.EVENT_KIND, ev.kind());
        assertEquals("term-idle0001", ev.sessionId(), "事件应按工作区键聚合");
        assertTrue(ev.content().contains("term-idle0001"));
        assertTrue(e.getRemindedAt() != null, "提醒后应落 remindedAt");

        h.job.runOnce(); // 24h 内不重复轰炸
        assertEquals(1, h.events.size(), "remindedAt 未超间隔不应重复提醒");
    }

    @Test
    void 间隔过后再次提醒() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-idle0002",
                Instant.now().minus(10, ChronoUnit.DAYS));
        e.setRemindedAt(Instant.now().minus(25, ChronoUnit.HOURS));
        h.job.runOnce();
        assertEquals(1, h.events.size(), "距上次提醒超 24h 应再次提醒");
    }

    @Test
    void 未超龄与非OPEN不提醒() {
        Harness h = new Harness();
        h.openRow("term-fresh001", Instant.now().minus(1, ChronoUnit.HOURS));
        TerminalWorkspaceEntity released = h.openRow("term-released",
                Instant.now().minus(30, ChronoUnit.DAYS));
        released.setState(TerminalWorkspaceService.STATE_RELEASED);

        h.job.runOnce();
        assertEquals(0, h.events.size());
    }

    @Test
    void 阈值为0关闭提醒() {
        Harness h = new Harness();
        h.props.setIdleRemindDays(0);
        h.openRow("term-idle0003", Instant.now().minus(30, ChronoUnit.DAYS));
        h.job.runOnce();
        assertEquals(0, h.events.size(), "idleRemindDays=0 应整体关闭");
    }
}
