package com.devmind.session.service;

import com.devmind.common.agent.AgentNodeSessionsProvider.ActiveSessionInfo;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.repo.SessionRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SessionNodeSessionsProvider}：活动三态过滤 + 标题取值（summary 优先，空则 taskSpec 截断）。无 Spring/Mockito，手工 fake。 */
class SessionNodeSessionsProviderTest {

    /** findByAgentNodeIdAndStatusIn 返回给定清单并记录入参的 SessionRepository 代理（其余方法不支持）。 */
    private static SessionRepository repoReturning(List<SessionEntity> rows,
                                                   AtomicReference<String> nodeIdSeen,
                                                   AtomicReference<Collection<String>> statusesSeen) {
        return (SessionRepository) Proxy.newProxyInstance(
                SessionNodeSessionsProviderTest.class.getClassLoader(),
                new Class<?>[]{SessionRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findByAgentNodeIdAndStatusIn")) {
                        nodeIdSeen.set((String) args[0]);
                        @SuppressWarnings("unchecked")
                        Collection<String> statuses = (Collection<String>) args[1];
                        statusesSeen.set(statuses);
                        return rows;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static SessionEntity entity(String id, String summary, String taskSpec) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setStatus(SessionState.RUNNING.name());
        e.setSummary(summary);
        e.setTaskSpec(taskSpec);
        e.setCreatedBy("u1");
        e.setCreatedAt(Instant.parse("2026-09-09T01:00:00Z"));
        return e;
    }

    @Test
    void mapsActiveSessionsWithSummaryTitle() {
        AtomicReference<String> nodeId = new AtomicReference<>();
        AtomicReference<Collection<String>> statuses = new AtomicReference<>();
        SessionNodeSessionsProvider provider = new SessionNodeSessionsProvider(
                repoReturning(List.of(entity("s1", "修登录 bug", "长任务说明")), nodeId, statuses));

        List<ActiveSessionInfo> list = provider.activeSessions("3");

        assertEquals("3", nodeId.get());
        assertEquals(List.of(SessionState.RUNNING.name(), SessionState.WAITING_INPUT.name(),
                SessionState.WAITING_AUTH.name()), List.copyOf(statuses.get()));
        assertEquals(1, list.size());
        ActiveSessionInfo info = list.get(0);
        assertEquals("s1", info.sessionId());
        assertEquals("SESSION", info.kind());
        assertEquals("修登录 bug", info.title());
        assertEquals("RUNNING", info.status());
        assertEquals("u1", info.createdBy());
    }

    @Test
    void fallsBackToTruncatedTaskSpecFirstLine() {
        String longSpec = "第一行" + "x".repeat(80) + "\n第二行";
        SessionNodeSessionsProvider provider = new SessionNodeSessionsProvider(
                repoReturning(List.of(entity("s2", "  ", longSpec)),
                        new AtomicReference<>(), new AtomicReference<>()));

        String title = provider.activeSessions("3").get(0).title();

        assertTrue(title.endsWith("…"), title);
        assertTrue(title.length() <= 61, title);
        assertTrue(title.startsWith("第一行"), title);
    }
}
