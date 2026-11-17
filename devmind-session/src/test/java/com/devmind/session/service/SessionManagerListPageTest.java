package com.devmind.session.service;

import com.devmind.common.agent.runtime.SessionState;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.session.config.SessionProperties;
import com.devmind.session.dto.SessionPageView;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.repo.SessionEventRepository;
import com.devmind.session.repo.SessionRepoRepository;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.runtime.SessionEventSaver;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-23 会话列表分页加载（{@code SessionManagerService.listPage}）：
 * 终态按 createdAt desc 分页 + hasMore；活跃会话（RUNNING/WAITING_INPUT/WAITING_AUTH）恒在且不重复；
 * status/keyword 过滤与分页的组合；keyword 分支不做活跃合并。
 * mock 风格同 {@link SessionManagerReconcileTest}（JDK 动态代理假仓储）。
 */
class SessionManagerListPageTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private static SessionEntity session(String id, String projectId, String status, int createdSecondsAgo) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setProjectId(projectId);
        e.setStatus(status);
        e.setTaskSpec("task " + id);
        e.setCreatedAt(BASE.plusSeconds(10_000L - createdSecondsAgo));
        e.setUpdatedAt(e.getCreatedAt());
        return e;
    }

    /** 派生查询统一 createdAt desc；Pageable 重载在假仓储里手工切片（与服务端 head-fetch 语义一致） */
    private static List<SessionEntity> slice(List<SessionEntity> sorted, Pageable p) {
        int from = (int) Math.min(p.getOffset(), sorted.size());
        int to = Math.min(from + p.getPageSize(), sorted.size());
        return sorted.subList(from, to);
    }

    private static SessionManagerService newService(Map<String, SessionEntity> store) {
        SessionRepository repo = proxy(SessionRepository.class, (p, m, args) -> {
            List<SessionEntity> all = store.values().stream()
                    .sorted(Comparator.comparing(SessionEntity::getCreatedAt).reversed())
                    .toList();
            return switch (m.getName()) {
                case "findByStatusInOrderByCreatedAtDesc" -> all.stream()
                        .filter(e -> ((Collection<?>) args[0]).contains(e.getStatus()))
                        .toList();
                case "findByStatusOrderByCreatedAtDesc" -> {
                    List<SessionEntity> r = all.stream().filter(e -> args[0].equals(e.getStatus())).toList();
                    yield args.length > 1 ? slice(r, (Pageable) args[1]) : r;
                }
                case "findByProjectIdOrderByCreatedAtDesc" -> {
                    List<SessionEntity> r = all.stream().filter(e -> args[0].equals(e.getProjectId())).toList();
                    yield args.length > 1 ? slice(r, (Pageable) args[1]) : r;
                }
                case "findByProjectIdAndStatusOrderByCreatedAtDesc" -> slice(all.stream()
                        .filter(e -> args[0].equals(e.getProjectId()) && args[1].equals(e.getStatus()))
                        .toList(), (Pageable) args[2]);
                case "findByWorkItemIdOrderByCreatedAtDesc" -> {
                    List<SessionEntity> r = all.stream()
                            .filter(e -> Objects.equals(args[0], e.getWorkItemId())).toList();
                    yield args.length > 1 ? slice(r, (Pageable) args[1]) : r;
                }
                case "findByRequirementIdOrderByCreatedAtDesc" -> {
                    List<SessionEntity> r = all.stream()
                            .filter(e -> Objects.equals(args[0], e.getRequirementId())).toList();
                    yield args.length > 1 ? slice(r, (Pageable) args[1]) : r;
                }
                case "search" -> {
                    String kw = ((String) args[1]).toLowerCase();
                    List<SessionEntity> r = all.stream()
                            .filter(e -> args[0] == null || args[0].equals(e.getProjectId()))
                            .filter(e -> e.getId().toLowerCase().contains(kw)
                                    || (e.getTaskSpec() != null && e.getTaskSpec().toLowerCase().contains(kw))
                                    || (e.getSummary() != null && e.getSummary().toLowerCase().contains(kw)))
                            .toList();
                    yield args.length > 2 ? slice(r, (Pageable) args[2]) : r;
                }
                case "findAll" -> new PageImpl<>(slice(all, (Pageable) args[0]), (Pageable) args[0], all.size());
                default -> throw new UnsupportedOperationException(m.getName());
            };
        });
        SessionRepoRepository sessionRepoRepo = proxy(SessionRepoRepository.class,
                (p, m, args) -> List.of());
        SessionEventRepository eventRepo = proxy(SessionEventRepository.class, (p, m, args) ->
                "saveAll".equals(m.getName()) ? args[0] : null);
        SessionProperties props = new SessionProperties();
        SessionEventSaver saver = new SessionEventSaver(eventRepo, props, JsonMapper.builder().build());
        var connectorProvider = proxy(org.springframework.beans.factory.ObjectProvider.class,
                (p, m, args) -> null);
        return new SessionManagerService(null, null, null, null, null, null, null,
                e -> { }, new DomainEventPublisher(msg -> { }),
                repo, eventRepo, null, sessionRepoRepo, saver, props, JsonMapper.builder().build(),
                connectorProvider, null, null, null, null, null, null, null);
    }

    private static Map<String, SessionEntity> storeOf(SessionEntity... sessions) {
        Map<String, SessionEntity> store = new LinkedHashMap<>();
        for (SessionEntity e : sessions) {
            store.put(e.getId(), e);
        }
        return store;
    }

    private static SessionEntity[] terminalBatch(String projectId, String status, int count, int idOffset) {
        SessionEntity[] arr = new SessionEntity[count];
        for (int i = 0; i < count; i++) {
            // 第三个参数越大越老；idOffset 避免不同批次撞 id/时间
            arr[i] = session("s-" + status.toLowerCase() + "-" + (idOffset + i), projectId, status, idOffset + i);
        }
        return arr;
    }

    @Test
    void 终态会话按createdAt倒序分页() {
        Map<String, SessionEntity> store = storeOf(terminalBatch("p1", "DONE", 15, 0));
        SessionManagerService service = newService(store);

        SessionPageView page1 = service.listPage(null, "p1", null, null, null, 10, 0);
        assertEquals(10, page1.items().size(), "第一页 10 条");
        assertTrue(page1.hasMore(), "还有更早数据");
        // 最新在前（数字越小越新）
        assertEquals("s-done-0", page1.items().get(0).id());
        assertEquals("s-done-9", page1.items().get(9).id());

        SessionPageView page2 = service.listPage(null, "p1", null, null, null, 10, 10);
        assertEquals(5, page2.items().size(), "第二页剩余 5 条");
        assertFalse(page2.hasMore(), "没有更早数据了");
        assertEquals("s-done-10", page2.items().get(0).id());
        assertEquals("s-done-14", page2.items().get(4).id());

        List<String> allIds = new ArrayList<>(page1.items().stream().map(v -> v.id()).toList());
        allIds.addAll(page2.items().stream().map(v -> v.id()).toList());
        assertEquals(15, allIds.stream().distinct().count(), "两页合并不重复不遗漏");
    }

    @Test
    void 活跃会话任意页恒在且不重复() {
        List<SessionEntity> all = new ArrayList<>(List.of(terminalBatch("p1", "DONE", 15, 0)));
        // 最老的仍在运行的会话（会被「最近 10 条」窗口挤掉，活跃恒在规则要保证它出现）
        SessionEntity running = session("s-running-old", "p1", SessionState.RUNNING.name(), 999);
        SessionEntity otherProjectRunning = session("s-running-p2", "p2", SessionState.RUNNING.name(), 500);
        all.add(running);
        all.add(otherProjectRunning);
        SessionManagerService service = newService(storeOf(all.toArray(SessionEntity[]::new)));

        SessionPageView page1 = service.listPage(null, "p1", null, null, null, 10, 0);
        assertEquals(11, page1.items().size(), "活跃 + 10 条终态");
        assertTrue(page1.items().stream().anyMatch(v -> v.id().equals("s-running-old")), "活跃会话在第一页");
        assertTrue(page1.items().stream().noneMatch(v -> v.id().equals("s-running-p2")), "其他项目的活跃不进 scope");

        SessionPageView page2 = service.listPage(null, "p1", null, null, null, 10, 10);
        long runningOccurrences = page2.items().stream().filter(v -> v.id().equals("s-running-old")).count();
        assertEquals(1, runningOccurrences, "活跃会话在第二页恒在且只出现一次");
        assertFalse(page2.hasMore());
        // 第二页终态 = 第一页之外的 5 条 DONE
        List<String> terminalIds = page2.items().stream()
                .filter(v -> !v.id().equals("s-running-old")).map(v -> v.id()).toList();
        assertEquals(5, terminalIds.size());
    }

    @Test
    void status过滤下推到查询且分页正确() {
        List<SessionEntity> all = new ArrayList<>(List.of(terminalBatch("p1", "DONE", 8, 0)));
        all.addAll(List.of(terminalBatch("p1", "FAILED", 7, 100)));
        SessionManagerService service = newService(storeOf(all.toArray(SessionEntity[]::new)));

        SessionPageView page = service.listPage("DONE", "p1", null, null, null, 10, 0);
        assertEquals(8, page.items().size());
        assertTrue(page.items().stream().allMatch(v -> v.status().equals("DONE")));
        assertFalse(page.hasMore());

        // 活跃恒在也受显式 status 过滤约束（status=FAILED 时不混入 RUNNING）
        all.add(session("s-running", "p1", SessionState.RUNNING.name(), 999));
        SessionPageView failed = service.listPage("FAILED", "p1", null, null, null, 10, 0);
        assertTrue(failed.items().stream().allMatch(v -> v.status().equals("FAILED")));
        assertEquals(7, failed.items().size());

        // 无 projectId 的纯 status 分支
        SessionPageView doneOnly = service.listPage("DONE", null, null, null, null, 10, 0);
        assertEquals(8, doneOnly.items().size());
        assertTrue(doneOnly.items().stream().allMatch(v -> v.status().equals("DONE")));
    }

    @Test
    void 关键词搜索分页且不做活跃合并() {
        List<SessionEntity> all = new ArrayList<>(List.of(terminalBatch("p1", "DONE", 15, 0)));
        SessionEntity running = session("s-running-old", "p1", SessionState.RUNNING.name(), 999);
        all.add(running);
        SessionManagerService service = newService(storeOf(all.toArray(SessionEntity[]::new)));

        // taskSpec 均为 "task s-..."，搜 "task" 命中全部 16 条；keyword 分支不做活跃合并
        SessionPageView page1 = service.listPage(null, null, null, null, "task", 10, 0);
        assertEquals(10, page1.items().size(), "keyword 分支直接对搜索结果分页，活跃不并入");
        assertTrue(page1.hasMore());
        assertTrue(page1.items().stream().noneMatch(v -> v.id().equals("s-running-old")),
                "最老的活跃会话在 keyword 分支不恒在（显式搜索意图）");

        SessionPageView page2 = service.listPage(null, null, null, null, "task", 10, 10);
        assertEquals(6, page2.items().size());
        assertFalse(page2.hasMore());

        // 项目内关键词搜索限定 scope
        SessionPageView scoped = service.listPage(null, "p2", null, null, "task", 10, 0);
        assertEquals(0, scoped.items().size());
        assertFalse(scoped.hasMore());
    }
}
