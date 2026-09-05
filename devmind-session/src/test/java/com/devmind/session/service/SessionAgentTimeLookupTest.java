package com.devmind.session.service;

import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionState;
import com.devmind.session.repo.SessionRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionAgentTimeLookup 汇总口径单测：终态按 finished-created，活跃算到当前，
 * SUSPENDED（无 finishedAt）不计；多需求分组求和。
 */
class SessionAgentTimeLookupTest {

    /** 基准取真实 now（活跃会话算到当前时刻，不能用固定过去时刻——否则未来 createdAt 会被 >0 过滤掉） */
    private static final Instant NOW = Instant.now();

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private static SessionEntity session(String rid, String status, Instant created, Instant finished) {
        SessionEntity s = new SessionEntity();
        s.setId("s-" + Math.abs((rid + created).hashCode()));
        s.setRequirementId(rid);
        s.setStatus(status);
        s.setCreatedAt(created);
        s.setFinishedAt(finished);
        return s;
    }

    @Test
    void 终态活跃挂起混合汇总() {
        Instant t0 = NOW.minusSeconds(3600);
        List<SessionEntity> store = List.of(
                // r1：终态 1h + 终态 30m + 活跃（已跑 10m）
                session("r1", SessionState.DONE.name(), t0, t0.plusSeconds(3600)),
                session("r1", SessionState.FAILED.name(), t0, t0.plusSeconds(1800)),
                session("r1", SessionState.RUNNING.name(), NOW.minusSeconds(600), null),
                // r1：挂起无 finishedAt 不计
                session("r1", SessionState.SUSPENDED.name(), t0, null),
                // r2：一个终态 15m
                session("r2", SessionState.TERMINATED.name(), t0, t0.plusSeconds(900)));
        SessionRepository repo = proxy(SessionRepository.class, (p, m, args) ->
                "findByRequirementIdIn".equals(m.getName())
                        ? store.stream().filter(s -> ((Collection<?>) args[0]).contains(s.getRequirementId())).toList()
                        : List.of());

        Map<String, Long> out = new SessionAgentTimeLookup(repo).secondsFor(List.of("r1", "r2", "r3"));

        // r1 = 3600 + 1800 + 活跃会话算到"当前"（≥600s，留 5s 执行漂移）
        long r1 = out.get("r1");
        assertTrue(r1 >= 5400 + 595, "r1 应含终态 5400s + 活跃 ≥600s，实际 " + r1);
        assertEquals(900L, out.get("r2"));
        assertFalse(out.containsKey("r3")); // 无会话不进 map
    }

    @Test
    void 空入参与空结果安全() {
        SessionRepository repo = proxy(SessionRepository.class, (p, m, args) -> List.of());
        SessionAgentTimeLookup lookup = new SessionAgentTimeLookup(repo);
        assertTrue(lookup.secondsFor(List.of()).isEmpty());
        assertTrue(lookup.secondsFor(List.of("rx")).isEmpty());
    }

    @Test
    void 未知状态与缺createdAt不计() {
        List<SessionEntity> store = new ArrayList<>();
        store.add(session("r1", "ALIEN_STATE", NOW.minusSeconds(100), null));
        SessionEntity noCreated = session("r1", SessionState.DONE.name(), null, NOW);
        store.add(noCreated);
        SessionRepository repo = proxy(SessionRepository.class, (p, m, args) -> store);

        Map<String, Long> out = new SessionAgentTimeLookup(repo).secondsFor(List.of("r1"));

        assertTrue(out.isEmpty());
    }
}
