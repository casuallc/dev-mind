package com.devmind.session.service;

import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.common.event.DomainEventPublisher;
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

/**
 * CAP-34 FR-04 服务端重启后的 hello 对账（DB 兜底）：内存 runtimes 已丢失，
 * 该节点活动状态的存量远程会话——清单内 reattach 挂回（后续 exit 帧可路由），清单外判 FAILED。
 * 仅构造对账路径用得到的依赖，其余传 null。
 */
class SessionManagerReconcileTest {

    private static final String NODE = "node-1";

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private static SessionEntity remoteRunning(String id) {
        SessionEntity ent = new SessionEntity();
        ent.setId(id);
        ent.setProjectId("p1");
        ent.setStatus(SessionState.RUNNING.name());
        ent.setAgentNodeId(NODE);
        ent.setCreatedAt(Instant.now());
        ent.setUpdatedAt(Instant.now());
        return ent;
    }

    private static SessionManagerService newService(Map<String, SessionEntity> store,
                                                    AgentNodeConnector connector,
                                                    List<Object> published) {
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
                e -> { }, new DomainEventPublisher(published::add),
                repo, eventRepo, null, null, saver, props, JsonMapper.builder().build(),
                connectorProvider, null, null, null, null);
    }

    @Test
    void 清单外存量会话判FAILED() {
        Map<String, SessionEntity> store = new ConcurrentHashMap<>();
        store.put("s-gone", remoteRunning("s-gone"));
        List<Object> published = new ArrayList<>();
        SessionManagerService service = newService(store, null, published);

        service.onRemoteHello(NODE, List.of());

        SessionEntity ent = store.get("s-gone");
        assertEquals(SessionState.FAILED.name(), ent.getStatus());
        assertEquals(1, published.size(), "判死应发布 session.completed 领域事件");
    }

    @Test
    void 清单内存量会话reattach后exit可路由() {
        Map<String, SessionEntity> store = new ConcurrentHashMap<>();
        store.put("s-alive", remoteRunning("s-alive"));
        AgentNodeConnector connector = proxy(AgentNodeConnector.class, (p, m, args) -> null);
        SessionManagerService service = newService(store, connector, new ArrayList<>());

        service.onRemoteHello(NODE, List.of("s-alive"));

        assertEquals(SessionState.RUNNING.name(), store.get("s-alive").getStatus(), "reattach 不改状态");
        // reattach 的证明：后续 exit 帧能路由到重建的 runtime → DONE 落库
        service.onRemoteExit(NODE, "s-alive", 0);
        assertEquals(SessionState.DONE.name(), store.get("s-alive").getStatus());
    }
}
