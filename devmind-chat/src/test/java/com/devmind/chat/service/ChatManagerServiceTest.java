package com.devmind.chat.service;

import com.devmind.auth.IdentityService;
import com.devmind.chat.config.ChatProperties;
import com.devmind.chat.dto.ChatView;
import com.devmind.chat.dto.CreateChatRequest;
import com.devmind.chat.dto.ImageRef;
import com.devmind.chat.model.ChatEventEntity;
import com.devmind.chat.model.ChatSessionEntity;
import com.devmind.chat.repo.ChatEventRepository;
import com.devmind.chat.repo.ChatSessionRepository;
import com.devmind.chat.runtime.ChatEventSaver;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.common.notification.NotificationEvent;
import com.devmind.notification.NotificationPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-30 ChatManagerService 单测（无 Spring 上下文，fake executor 需本机有 node）：
 * repository 用 JDK 动态代理内存 fake，事件落库跑真实 ChatEventSaver（flush 周期 50ms）。
 * 覆盖：创建（标题/沙箱目录）→ 授权 → 多轮输入 → finish 优雅结束（DONE + 沙箱递归清理）
 * → 事件落库 → 删除清记录。
 */
class ChatManagerServiceTest {

    @TempDir
    Path tempDir;

    private FakeChatRepo chats;
    private FakeEventRepo events;
    private ChatEventSaver saver;
    private ChatManagerService service;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    static class FakeChatRepo {
        final Map<String, ChatSessionEntity> store = new ConcurrentHashMap<>();

        ChatSessionRepository jpa() {
            return proxy(ChatSessionRepository.class, (p, m, args) -> switch (m.getName()) {
                case "save" -> {
                    ChatSessionEntity e = (ChatSessionEntity) args[0];
                    store.put(e.getId(), e);
                    yield e;
                }
                case "findById" -> Optional.ofNullable(store.get((String) args[0]));
                case "findAll" -> new ArrayList<>(store.values());
                case "findByCreatedByOrderByCreatedAtDesc" -> store.values().stream()
                        .filter(e -> args[0].equals(e.getCreatedBy()))
                        .sorted(Comparator.comparing(ChatSessionEntity::getCreatedAt).reversed())
                        .toList();
                case "delete" -> {
                    store.remove(((ChatSessionEntity) args[0]).getId());
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    static class FakeEventRepo {
        // 写线程（saver flush）与读线程（断言轮询）不同，用 COW 避免并发修改
        final List<ChatEventEntity> store = new java.util.concurrent.CopyOnWriteArrayList<>();

        ChatEventRepository jpa() {
            return proxy(ChatEventRepository.class, (p, m, args) -> switch (m.getName()) {
                case "saveAll" -> {
                    store.addAll((List<ChatEventEntity>) args[0]);
                    yield args[0];
                }
                case "findByChatIdAndSeqGreaterThanOrderBySeqAsc" -> store.stream()
                        .filter(e -> e.getChatId().equals(args[0]) && e.getSeq() > (Long) args[1])
                        .toList();
                case "deleteByChatId" -> {
                    store.removeIf(e -> e.getChatId().equals(args[0]));
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    /** 无认证上下文：固定当前用户 tester（覆盖 currentUser 防空 userRepo 被踩）。 */
    private static IdentityService fakeIdentity() {
        return new IdentityService(null, null, null) {
            @Override
            public String currentActor() {
                return "tester";
            }

            @Override
            public Optional<com.devmind.auth.model.UserEntity> currentUser() {
                return Optional.empty();
            }
        };
    }

    @BeforeEach
    void setUp() {
        chats = new FakeChatRepo();
        events = new FakeEventRepo();
        ChatProperties props = new ChatProperties();
        props.setExecutor("fake");
        props.setWorkDir(tempDir.toString());
        props.setEventFlushMs(50);
        ObjectMapper mapper = JsonMapper.builder().build();
        saver = new ChatEventSaver(events.jpa(), props, mapper);
        saver.start();
        NotificationPublisher noopPublisher = (NotificationEvent e) -> { };
        service = new ChatManagerService(fakeIdentity(), noopPublisher,
                chats.jpa(), events.jpa(), saver, props, mapper,
                // 无 agent 模块：ObjectProvider 空实现（getIfAvailable 恒 null → 本机路由）
                proxyObjectProvider(),
                // 无 attachment 模块：getIfAvailable 恒 null → 带图输入报错
                proxyObjectProvider());
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> proxyObjectProvider() {
        return proxy(org.springframework.beans.factory.ObjectProvider.class, (p, m, args) -> switch (m.getName()) {
            case "getIfAvailable" -> null;
            case "forEach" -> null;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        saver.stop();
    }

    private static void await(String what, BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待超时: " + what);
    }

    @Test
    void 问答全链路_创建授权输入结束清理删除() throws Exception {
        ChatView v = service.create(new CreateChatRequest("帮我解释下 worktree 是什么", "", "", "local"));
        assertEquals(SessionState.RUNNING.name(), v.status());
        assertEquals("帮我解释下 worktree 是什么", v.title());
        Path sandbox = tempDir.resolve(v.id());
        assertTrue(Files.isDirectory(sandbox), "创建后沙箱目录应存在");

        // fake-agent 约 2s 后发 permission_request → WAITING_AUTH
        await("进入 WAITING_AUTH", () -> service.get(v.id()).state() == SessionState.WAITING_AUTH);
        service.authorize(v.id(), true, "once", null);
        await("授权后回 RUNNING", () -> service.get(v.id()).state() == SessionState.RUNNING);

        // 多轮输入
        service.input(v.id(), "继续展开说说");
        await("收到 fake 回复", () -> events.store.stream()
                .anyMatch(e -> e.getContent() != null && e.getContent().contains("收到：继续展开说说")));

        // 优雅结束：stdin EOF → fake 发 result 退出 → DONE + 沙箱递归清理
        service.finish(v.id());
        await("会话 DONE", () -> service.get(v.id()).status().equals(SessionState.DONE.name()));
        await("沙箱目录已清理", () -> !Files.exists(sandbox));
        await("事件已落库", () -> !events.store.isEmpty());

        // 删除：事件与记录清除
        service.deleteChat(v.id());
        assertFalse(chats.store.containsKey(v.id()));
        assertTrue(events.store.isEmpty(), "删除后事件应清空");
    }

    @Test
    void 带图输入但附件模块未装配时报错不静默丢图() throws Exception {
        ChatView v = service.create(new CreateChatRequest("看图", "", "", "local"));
        // resolver getIfAvailable 恒 null → CONFLICT
        assertThrows(com.devmind.common.exception.DevMindException.class,
                () -> service.input(v.id(), "", List.of(new ImageRef("abc123", "a.png", "image/png"))));
        // 纯文本输入不受影响
        service.input(v.id(), "纯文本没问题");
        service.kill(v.id());
    }

    @Test
    void 强杀清理沙箱并标记终止() throws Exception {
        ChatView v = service.create(new CreateChatRequest("测试 kill", "", "", "local"));
        Path sandbox = tempDir.resolve(v.id());
        await("进程拉起", () -> Files.isDirectory(sandbox));
        service.kill(v.id());
        assertEquals(SessionState.TERMINATED.name(), service.get(v.id()).status());
        await("沙箱目录已清理", () -> !Files.exists(sandbox));
    }

    @Test
    void 挂起恢复_沙箱保留() throws Exception {
        ChatView v = service.create(new CreateChatRequest("测试挂起", "", "", "local"));
        Path sandbox = tempDir.resolve(v.id());
        await("进程拉起", () -> Files.isDirectory(sandbox));
        service.suspend(v.id());
        assertEquals(SessionState.SUSPENDED.name(), service.get(v.id()).status());
        assertTrue(Files.isDirectory(sandbox), "挂起应保留沙箱（resume 复用）");

        ChatView r = service.resume(v.id());
        assertEquals(SessionState.RUNNING.name(), r.status());
        await("恢复后进入 WAITING_AUTH", () -> service.get(v.id()).state() == SessionState.WAITING_AUTH);
        service.kill(v.id());
    }
}
