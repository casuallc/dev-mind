package com.devmind.session.service;

import com.devmind.common.event.DomainEventPublisher;
import com.devmind.session.config.SessionProperties;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.repo.SessionEventRepository;
import com.devmind.session.repo.SessionRepoRepository;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.runtime.SessionEventSaver;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CAP-109 全局活跃会话页：GET /api/sessions?statuses= 多状态过滤。
 * 覆盖：多状态命中两个会话且排除终结态；statuses+keyword 组合（search 后内存过滤）；
 * statuses 与单值 status 并存时 statuses 优先。仅构造 list 路径用得到的依赖，其余传 null。
 */
class SessionListStatusesTest {

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private static SessionEntity session(String id, String projectId, String status, String taskSpec,
                                         Instant createdAt) {
        SessionEntity ent = new SessionEntity();
        ent.setId(id);
        ent.setProjectId(projectId);
        ent.setStatus(status);
        ent.setTaskSpec(taskSpec);
        ent.setCreatedAt(createdAt);
        ent.setUpdatedAt(createdAt);
        return ent;
    }

    private static SessionManagerService newService(Map<String, SessionEntity> store) {
        SessionRepository repo = proxy(SessionRepository.class, (p, m, args) -> switch (m.getName()) {
            case "findByStatusInOrderByCreatedAtDesc" -> store.values().stream()
                    .filter(e -> ((java.util.Collection<?>) args[0]).contains(e.getStatus()))
                    .sorted(Comparator.comparing(SessionEntity::getCreatedAt).reversed())
                    .toList();
            case "findByProjectIdOrderByCreatedAtDesc" -> store.values().stream()
                    .filter(e -> args[0].equals(e.getProjectId()))
                    .sorted(Comparator.comparing(SessionEntity::getCreatedAt).reversed())
                    .toList();
            case "search" -> store.values().stream()
                    .filter(e -> e.getTaskSpec() != null
                            && e.getTaskSpec().toLowerCase().contains(((String) args[1]).toLowerCase()))
                    .sorted(Comparator.comparing(SessionEntity::getCreatedAt).reversed())
                    .toList();
            default -> throw new UnsupportedOperationException(m.getName());
        });
        SessionEventRepository eventRepo = proxy(SessionEventRepository.class, (p, m, args) -> null);
        SessionRepoRepository repoRepo = proxy(SessionRepoRepository.class,
                (p, m, args) -> "findBySessionIdInOrderBySortOrderAscIdAsc".equals(m.getName()) ? List.of() : null);
        SessionProperties props = new SessionProperties();
        SessionEventSaver saver = new SessionEventSaver(eventRepo, props, JsonMapper.builder().build());
        return new SessionManagerService(null, null, null, null, null, null, null,
                e -> { }, new DomainEventPublisher(e -> { }),
                repo, eventRepo, null, repoRepo, saver, props, JsonMapper.builder().build(),
                null, null, null, null, null, null, null, null);
    }

    private static Map<String, SessionEntity> fixture() {
        Map<String, SessionEntity> store = new ConcurrentHashMap<>();
        Instant base = Instant.parse("2026-11-17T00:00:00Z");
        store.put("s-run", session("s-run", "p1", "RUNNING", "实现登录", base.plusSeconds(1)));
        store.put("s-wait", session("s-wait", "p2", "WAITING_INPUT", "实现登录页", base.plusSeconds(2)));
        store.put("s-done", session("s-done", "p1", "DONE", "实现登录历史", base.plusSeconds(3)));
        store.put("s-other", session("s-other", "p2", "SUSPENDED", "别的任务", base.plusSeconds(4)));
        return store;
    }

    private static List<String> ids(List<com.devmind.session.dto.SessionView> views) {
        return views.stream().map(com.devmind.session.dto.SessionView::id).toList();
    }

    @Test
    void 多状态过滤命中活跃会话排除终结态() {
        SessionManagerService service = newService(fixture());

        var views = service.list(null, null, null, null, null,
                List.of("RUNNING", "WAITING_INPUT", "WAITING_AUTH"));

        assertEquals(List.of("s-wait", "s-run"), ids(views));
    }

    @Test
    void statuses与keyword组合先search再内存过滤() {
        SessionManagerService service = newService(fixture());

        var views = service.list(null, null, null, null, "登录",
                List.of("RUNNING", "WAITING_INPUT"));

        // search 命中 s-run/s-wait/s-done，内存状态过滤剔掉 DONE
        assertEquals(List.of("s-wait", "s-run"), ids(views));
    }

    @Test
    void statuses优先于单值status() {
        SessionManagerService service = newService(fixture());

        var views = service.list("DONE", null, null, null, null, List.of("RUNNING"));

        assertEquals(List.of("s-run"), ids(views));
    }

    @Test
    void statuses与projectId组合走项目清单内存过滤() {
        SessionManagerService service = newService(fixture());

        var views = service.list(null, "p1", null, null, null,
                List.of("RUNNING", "DONE"));

        assertEquals(List.of("s-done", "s-run"), ids(views));
    }
}
