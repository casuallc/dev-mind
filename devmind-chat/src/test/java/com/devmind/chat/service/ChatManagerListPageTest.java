package com.devmind.chat.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.chat.config.ChatProperties;
import com.devmind.chat.dto.ChatPageView;
import com.devmind.chat.model.ChatSessionEntity;
import com.devmind.chat.repo.ChatEventRepository;
import com.devmind.chat.repo.ChatSessionRepository;
import com.devmind.chat.runtime.ChatEventSaver;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.notification.NotificationPublisher;
import org.junit.jupiter.api.Test;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-23 问答列表分页加载（{@code ChatManagerService.listPage}）：
 * 终态按 createdAt desc 分页 + hasMore；当前用户活跃问答恒在且不重复；
 * status/keyword 服务端过滤；keyword 分支不做活跃合并；createdBy 数据隔离。
 * mock 风格同 {@link ChatManagerServiceTest}（JDK 动态代理假仓储，无 Spring 上下文）。
 */
class ChatManagerListPageTest {

    private static final String USER = "tester";
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    /** ageRank 越大越老（createdAt 越小） */
    private static ChatSessionEntity chat(String id, String createdBy, String status, int ageRank) {
        ChatSessionEntity e = new ChatSessionEntity();
        e.setId(id);
        e.setCreatedBy(createdBy);
        e.setStatus(status);
        e.setTitle("主题 " + id);
        e.setCreatedAt(BASE.plusSeconds(10_000L - ageRank));
        e.setUpdatedAt(e.getCreatedAt());
        return e;
    }

    /** Pageable 重载在假仓储里手工切片（与服务端 head-fetch 语义一致） */
    private static List<ChatSessionEntity> slice(List<ChatSessionEntity> sorted, Pageable p) {
        int from = (int) Math.min(p.getOffset(), sorted.size());
        int to = Math.min(from + p.getPageSize(), sorted.size());
        return sorted.subList(from, to);
    }

    /** 固定当前用户 tester（同 ChatManagerServiceTest.fakeIdentity） */
    private static IdentityService fakeIdentity() {
        return new IdentityService(null, null, null) {
            @Override
            public String currentActor() {
                return USER;
            }

            @Override
            public Optional<UserEntity> currentUser() {
                return Optional.empty();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> emptyProvider() {
        return proxy(org.springframework.beans.factory.ObjectProvider.class, (p, m, args) -> null);
    }

    private static ChatManagerService newService(Map<String, ChatSessionEntity> store) {
        ChatSessionRepository repo = proxy(ChatSessionRepository.class, (p, m, args) -> {
            List<ChatSessionEntity> all = store.values().stream()
                    .sorted(Comparator.comparing(ChatSessionEntity::getCreatedAt).reversed())
                    .toList();
            return switch (m.getName()) {
                case "findByCreatedByOrderByCreatedAtDesc" -> {
                    List<ChatSessionEntity> r = all.stream()
                            .filter(e -> args[0].equals(e.getCreatedBy())).toList();
                    yield args.length > 1 ? slice(r, (Pageable) args[1]) : r;
                }
                case "findByCreatedByAndStatusOrderByCreatedAtDesc" -> slice(all.stream()
                        .filter(e -> args[0].equals(e.getCreatedBy()) && args[1].equals(e.getStatus()))
                        .toList(), (Pageable) args[2]);
                case "findByCreatedByAndStatusInOrderByCreatedAtDesc" -> all.stream()
                        .filter(e -> args[0].equals(e.getCreatedBy()))
                        .filter(e -> ((Collection<?>) args[1]).contains(e.getStatus()))
                        .toList();
                case "search" -> {
                    String kw = ((String) args[1]).toLowerCase();
                    yield slice(all.stream()
                            .filter(e -> args[0].equals(e.getCreatedBy()))
                            .filter(e -> e.getId().toLowerCase().contains(kw)
                                    || (e.getTitle() != null && e.getTitle().toLowerCase().contains(kw))
                                    || (e.getSummary() != null && e.getSummary().toLowerCase().contains(kw)))
                            .toList(), (Pageable) args[2]);
                }
                default -> throw new UnsupportedOperationException(m.getName());
            };
        });
        ChatEventRepository eventRepo = proxy(ChatEventRepository.class, (p, m, args) ->
                "saveAll".equals(m.getName()) ? args[0] : null);
        ChatProperties props = new ChatProperties();
        ChatEventSaver saver = new ChatEventSaver(eventRepo, props, JsonMapper.builder().build());
        NotificationPublisher noopPublisher = e -> { };
        return new ChatManagerService(fakeIdentity(), noopPublisher,
                repo, eventRepo, saver, props, JsonMapper.builder().build(),
                emptyProvider(), emptyProvider(), emptyProvider(),
                emptyProvider(), emptyProvider(), emptyProvider(),
                null, null);
    }

    private static Map<String, ChatSessionEntity> storeOf(List<ChatSessionEntity> chats) {
        Map<String, ChatSessionEntity> store = new LinkedHashMap<>();
        for (ChatSessionEntity e : chats) {
            store.put(e.getId(), e);
        }
        return store;
    }

    private static List<ChatSessionEntity> terminalBatch(String createdBy, String status, int count, int idOffset) {
        List<ChatSessionEntity> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(chat("c-" + status.toLowerCase() + "-" + (idOffset + i), createdBy, status, idOffset + i));
        }
        return list;
    }

    @Test
    void 终态问答按createdAt倒序分页() {
        ChatManagerService service = newService(storeOf(terminalBatch(USER, "DONE", 15, 0)));

        ChatPageView page1 = service.listPage(null, null, 10, 0);
        assertEquals(10, page1.items().size(), "第一页 10 条");
        assertTrue(page1.hasMore(), "还有更早数据");
        assertEquals("c-done-0", page1.items().get(0).id(), "最新在前");
        assertEquals("c-done-9", page1.items().get(9).id());

        ChatPageView page2 = service.listPage(null, null, 10, 10);
        assertEquals(5, page2.items().size(), "第二页剩余 5 条");
        assertFalse(page2.hasMore());
        assertEquals("c-done-10", page2.items().get(0).id());
        assertEquals("c-done-14", page2.items().get(4).id());

        List<String> allIds = new ArrayList<>(page1.items().stream().map(v -> v.id()).toList());
        allIds.addAll(page2.items().stream().map(v -> v.id()).toList());
        assertEquals(15, allIds.stream().distinct().count(), "两页合并不重复不遗漏");
    }

    @Test
    void 活跃问答任意页恒在且不重复() {
        List<ChatSessionEntity> all = new ArrayList<>(terminalBatch(USER, "DONE", 15, 0));
        // 最老的仍在运行的问答（会被「最近 10 条」窗口挤掉，活跃恒在规则要保证它出现）
        all.add(chat("c-running-old", USER, SessionState.RUNNING.name(), 999));
        // 其他用户的活跃/终态问答一概不出现（createdBy 隔离）
        all.add(chat("c-other-running", "someone-else", SessionState.RUNNING.name(), 500));
        all.add(chat("c-other-done", "someone-else", "DONE", 5));
        ChatManagerService service = newService(storeOf(all));

        ChatPageView page1 = service.listPage(null, null, 10, 0);
        assertEquals(11, page1.items().size(), "活跃 + 10 条终态");
        assertTrue(page1.items().stream().anyMatch(v -> v.id().equals("c-running-old")), "活跃问答在第一页");
        assertTrue(page1.items().stream().allMatch(v -> USER.equals(v.createdBy())), "只含当前用户的问答");

        ChatPageView page2 = service.listPage(null, null, 10, 10);
        long runningOccurrences = page2.items().stream().filter(v -> v.id().equals("c-running-old")).count();
        assertEquals(1, runningOccurrences, "活跃问答在第二页恒在且只出现一次");
        assertFalse(page2.hasMore());
        assertEquals(6, page2.items().size(), "活跃 + 剩余 5 条终态");
    }

    @Test
    void status过滤服务端化且分页正确() {
        List<ChatSessionEntity> all = new ArrayList<>(terminalBatch(USER, "DONE", 8, 0));
        all.addAll(terminalBatch(USER, "FAILED", 7, 100));
        ChatManagerService service = newService(storeOf(all));

        ChatPageView page = service.listPage("DONE", null, 10, 0);
        assertEquals(8, page.items().size());
        assertTrue(page.items().stream().allMatch(v -> v.status().equals("DONE")));
        assertFalse(page.hasMore());

        // 显式 status 过滤时活跃不混入（status=FAILED 不含 RUNNING）
        all.add(chat("c-running", USER, SessionState.RUNNING.name(), 999));
        ChatPageView failed = service.listPage("FAILED", null, 10, 0);
        assertEquals(7, failed.items().size());
        assertTrue(failed.items().stream().allMatch(v -> v.status().equals("FAILED")));
    }

    @Test
    void 关键词搜索分页且不做活跃合并() {
        List<ChatSessionEntity> all = new ArrayList<>(terminalBatch(USER, "DONE", 15, 0));
        all.add(chat("c-running-old", USER, SessionState.RUNNING.name(), 999));
        // 他人的问答标题也含关键词，不能命中（createdBy 隔离）
        all.add(chat("c-other", "someone-else", "DONE", 3));
        ChatManagerService service = newService(storeOf(all));

        // 标题均为 "主题 c-..."，搜「主题」命中当前用户全部 16 条；keyword 分支不做活跃合并
        ChatPageView page1 = service.listPage(null, "主题", 10, 0);
        assertEquals(10, page1.items().size(), "keyword 分支直接对搜索结果分页，活跃不并入");
        assertTrue(page1.hasMore());
        assertTrue(page1.items().stream().noneMatch(v -> v.id().equals("c-running-old")),
                "最老的活跃问答在 keyword 分支不恒在（显式搜索意图）");
        assertTrue(page1.items().stream().allMatch(v -> USER.equals(v.createdBy())));

        ChatPageView page2 = service.listPage(null, "主题", 10, 10);
        assertEquals(6, page2.items().size());
        assertFalse(page2.hasMore());

        // 无命中 → 空页
        ChatPageView miss = service.listPage(null, "不存在的关键词", 10, 0);
        assertEquals(0, miss.items().size());
        assertFalse(miss.hasMore());
    }
}
