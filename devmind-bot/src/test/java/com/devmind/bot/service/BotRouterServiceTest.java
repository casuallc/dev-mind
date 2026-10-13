package com.devmind.bot.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.devmind.bot.config.BotProperties;
import com.devmind.bot.model.BotBindingEntity;
import com.devmind.bot.repo.BotBindingRepository;
import com.devmind.bot.spi.BotAdapter;
import com.devmind.bot.spi.BotMessage;
import com.devmind.bot.spi.BotMessageHandler;
import com.devmind.build.dto.BuildView;
import com.devmind.build.service.BuildService;
import com.devmind.chat.dto.ChatView;
import com.devmind.chat.dto.CreateChatRequest;
import com.devmind.chat.service.ChatManagerService;
import com.devmind.common.agent.SessionEvent;
import com.devmind.common.dto.PageView;
import com.devmind.deploy.service.DeploymentService;
import com.devmind.project.ProjectService;
import com.devmind.project.dto.ProjectView;
import com.devmind.session.service.SessionManagerService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * BotRouterService 路由矩阵（CAP-75 FR-08/09）：指令路由、绑定懒创建、问答回推、
 * 去重、群聊免打扰、失败文案。外部服务全 mock，回复由测试适配器捕获。
 */
class BotRouterServiceTest {

    /** 捕获回复的测试适配器（code=fake）。 */
    static class CapturingAdapter implements BotAdapter {
        final BlockingQueue<String> replies = new LinkedBlockingQueue<>();

        @Override
        public String code() { return "fake"; }

        @Override
        public void start(BotMessageHandler handler) { }

        @Override
        public void stop() { }

        @Override
        public void reply(BotMessage origin, String text) {
            replies.add(text);
        }
    }

    private final BotBindingRepository bindingRepo = mock(BotBindingRepository.class);
    private final ChatManagerService chatManager = mock(ChatManagerService.class);
    private final SessionManagerService sessionManager = mock(SessionManagerService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final BuildService buildService = mock(BuildService.class);
    private final DeploymentService deploymentService = mock(DeploymentService.class);
    private final CapturingAdapter adapter = new CapturingAdapter();
    private final Map<String, BotBindingEntity> bindings = new HashMap<>();

    private BotRouterService router;

    @BeforeEach
    void setUp() {
        Mockito.reset(bindingRepo);
        bindings.clear();
        adapter.replies.clear();
        when(bindingRepo.findByChannelAndExternalChatId(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(
                        bindings.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(bindingRepo.save(any())).thenAnswer(inv -> {
            BotBindingEntity b = inv.getArgument(0);
            bindings.put(b.getChannel() + "|" + b.getExternalChatId(), b);
            return b;
        });

        BotProperties props = new BotProperties();
        props.setQaTimeoutSeconds(10);
        AtomicReference<BotAdapterRegistry> registryRef = new AtomicReference<>();
        @SuppressWarnings("unchecked")
        ObjectProvider<BotAdapterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenAnswer(inv -> registryRef.get());

        router = new BotRouterService(bindingRepo, chatManager, sessionManager, projectService,
                buildService, deploymentService, props, provider);
        registryRef.set(new BotAdapterRegistry(List.of(adapter), router));
    }

    private static BotMessage msg(String text, String eventId) {
        return new BotMessage("fake", "chat-1", BotMessage.CHAT_TYPE_P2P, "u-1",
                text, false, "m-" + eventId, eventId);
    }

    private String awaitReply() throws InterruptedException {
        String r = adapter.replies.poll(15, TimeUnit.SECONDS);
        if (r == null) {
            throw new AssertionError("等待回复超时");
        }
        return r;
    }

    @Test
    void helpCommand() throws Exception {
        router.onMessage(msg("/help", "e-help"));
        assertTrue(awaitReply().contains("可用指令"));
    }

    @Test
    void unknownCommand() throws Exception {
        router.onMessage(msg("/nope", "e-nope"));
        assertTrue(awaitReply().contains("未知指令"));
    }

    @Test
    void duplicateEventDropped() throws Exception {
        router.onMessage(msg("/help", "e-dup"));
        router.onMessage(msg("/help", "e-dup"));
        awaitReply();
        assertTrue(adapter.replies.poll(2, TimeUnit.SECONDS) == null, "重复事件不应再回");
    }

    @Test
    void groupChatIgnoredUnlessMentioned() throws Exception {
        router.onMessage(new BotMessage("fake", "g-1", BotMessage.CHAT_TYPE_GROUP, "u-1",
                "/help", false, "m-g1", "e-g1"));
        assertTrue(adapter.replies.poll(2, TimeUnit.SECONDS) == null, "群聊未 @不应响应");
        router.onMessage(new BotMessage("fake", "g-1", BotMessage.CHAT_TYPE_GROUP, "u-1",
                "/help", true, "m-g2", "e-g2"));
        assertTrue(awaitReply().contains("可用指令"));
    }

    @Test
    void bindByIdThenBuild() throws Exception {
        ProjectView p = mock(ProjectView.class);
        when(p.id()).thenReturn("p1");
        when(p.name()).thenReturn("演示项目");
        when(projectService.get("p1")).thenReturn(p);

        router.onMessage(msg("/bind p1", "e-bind"));
        assertTrue(awaitReply().contains("已绑定项目：演示项目（p1）"));
        assertTrue("p1".equals(bindings.get("fake|chat-1").getProjectId()));

        BuildView b = new BuildView(7L, "p1", null, "abcdef123456", "main", null, null,
                "SUCCESS", 0, null, null, null, Instant.now(), null);
        when(buildService.history("p1", null)).thenReturn(List.of(b));
        router.onMessage(msg("/build", "e-build"));
        String reply = awaitReply();
        assertTrue(reply.contains("#7") && reply.contains("SUCCESS") && reply.contains("abcdef1"));
    }

    @Test
    void buildRequiresBinding() throws Exception {
        router.onMessage(msg("/build", "e-nobind"));
        assertTrue(awaitReply().contains("请先"));
    }

    @Test
    void deployEmpty() throws Exception {
        ProjectView p = mock(ProjectView.class);
        when(p.id()).thenReturn("p1");
        when(p.name()).thenReturn("演示项目");
        when(projectService.get("p1")).thenReturn(p);
        router.onMessage(msg("/bind p1", "e-bind2"));
        awaitReply();
        when(deploymentService.history("p1", null, 1, 5))
                .thenReturn(new PageView<>(List.of(), 0, 1, 5));
        router.onMessage(msg("/deploy", "e-deploy"));
        assertTrue(awaitReply().contains("暂无部署记录"));
    }

    @Test
    void qaCreatesModelChatAndRepliesAnswer() throws Exception {
        ChatView view = mock(ChatView.class);
        when(view.id()).thenReturn("c1");
        when(chatManager.create(any(CreateChatRequest.class))).thenReturn(view);
        AtomicReference<Consumer<SessionEvent>> consumerRef = new AtomicReference<>();
        doAnswer(inv -> {
            consumerRef.set(inv.getArgument(1));
            return List.of();
        }).when(chatManager).subscribe(eq("c1"), any());

        router.onMessage(msg("什么是分布式锁？", "e-qa"));

        // 等路由线程订阅上，再喂事件（assistant 正文 + result 收口）
        Consumer<SessionEvent> consumer = awaitConsumer(consumerRef);
        consumer.accept(SessionEvent.of(1, "assistant", "分布式锁是对共享资源的互斥控制。", "stdout"));
        consumer.accept(SessionEvent.of(2, "result", "摘要", "stdout", Map.of("isError", false)));

        String reply = awaitReply();
        assertTrue(reply.contains("分布式锁是对共享资源的互斥控制。"));
        assertTrue("c1".equals(bindings.get("fake|chat-1").getChatSessionId()));
    }

    @Test
    void qaErrorResultReported() throws Exception {
        ChatView view = mock(ChatView.class);
        when(view.id()).thenReturn("c1");
        when(chatManager.create(any(CreateChatRequest.class))).thenReturn(view);
        AtomicReference<Consumer<SessionEvent>> consumerRef = new AtomicReference<>();
        doAnswer(inv -> {
            consumerRef.set(inv.getArgument(1));
            return List.of();
        }).when(chatManager).subscribe(eq("c1"), any());

        router.onMessage(msg("问个问题", "e-qaerr"));
        Consumer<SessionEvent> consumer = awaitConsumer(consumerRef);
        consumer.accept(SessionEvent.of(1, "result", "上游 401", "stdout", Map.of("isError", true)));

        assertTrue(awaitReply().contains("回答失败"));
    }

    private static Consumer<SessionEvent> awaitConsumer(AtomicReference<Consumer<SessionEvent>> ref)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (ref.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        if (ref.get() == null) {
            throw new AssertionError("路由线程未订阅问答事件");
        }
        return ref.get();
    }
}
