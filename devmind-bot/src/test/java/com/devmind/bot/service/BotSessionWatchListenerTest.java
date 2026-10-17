package com.devmind.bot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.devmind.bot.config.BotProperties;
import com.devmind.bot.fake.FakeBotAdapter;
import com.devmind.bot.model.BotSessionWatchEntity;
import com.devmind.bot.spi.BotAdapter;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.session.dto.SessionView;
import com.devmind.session.service.SessionManagerService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** CAP-75 FR-20 会话终态推送：订阅命中即推、一次性消费、失败不补推。 */
class BotSessionWatchListenerTest {

    private static final String SESSION = "s9f2a1b3c4d5";

    private final BotSessionWatchService watchService = mock(BotSessionWatchService.class);
    private final SessionManagerService sessionManager = mock(SessionManagerService.class);
    private final FakeBotAdapter adapter = new FakeBotAdapter();

    private BotSessionWatchListener listener;

    @BeforeEach
    void setUp() {
        listener = listenerWith(adapter);
    }

    private BotSessionWatchListener listenerWith(BotAdapter a) {
        BotProperties props = new BotProperties();
        props.setPlatformBaseUrl("https://dev.example.com/");
        @SuppressWarnings("unchecked")
        ObjectProvider<BotAdapterRegistry> provider = mock(ObjectProvider.class);
        BotAdapterRegistry registry = new BotAdapterRegistry(List.of(a), msg -> { });
        when(provider.getIfAvailable()).thenReturn(registry);
        return new BotSessionWatchListener(watchService, sessionManager, provider, props);
    }

    private static SimpleDomainEvent completed(String sessionId, boolean success) {
        return SimpleDomainEvent.of("session.completed", "p1", null, "alice",
                "会话 " + sessionId + (success ? " 完成" : " 失败"), "SESSION", sessionId, success);
    }

    private static BotSessionWatchEntity watch(String sessionId, String channel, String chatId,
                                               String taskSpec) {
        BotSessionWatchEntity w = new BotSessionWatchEntity();
        w.setSessionId(sessionId);
        w.setChannel(channel);
        w.setExternalChatId(chatId);
        w.setTaskSpec(taskSpec);
        return w;
    }

    private static SessionView view(String id, String projectId, String taskSpec, String summary) {
        SessionView s = mock(SessionView.class);
        when(s.id()).thenReturn(id);
        when(s.projectId()).thenReturn(projectId);
        when(s.taskSpec()).thenReturn(taskSpec);
        when(s.summary()).thenReturn(summary);
        when(s.status()).thenReturn("DONE");
        when(s.createdAt()).thenReturn(Instant.now());
        return s;
    }

    @Test
    void pushesCompletedSessionAndConsumesWatch() {
        when(watchService.take(SESSION)).thenReturn(Optional.of(
                watch(SESSION, "fake", "chat-1", "做导出")));
        SessionView v = view(SESSION, "p1", "做导出功能", "已完成导出逻辑\n改了 3 个文件");
        when(sessionManager.get(SESSION)).thenReturn(v);
        listener.push(completed(SESSION, true));

        List<FakeBotAdapter.Reply> pushes = adapter.pushes();
        assertEquals(1, pushes.size());
        assertEquals("chat-1", pushes.get(0).externalChatId());
        String text = pushes.get(0).text();
        assertTrue(text.contains("✅ 会话 s9f2a1b3 已完成"), text);
        assertTrue(text.contains("任务：做导出功能"), text);
        assertTrue(text.contains("摘要：已完成导出逻辑 改了 3 个文件"), text);
        assertTrue(text.contains("https://dev.example.com/projects/p1"), text);
        // 取走即删（一次性）：订阅库只被 take 一次，推送后不再有残留
        verify(watchService).take(SESSION);
    }

    @Test
    void pushesFailedSessionWithFallbackTaskSpec() {
        when(watchService.take(SESSION)).thenReturn(Optional.of(
                watch(SESSION, "fake", "chat-9", "做导出")));
        // 会话详情读不到（已删）：退回订阅时留的 taskSpec，摘要省略，不抛错
        when(sessionManager.get(SESSION)).thenThrow(
                new DevMindException(ErrorCode.NOT_FOUND, "会话不存在"));

        listener.push(completed(SESSION, false));

        List<FakeBotAdapter.Reply> pushes = adapter.pushes();
        assertEquals(1, pushes.size());
        assertEquals("chat-9", pushes.get(0).externalChatId());
        String text = pushes.get(0).text();
        assertTrue(text.contains("❌ 会话 s9f2a1b3 已失败"), text);
        assertTrue(text.contains("任务：做导出"), text);
        assertFalse(text.contains("摘要："), text);
        assertFalse(text.contains("平台查看"), text);
    }

    @Test
    void skipsWhenNoWatch() {
        when(watchService.take(SESSION)).thenReturn(Optional.empty());
        listener.push(completed(SESSION, true));
        assertTrue(adapter.pushes().isEmpty());
        verify(sessionManager, never()).get(any());
    }

    @Test
    void sendFailureIsLoggedNotRethrown() {
        when(watchService.take(SESSION)).thenReturn(Optional.of(
                watch(SESSION, "fake", "chat-1", "做导出")));
        SessionView v = view(SESSION, "p1", "做导出", "ok");
        when(sessionManager.get(SESSION)).thenReturn(v);
        BotSessionWatchListener boom = listenerWith(new BoomAdapter());

        boom.push(completed(SESSION, true)); // 不抛：推送失败只记日志（订阅已消费，不补推）

        assertTrue(adapter.pushes().isEmpty());
        verify(watchService).take(SESSION);
    }

    @Test
    void ignoresNonSessionEvents() {
        listener.onSessionCompleted(SimpleDomainEvent.of("build.completed", "p1", null, "a",
                "构建完成", "BUILD", "b1", true));
        listener.onSessionCompleted(completed(null, true));
        listener.onSessionCompleted(SimpleDomainEvent.of("session.completed", "p1", null, "a",
                "会话完成", "CHAT", "c1", true));
        // 过滤是同步的：提交线程池都不是，立即断言无副作用
        verify(watchService, never()).take(any());
        assertTrue(adapter.pushes().isEmpty());
    }

    @Test
    void eventListenerSubmitsPushOnVirtualThread() throws InterruptedException {
        when(watchService.take(SESSION)).thenReturn(Optional.of(
                watch(SESSION, "fake", "chat-2", "做导出")));
        SessionView v = view(SESSION, "p1", "做导出", "ok");
        when(sessionManager.get(SESSION)).thenReturn(v);

        listener.onSessionCompleted(completed(SESSION, true));

        long deadline = System.currentTimeMillis() + 5_000;
        while (adapter.pushes().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, adapter.pushes().size());
        assertEquals("chat-2", adapter.pushes().get(0).externalChatId());
    }

    /** send 抛错的适配器（平台侧故障）。 */
    private static final class BoomAdapter extends FakeBotAdapter {
        @Override
        public void send(String externalChatId, String text) {
            throw new IllegalStateException("boom");
        }
    }
}
