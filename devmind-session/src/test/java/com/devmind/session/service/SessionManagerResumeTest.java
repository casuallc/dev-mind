package com.devmind.session.service;

import com.devmind.common.agent.AgentEventFrame;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.exception.DevMindException;
import com.devmind.session.config.SessionProperties;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.repo.SessionEventRepository;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.runtime.SessionEventSaver;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 会话 resume 放开（SUSPENDED/DONE/FAILED/TERMINATED 可「继续对话」）的守卫与 cliSessionId 捕获。
 * 守卫在重依赖（project/scenario/worktree）之前抛出，故 harness 照 ReconcileTest 传 null 即可。
 */
class SessionManagerResumeTest {

    private static final String NODE = "node-1";

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private static SessionEntity entity(String id, SessionState st, String cliSessionId) {
        SessionEntity ent = new SessionEntity();
        ent.setId(id);
        ent.setProjectId("p1");
        ent.setStatus(st.name());
        ent.setAgentNodeId(NODE);
        ent.setCliSessionId(cliSessionId);
        ent.setCreatedAt(Instant.now());
        ent.setUpdatedAt(Instant.now());
        return ent;
    }

    private static SessionManagerService newService(Map<String, SessionEntity> store,
                                                    AgentNodeConnector connector) {
        SessionRepository repo = proxy(SessionRepository.class, (p, m, args) -> switch (m.getName()) {
            case "save" -> {
                SessionEntity e = (SessionEntity) args[0];
                store.put(e.getId(), e);
                yield e;
            }
            case "findById" -> Optional.ofNullable(store.get((String) args[0]));
            case "findByAgentNodeIdAndStatusIn" -> store.values().stream()
                    .filter(e -> args[0].equals(e.getAgentNodeId()))
                    .filter(e -> ((java.util.Collection<?>) args[1]).contains(e.getStatus()))
                    .toList();
            default -> throw new UnsupportedOperationException(m.getName());
        });
        SessionEventRepository eventRepo = proxy(SessionEventRepository.class, (p, m, args) ->
                "saveAll".equals(m.getName()) ? args[0] : null);
        SessionProperties props = new SessionProperties();
        SessionEventSaver saver = new SessionEventSaver(eventRepo, props, JsonMapper.builder().build());
        var connectorProvider = proxy(org.springframework.beans.factory.ObjectProvider.class,
                (p, m, args) -> "getIfAvailable".equals(m.getName()) ? connector : null);
        return new SessionManagerService(null, null, null, null, null, null, null,
                e -> { }, new DomainEventPublisher(new ArrayList<>()::add),
                repo, eventRepo, null, null, saver, props, JsonMapper.builder().build(),
                connectorProvider, null, null, null, null);
    }

    @Test
    void 活跃态不可恢复() {
        Map<String, SessionEntity> store = new ConcurrentHashMap<>();
        store.put("s-run", entity("s-run", SessionState.RUNNING, "cli-1"));
        SessionManagerService service = newService(store, null);
        assertThrows(DevMindException.class, () -> service.resume("s-run"));
    }

    @Test
    void 终态无cliSessionId不可恢复() {
        Map<String, SessionEntity> store = new ConcurrentHashMap<>();
        store.put("s-done", entity("s-done", SessionState.DONE, null));
        SessionManagerService service = newService(store, null);
        assertThrows(DevMindException.class, () -> service.resume("s-done"));
    }

    @Test
    void init事件捕获cliSessionId落库() {
        Map<String, SessionEntity> store = new ConcurrentHashMap<>();
        store.put("s-alive", entity("s-alive", SessionState.RUNNING, null));
        AgentNodeConnector connector = proxy(AgentNodeConnector.class, (p, m, args) -> null);
        SessionManagerService service = newService(store, connector);

        // reattach 重建 runtime（内存有句柄后事件才会路由）
        service.onRemoteHello(NODE, List.of("s-alive"));
        service.onRemoteEvent(NODE, new AgentEventFrame("s-alive", "system", "init: cli-9", "stdout",
                System.currentTimeMillis(), Map.of("subtype", "init", "sessionId", "cli-9")));

        assertEquals("cli-9", store.get("s-alive").getCliSessionId());
    }
}
