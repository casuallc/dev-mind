package com.devmind.bot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.devmind.bot.config.BotProperties;
import com.devmind.bot.fake.FakeBotAdapter;
import com.devmind.bot.model.BotJobWatchEntity;
import com.devmind.bot.spi.BotAdapter;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.deploy.event.DeploymentCompletedEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** CAP-75 FR-25 作业终态推送：三类事件命中即推、一次性消费、失败不补推、无关事件零开销。 */
class BotJobWatchListenerTest {

    private final BotJobWatchService watchService = mock(BotJobWatchService.class);
    private final FakeBotAdapter adapter = new FakeBotAdapter();

    private BotJobWatchListener listener;

    @BeforeEach
    void setUp() {
        listener = listenerWith(adapter);
    }

    private BotJobWatchListener listenerWith(BotAdapter a) {
        BotProperties props = new BotProperties();
        props.setPlatformBaseUrl("https://dev.example.com/");
        @SuppressWarnings("unchecked")
        ObjectProvider<BotAdapterRegistry> provider = mock(ObjectProvider.class);
        BotAdapterRegistry registry = new BotAdapterRegistry(List.of(a), msg -> { });
        when(provider.getIfAvailable()).thenReturn(registry);
        return new BotJobWatchListener(watchService, provider, props);
    }

    private static BotJobWatchEntity watch(String entityType, String entityId, String label) {
        BotJobWatchEntity w = new BotJobWatchEntity();
        w.setEntityType(entityType);
        w.setEntityId(entityId);
        w.setChannel("fake");
        w.setExternalChatId("chat-1");
        w.setLabel(label);
        return w;
    }

    @Test
    void pushesBuildCompletedAndConsumesWatch() {
        when(watchService.take("BUILD", "12"))
                .thenReturn(Optional.of(watch("BUILD", "12", "触发构建")));

        listener.push("BUILD", "12", "p1", "构建 #12 SUCCESS（master）", true);

        List<FakeBotAdapter.Reply> pushes = adapter.pushes();
        assertEquals(1, pushes.size());
        assertEquals("chat-1", pushes.get(0).externalChatId());
        String text = pushes.get(0).text();
        assertTrue(text.contains("✅ 触发构建 已完成"), text);
        assertTrue(text.contains("构建 #12 SUCCESS（master）"), text);
        assertTrue(text.contains("https://dev.example.com/projects/p1"), text);
        verify(watchService).take("BUILD", "12");
    }

    @Test
    void pushesFailedTestRun() {
        when(watchService.take("TEST_RUN", "9"))
                .thenReturn(Optional.of(watch("TEST_RUN", "9", "触发测试")));

        listener.push("TEST_RUN", "9", "p1", "测试 #9 FAILED", false);

        String text = adapter.pushes().get(0).text();
        assertTrue(text.contains("❌ 触发测试 已失败"), text);
        assertTrue(text.contains("测试 #9 FAILED"), text);
    }

    @Test
    void skipsWhenNoWatch() {
        when(watchService.take("BUILD", "99")).thenReturn(Optional.empty());

        listener.push("BUILD", "99", "p1", "构建 #99 SUCCESS", true);

        assertTrue(adapter.pushes().isEmpty());
    }

    @Test
    void sendFailureIsLoggedNotRethrown() {
        when(watchService.take("BUILD", "12"))
                .thenReturn(Optional.of(watch("BUILD", "12", "触发构建")));
        BotJobWatchListener boom = listenerWith(new BoomAdapter());

        boom.push("BUILD", "12", "p1", "构建 #12 SUCCESS", true); // 不抛：失败只记日志不补推

        assertTrue(adapter.pushes().isEmpty());
        verify(watchService).take("BUILD", "12");
    }

    @Test
    void ignoresUnrelatedEvents() {
        listener.onJobCompleted(SimpleDomainEvent.of("session.completed", "p1", null, "a",
                "会话完成", "SESSION", "s-1", true));
        listener.onJobCompleted(SimpleDomainEvent.of("build.completed", "p1", null, "a",
                "类型不匹配", "DEPLOYMENT", "5", true));
        listener.onJobCompleted(SimpleDomainEvent.of("build.completed", "p1", null, "a",
                "无实体", "BUILD", null, true));
        // 过滤是同步的：不提交线程池，立即断言无副作用
        verify(watchService, never()).take(anyString(), any());
        assertTrue(adapter.pushes().isEmpty());
    }

    @Test
    void deploymentEventPushesViaVirtualThread() throws InterruptedException {
        when(watchService.take("DEPLOYMENT", "5"))
                .thenReturn(Optional.of(watch("DEPLOYMENT", "5", "触发部署")));

        listener.onDeploymentCompleted(new DeploymentCompletedEvent(5L, "p1", "node-1", false));

        long deadline = System.currentTimeMillis() + 5_000;
        while (adapter.pushes().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, adapter.pushes().size());
        String text = adapter.pushes().get(0).text();
        assertTrue(text.contains("❌ 触发部署 已失败"), text);
        assertTrue(text.contains("部署 #5 FAILED"), text);
    }

    /** send 抛错的适配器（平台侧故障）。 */
    private static final class BoomAdapter extends FakeBotAdapter {
        @Override
        public void send(String externalChatId, String text) {
            throw new IllegalStateException("boom");
        }
    }
}
