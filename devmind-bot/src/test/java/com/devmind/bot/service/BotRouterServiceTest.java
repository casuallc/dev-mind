package com.devmind.bot.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.devmind.bot.config.BotProperties;
import com.devmind.bot.model.BotBindingEntity;
import com.devmind.bot.model.BotInteractionEntity;
import com.devmind.bot.repo.BotBindingRepository;
import com.devmind.bot.repo.BotInteractionRepository;
import com.devmind.bot.spi.BotAdapter;
import com.devmind.bot.spi.BotChannelConfigProvider;
import com.devmind.bot.spi.BotMessage;
import com.devmind.bot.spi.BotMessageHandler;
import com.devmind.build.dto.BuildView;
import com.devmind.build.service.BuildService;
import com.devmind.chat.dto.ChatView;
import com.devmind.chat.dto.CreateChatRequest;
import com.devmind.chat.service.ChatManagerService;
import com.devmind.common.agent.SessionEvent;
import com.devmind.common.attachment.AttachmentContentResolver;
import com.devmind.common.dto.PageView;
import com.devmind.deploy.service.DeploymentService;
import com.devmind.project.ProjectService;
import com.devmind.project.RequirementService;
import com.devmind.project.WorkItemService;
import com.devmind.project.dto.ProjectView;
import com.devmind.project.dto.RequirementView;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.RequirementEntity;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.SessionView;
import com.devmind.session.service.SessionManagerService;
import java.time.Instant;
import java.util.ArrayList;
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

    /** 捕获回复的测试适配器（默认 code=fake）；failOnReply 非空时 reply 抛错（模拟回推失败）。 */
    static class CapturingAdapter implements BotAdapter {
        final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        /** FR-16 出站图片捕获：fileName|contentType|bytes.length */
        final BlockingQueue<String> images = new LinkedBlockingQueue<>();
        volatile RuntimeException failOnReply;
        private final String code;

        CapturingAdapter() { this("fake"); }

        CapturingAdapter(String code) { this.code = code; }

        @Override
        public String code() { return code; }

        @Override
        public void start(BotMessageHandler handler) { }

        @Override
        public void stop() { }

        @Override
        public void reply(BotMessage origin, String text) {
            if (failOnReply != null) {
                throw failOnReply;
            }
            replies.add(text);
        }

        @Override
        public void replyImage(BotMessage origin, String fileName, String contentType, byte[] bytes) {
            images.add(fileName + "|" + contentType + "|" + bytes.length);
        }

        /** FR-17 入站图片：非 null 时 downloadImage 返回该字节（null=平台不支持收图）。 */
        volatile byte[] downloadResult;

        @Override
        public byte[] downloadImage(String imageKey) {
            return downloadResult;
        }
    }

    private final BotBindingRepository bindingRepo = mock(BotBindingRepository.class);
    private final BotInteractionRepository interactionRepo = mock(BotInteractionRepository.class);
    private final ChatManagerService chatManager = mock(ChatManagerService.class);
    private final SessionManagerService sessionManager = mock(SessionManagerService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final RequirementService requirementService = mock(RequirementService.class);
    private final WorkItemService workItemService = mock(WorkItemService.class);
    private final BuildService buildService = mock(BuildService.class);
    private final DeploymentService deploymentService = mock(DeploymentService.class);
    private final com.devmind.attachment.service.AttachmentService attachmentService =
            mock(com.devmind.attachment.service.AttachmentService.class);
    private final CapturingAdapter adapter = new CapturingAdapter();
    private final Map<String, BotBindingEntity> bindings = new HashMap<>();
    private final List<BotInteractionEntity> interactions = new ArrayList<>();
    /** FR-16：attachment 解析 SPI（null=模块未装配）；测试按需 set */
    private final AtomicReference<AttachmentContentResolver> attachmentResolverRef =
            new AtomicReference<>();

    private BotRouterService router;

    @BeforeEach
    void setUp() {
        Mockito.reset(bindingRepo, interactionRepo);
        bindings.clear();
        interactions.clear();
        adapter.replies.clear();
        adapter.images.clear();
        adapter.failOnReply = null;
        attachmentResolverRef.set(null);
        adapter.downloadResult = null;
        java.util.concurrent.atomic.AtomicInteger uploadSeq = new java.util.concurrent.atomic.AtomicInteger();
        when(attachmentService.upload(any(), anyString(), anyString())).thenAnswer(inv -> {
            String id = String.format("%032x", uploadSeq.incrementAndGet());
            org.springframework.web.multipart.MultipartFile f = inv.getArgument(0);
            return new com.devmind.attachment.dto.AttachmentView(id, f.getOriginalFilename(),
                    f.getContentType(), f.getSize(), inv.getArgument(1), true,
                    "/api/attachments/" + id + "/raw", inv.getArgument(2), null, null,
                    "u-1", Instant.now());
        });
        when(bindingRepo.findByChannelAndExternalChatId(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(
                        bindings.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(bindingRepo.save(any())).thenAnswer(inv -> {
            BotBindingEntity b = inv.getArgument(0);
            bindings.put(b.getChannel() + "|" + b.getExternalChatId(), b);
            return b;
        });
        when(interactionRepo.save(any())).thenAnswer(inv -> {
            BotInteractionEntity r = inv.getArgument(0);
            synchronized (interactions) {
                interactions.add(r);
            }
            return r;
        });

        BotProperties props = new BotProperties();
        props.setQaTimeoutSeconds(10);
        AtomicReference<BotAdapterRegistry> registryRef = new AtomicReference<>();
        @SuppressWarnings("unchecked")
        ObjectProvider<BotAdapterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenAnswer(inv -> registryRef.get());

        router = new BotRouterService(bindingRepo, interactionRepo, chatManager, sessionManager,
                projectService, requirementService, workItemService, buildService,
                deploymentService, props, provider, attachmentProvider(), attachmentService,
                List.of());
        registryRef.set(new BotAdapterRegistry(List.of(adapter), router));
    }

    /** FR-16：attachmentResolverProvider 桩——getIfAvailable 读 attachmentResolverRef。 */
    @SuppressWarnings("unchecked")
    private ObjectProvider<AttachmentContentResolver> attachmentProvider() {
        ObjectProvider<AttachmentContentResolver> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenAnswer(inv -> attachmentResolverRef.get());
        return p;
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

    @Test
    void qaDeletedChatSelfHeals() throws Exception {
        // 绑定上挂着已被平台删除的问答：续问撞 NOT_FOUND → 丢弃引用原地重建，本轮消息不丢
        BotBindingEntity b = new BotBindingEntity();
        b.setChannel("fake");
        b.setExternalChatId("chat-1");
        b.setChatSessionId("gone");
        bindings.put("fake|chat-1", b);
        when(chatManager.subscribe(eq("gone"), any())).thenThrow(
                new com.devmind.common.exception.DevMindException(
                        com.devmind.common.exception.ErrorCode.NOT_FOUND, "问答不存在: gone"));

        ChatView view = mock(ChatView.class);
        when(view.id()).thenReturn("c2");
        when(chatManager.create(any(CreateChatRequest.class))).thenReturn(view);
        AtomicReference<Consumer<SessionEvent>> consumerRef = new AtomicReference<>();
        doAnswer(inv -> {
            consumerRef.set(inv.getArgument(1));
            return List.of();
        }).when(chatManager).subscribe(eq("c2"), any());

        router.onMessage(msg("接着问", "e-heal"));
        Consumer<SessionEvent> consumer = awaitConsumer(consumerRef);
        consumer.accept(SessionEvent.of(1, "assistant", "新问答的回答", "stdout"));
        consumer.accept(SessionEvent.of(2, "result", "摘要", "stdout", Map.of("isError", false)));

        assertTrue(awaitReply().contains("新问答的回答"));
        assertTrue("c2".equals(bindings.get("fake|chat-1").getChatSessionId()));
    }

    // ---------------- CAP-75 FR-15 研发主线指令 ----------------

    private static RequirementView reqView(String id, long seq, String title, String status, String desc) {
        return new RequirementView(id, "p1", seq, "REQ-" + seq, title, desc, status,
                "FEATURE", null, null, "LOCAL", null, null, null, null, null, null, null,
                null, null, null, null, null, "admin", null, null, null, null, null);
    }

    private void bindP1() throws Exception {
        ProjectView p = mock(ProjectView.class);
        when(p.id()).thenReturn("p1");
        when(p.name()).thenReturn("演示项目");
        when(projectService.get("p1")).thenReturn(p);
        router.onMessage(msg("/bind p1", "e-bindp1-" + System.nanoTime()));
        awaitReply();
    }

    @Test
    void reqsListsOpenRequirements() throws Exception {
        bindP1();
        when(requirementService.list(eq("p1"), eq("OPEN"), isNull(), isNull(), isNull(),
                eq("seq"), eq("desc"), eq(0), eq(10)))
                .thenReturn(new PageView<>(List.of(
                        reqView("r1", 3, "登录加验证码", "IN_PROGRESS", null),
                        reqView("r2", 2, "导出 CSV", "DESIGNING", null)), 2, 0, 10));

        router.onMessage(msg("/reqs", "e-rq-1"));
        String reply = awaitReply();
        assertTrue(reply.contains("REQ-3") && reply.contains("登录加验证码"), reply);
        assertTrue(reply.contains("REQ-2") && reply.contains("导出 CSV"), reply);
        assertTrue(reply.contains("共 2"), reply);
    }

    @Test
    void reqDetailResolvesSeqAndShowsWorkItems() throws Exception {
        bindP1();
        RequirementEntity e = new RequirementEntity();
        e.setId("r1");
        e.setProjectId("p1");
        e.setSeq(3L);
        when(requirementService.findByProjectSeq("p1", 3L)).thenReturn(Optional.of(e));
        when(requirementService.get("p1", "r1"))
                .thenReturn(reqView("r1", 3, "登录加验证码", "IN_PROGRESS", "支持图形验证码与滑块"));
        when(workItemService.list("p1", "r1")).thenReturn(List.of(
                new WorkItemView("w1", "p1", "r1", null, 1L, "WI-1", "BACKEND",
                        "登录接口", null, "DOING", null, null, "admin", null, null)));

        router.onMessage(msg("/req req-3", "e-rq-2"));
        String reply = awaitReply();
        assertTrue(reply.contains("REQ-3 登录加验证码"), reply);
        assertTrue(reply.contains("滑块"), reply);
        assertTrue(reply.contains("WI-1 [DOING] 登录接口"), reply);
    }

    @Test
    void reqDetailAcceptsPlainSeqNumber() throws Exception {
        bindP1();
        RequirementEntity e = new RequirementEntity();
        e.setId("r1");
        e.setProjectId("p1");
        e.setSeq(3L);
        when(requirementService.findByProjectSeq("p1", 3L)).thenReturn(Optional.of(e));
        when(requirementService.get("p1", "r1"))
                .thenReturn(reqView("r1", 3, "登录加验证码", "IN_PROGRESS", null));

        // 纯数字形态：前缀 REQ 整体可选（曾只有短横可选 → 落 id 直查分支）
        router.onMessage(msg("/req 3", "e-rq-plain"));
        String reply = awaitReply();
        assertTrue(reply.contains("REQ-3 登录加验证码"), reply);
    }

    @Test
    void devCreatesSessionLinkedToRequirement() throws Exception {
        bindP1();
        RequirementEntity e = new RequirementEntity();
        e.setId("r1");
        e.setProjectId("p1");
        e.setSeq(3L);
        when(requirementService.findByProjectSeq("p1", 3L)).thenReturn(Optional.of(e));
        when(requirementService.get("p1", "r1"))
                .thenReturn(reqView("r1", 3, "登录加验证码", "IN_PROGRESS", "支持图形验证码"));
        AtomicReference<CreateSessionRequest> captured = new AtomicReference<>();
        SessionView sv = mock(SessionView.class);
        when(sv.id()).thenReturn("s-1");
        when(sv.status()).thenReturn("QUEUED");
        when(sessionManager.create(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return sv;
        });

        router.onMessage(msg("/dev REQ-3 先做图形验证码", "e-rq-3"));
        String reply = awaitReply();
        assertTrue(reply.contains("s-1") && reply.contains("REQ-3"), reply);
        CreateSessionRequest req = captured.get();
        assertTrue("r1".equals(req.requirementId()), String.valueOf(req.requirementId()));
        assertTrue(req.taskSpec().contains("【REQ-3】登录加验证码"), req.taskSpec());
        assertTrue(req.taskSpec().contains("支持图形验证码"), req.taskSpec());
        assertTrue(req.taskSpec().contains("补充说明：先做图形验证码"), req.taskSpec());
    }

    // ---------------- CAP-75 FR-16 出站图片 ----------------

    @Test
    void reqDetailRepliesDescriptionImages() throws Exception {
        bindP1();
        String a1 = "0123456789abcdef0123456789abcdef";
        String a2 = "fedcba9876543210fedcba9876543210";
        RequirementEntity e = new RequirementEntity();
        e.setId("r1");
        e.setProjectId("p1");
        e.setSeq(3L);
        when(requirementService.findByProjectSeq("p1", 3L)).thenReturn(Optional.of(e));
        when(requirementService.get("p1", "r1")).thenReturn(reqView("r1", 3, "登录加验证码",
                "IN_PROGRESS", "流程见 ![流程图](/api/attachments/" + a1 + "/raw)"
                        + " 与 ![b](https://x/api/attachments/" + a2 + "/raw?access_token=t)"));
        AttachmentContentResolver resolver = mock(AttachmentContentResolver.class);
        when(resolver.resolve(a1)).thenReturn(Optional.of(
                new AttachmentContentResolver.ResolvedAttachment(
                        a1, "image/png", new byte[]{1, 2, 3}, "流程图.png")));
        when(resolver.resolve(a2)).thenReturn(Optional.of(
                new AttachmentContentResolver.ResolvedAttachment(
                        a2, "image/jpeg", new byte[]{4, 5}, "b.jpg")));
        attachmentResolverRef.set(resolver);

        router.onMessage(msg("/req 3", "e-img-1"));
        String reply = awaitReply();
        assertTrue(reply.contains("附 2 张需求截图"), reply);
        String img1 = adapter.images.poll(15, TimeUnit.SECONDS);
        String img2 = adapter.images.poll(15, TimeUnit.SECONDS);
        assertTrue(img1 != null && img1.startsWith("流程图.png|image/png|3"), String.valueOf(img1));
        assertTrue(img2 != null && img2.startsWith("b.jpg|image/jpeg|2"), String.valueOf(img2));
    }

    @Test
    void reqDetailSkipsNonImageAndMissingAttachments() throws Exception {
        bindP1();
        String a1 = "0123456789abcdef0123456789abcdef";
        String a2 = "fedcba9876543210fedcba9876543210";
        RequirementEntity e = new RequirementEntity();
        e.setId("r1");
        e.setProjectId("p1");
        e.setSeq(3L);
        when(requirementService.findByProjectSeq("p1", 3L)).thenReturn(Optional.of(e));
        when(requirementService.get("p1", "r1")).thenReturn(reqView("r1", 3, "登录加验证码",
                "IN_PROGRESS", "附件 ![spec](/api/attachments/" + a1 + "/raw)"
                        + " ![gone](/api/attachments/" + a2 + "/raw)"));
        AttachmentContentResolver resolver = mock(AttachmentContentResolver.class);
        // pdf 不是图片跳过；a2 解析不到跳过——文本照常回，不带「附 N 张」
        when(resolver.resolve(a1)).thenReturn(Optional.of(
                new AttachmentContentResolver.ResolvedAttachment(
                        a1, "application/pdf", new byte[]{1}, "spec.pdf")));
        when(resolver.resolve(a2)).thenReturn(Optional.empty());
        attachmentResolverRef.set(resolver);

        router.onMessage(msg("/req 3", "e-img-2"));
        String reply = awaitReply();
        assertTrue(reply.contains("REQ-3 登录加验证码"), reply);
        assertTrue(!reply.contains("需求截图"), reply);
        assertTrue(adapter.images.poll(2, TimeUnit.SECONDS) == null, "非图片附件不应回推图片");
    }

    // ---------------- CAP-75 FR-17 入站图片 ----------------

    /** PNG 魔数头（sniffImageType 只认头 4-12 字节）。 */
    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private static BotMessage imageMsg(String channel, String chatId, String sender,
                                       String imageKey, String eventId) {
        return new BotMessage(channel, chatId, BotMessage.CHAT_TYPE_P2P, sender,
                null, false, "m-" + eventId, eventId, imageKey);
    }

    @Test
    void imageReceiptThenSessionConsumesPending() throws Exception {
        bindP1();
        adapter.downloadResult = PNG_BYTES;
        router.onMessage(imageMsg("fake", "chat-1", "u-1", "img_v3_a", "e-in-1"));
        String receipt = awaitReply();
        assertTrue(receipt.contains("已收到截图") && receipt.contains("暂存 1 张"), receipt);

        // 连发第二张 → 累计 2 张
        router.onMessage(imageMsg("fake", "chat-1", "u-1", "img_v3_b", "e-in-2"));
        assertTrue(awaitReply().contains("暂存 2 张"));

        AtomicReference<CreateSessionRequest> captured = new AtomicReference<>();
        SessionView sv = mock(SessionView.class);
        when(sv.id()).thenReturn("s-9");
        when(sv.status()).thenReturn("QUEUED");
        when(sessionManager.create(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return sv;
        });
        router.onMessage(msg("/session 修登录页样式错位", "e-in-3"));
        String reply = awaitReply();
        assertTrue(reply.contains("已附 2 张截图"), reply);
        assertTrue(captured.get().attachmentIds() != null
                        && captured.get().attachmentIds().size() == 2,
                String.valueOf(captured.get().attachmentIds()));
    }

    @Test
    void imageReceiptThenDevConsumesPending() throws Exception {
        bindP1();
        RequirementEntity e = new RequirementEntity();
        e.setId("r1");
        e.setProjectId("p1");
        e.setSeq(3L);
        when(requirementService.findByProjectSeq("p1", 3L)).thenReturn(Optional.of(e));
        when(requirementService.get("p1", "r1"))
                .thenReturn(reqView("r1", 3, "登录加验证码", "IN_PROGRESS", null));
        adapter.downloadResult = PNG_BYTES;
        router.onMessage(imageMsg("fake", "chat-1", "u-1", "img_v3_c", "e-in-4"));
        awaitReply();

        AtomicReference<CreateSessionRequest> captured = new AtomicReference<>();
        SessionView sv = mock(SessionView.class);
        when(sv.id()).thenReturn("s-10");
        when(sv.status()).thenReturn("QUEUED");
        when(sessionManager.create(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return sv;
        });
        router.onMessage(msg("/dev REQ-3 样式见截图", "e-in-5"));
        String reply = awaitReply();
        assertTrue(reply.contains("已附 1 张截图"), reply);
        assertTrue(captured.get().attachmentIds() != null
                && captured.get().attachmentIds().size() == 1, "附件未挂上");
    }

    @Test
    void imageUnsupportedPlatformAndNonImageBytes() throws Exception {
        // 平台不支持收图（downloadImage 默认 null）
        router.onMessage(imageMsg("fake", "chat-1", "u-1", "img_x", "e-in-6"));
        assertTrue(awaitReply().contains("当前平台不支持发送图片给机器人"));

        // 下载到非图片字节 → 提示重发（魔数嗅探，不信平台声明）
        adapter.downloadResult = "not an image".getBytes();
        router.onMessage(imageMsg("fake", "chat-1", "u-1", "img_y", "e-in-7"));
        assertTrue(awaitReply().contains("不是可识别的图片"));
    }

    @Test
    void imageGatedWhenUnboundAndReceiptWhenBound() throws Exception {
        setupFeishuRouter();
        feishuAdapter.downloadResult = PNG_BYTES;
        // 未绑定个人身份：图片同敏感操作门禁，落 REJECTED（command=IMAGE）
        feishuRouter.onMessage(imageMsg("feishu", "oc-1", "ou_alice", "img_v3_g", "e-in-8"));
        assertTrue(awaitFeishuReply().contains("需要先绑定个人身份"));
        List<BotInteractionEntity> rows = awaitInteractions(1);
        assertTrue("IMAGE".equals(rows.get(0).getCommand()), rows.get(0).getCommand());
        assertTrue(BotInteractionEntity.STATUS_REJECTED.equals(rows.get(0).getStatus()),
                rows.get(0).getStatus());

        // 绑定后正常收图
        identityConfig.mappings.put("ou_alice", "alice");
        feishuRouter.onMessage(imageMsg("feishu", "oc-1", "ou_alice", "img_v3_h", "e-in-9"));
        assertTrue(awaitFeishuReply().contains("已收到截图"));
    }

    // ---------------- CAP-75 FR-18 会话控制 /status /stop ----------------

    private static SessionView sessView(String id, String status, String taskSpec, String summary) {
        SessionView s = mock(SessionView.class);
        when(s.id()).thenReturn(id);
        when(s.status()).thenReturn(status);
        when(s.taskSpec()).thenReturn(taskSpec);
        when(s.summary()).thenReturn(summary);
        when(s.createdAt()).thenReturn(Instant.now());
        return s;
    }

    @Test
    void statusListsRecentSessions() throws Exception {
        bindP1();
        // sessView 内部也是 when() 打桩，必须先建视图再 stub list（否则 UnfinishedStubbing）
        SessionView s1 = sessView("s-abcdef123456", "RUNNING", "修复登录页样式错位问题", "已改完 css");
        SessionView s2 = sessView("s-000000999999", "FINISHED", "导出 CSV", null);
        when(sessionManager.list(isNull(), eq("p1"), isNull(), isNull())).thenReturn(List.of(s1, s2));
        router.onMessage(msg("/status", "e-st-1"));
        String reply = awaitReply();
        assertTrue(reply.contains("s-abcdef") && reply.contains("RUNNING"), reply);
        assertTrue(reply.contains("修复登录页样式错位问题"), reply);
        assertTrue(reply.contains("进展：已改完 css"), reply);
        assertTrue(reply.contains("s-000000") && reply.contains("FINISHED"), reply);
    }

    @Test
    void stopWithoutArgKillsLatestRunning() throws Exception {
        bindP1();
        SessionView running = sessView("s-run12345678", "RUNNING", "做导出", null);
        SessionView killed = sessView("s-run12345678", "TERMINATED", "做导出", null);
        when(sessionManager.list(eq("RUNNING"), eq("p1"), isNull(), isNull()))
                .thenReturn(List.of(running));
        when(sessionManager.kill("s-run12345678")).thenReturn(killed);
        router.onMessage(msg("/stop", "e-st-2"));
        String reply = awaitReply();
        assertTrue(reply.contains("已停止会话 s-run123") && reply.contains("TERMINATED"), reply);
    }

    @Test
    void stopByUniquePrefix() throws Exception {
        bindP1();
        SessionView a = sessView("s-aaaa11112222", "RUNNING", "任务A", null);
        SessionView b = sessView("s-bbbb33334444", "FINISHED", "任务B", null);
        SessionView killed = sessView("s-aaaa11112222", "TERMINATED", "任务A", null);
        when(sessionManager.list(isNull(), eq("p1"), isNull(), isNull())).thenReturn(List.of(a, b));
        when(sessionManager.kill("s-aaaa11112222")).thenReturn(killed);
        router.onMessage(msg("/stop s-aaaa", "e-st-3"));
        assertTrue(awaitReply().contains("已停止会话 s-aaaa11"));
    }

    @Test
    void stopFinishedSessionShortCircuits() throws Exception {
        bindP1();
        SessionView dead = sessView("s-dead11112222", "FINISHED", "任务X", null);
        when(sessionManager.list(isNull(), eq("p1"), isNull(), isNull()))
                .thenReturn(List.of(dead));
        router.onMessage(msg("/stop s-dead", "e-st-4"));
        assertTrue(awaitReply().contains("无需停止"));
    }

    // ---------------- CAP-75 FR-11/12 个人身份与门禁 ----------------

    /** 支持身份映射的 channel 配置桩（仿飞书：senderId → 平台用户名）。 */
    static class IdentityChannelConfig implements BotChannelConfigProvider {
        final Map<String, String> mappings = new HashMap<>();

        @Override
        public String channel() { return "feishu"; }

        @Override
        public String defaultActor() { return "bot-admin"; }

        @Override
        public boolean supportsIdentity() { return true; }

        @Override
        public Optional<String> resolveActor(String senderId) {
            return Optional.ofNullable(mappings.get(senderId));
        }
    }

    private IdentityChannelConfig identityConfig;
    private CapturingAdapter feishuAdapter;
    private BotRouterService feishuRouter;

    /** 搭一个带身份映射 channel 的独立路由（共享 mock 仓储与服务）。 */
    private void setupFeishuRouter() {
        identityConfig = new IdentityChannelConfig();
        feishuAdapter = new CapturingAdapter("feishu");
        BotProperties props = new BotProperties();
        props.setQaTimeoutSeconds(10);
        AtomicReference<BotAdapterRegistry> registryRef = new AtomicReference<>();
        @SuppressWarnings("unchecked")
        ObjectProvider<BotAdapterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenAnswer(inv -> registryRef.get());
        feishuRouter = new BotRouterService(bindingRepo, interactionRepo, chatManager,
                sessionManager, projectService, requirementService, workItemService,
                buildService, deploymentService, props, provider, attachmentProvider(),
                attachmentService, List.of(identityConfig));
        registryRef.set(new BotAdapterRegistry(List.of(feishuAdapter), feishuRouter));
    }

    private static BotMessage feishuMsg(String sender, String text, String eventId) {
        return new BotMessage("feishu", "oc-1", BotMessage.CHAT_TYPE_P2P, sender,
                text, false, "m-" + eventId, eventId);
    }

    private String awaitFeishuReply() throws InterruptedException {
        String r = feishuAdapter.replies.poll(15, TimeUnit.SECONDS);
        if (r == null) {
            throw new AssertionError("等待回复超时");
        }
        return r;
    }

    @Test
    void bindmeReturnsSenderIdentityCode() throws Exception {
        setupFeishuRouter();
        feishuRouter.onMessage(feishuMsg("ou_alice", "/bindme", "e-bm-1"));
        String reply = awaitFeishuReply();
        assertTrue(reply.contains("ou_alice"), reply);
        assertTrue(reply.contains("第三方账号"), reply);
    }

    @Test
    void sensitiveOpsRejectedWhenUnbound() throws Exception {
        setupFeishuRouter();
        feishuRouter.onMessage(feishuMsg("ou_alice", "/build", "e-g-1"));
        assertTrue(awaitFeishuReply().contains("需要先绑定个人身份"));
        feishuRouter.onMessage(feishuMsg("ou_alice", "/bind 某项目", "e-g-2"));
        assertTrue(awaitFeishuReply().contains("需要先绑定个人身份"));
        // 放行项：/help /whoami /bindme 不受门禁
        feishuRouter.onMessage(feishuMsg("ou_alice", "/help", "e-g-3"));
        assertTrue(awaitFeishuReply().contains("可用指令"));
        feishuRouter.onMessage(feishuMsg("ou_alice", "/whoami", "e-g-4"));
        String whoamiReply = awaitFeishuReply();
        assertTrue(whoamiReply.contains("bot-admin（机器人默认身份"), whoamiReply);
        // M4 研发主线指令同口径门禁
        feishuRouter.onMessage(feishuMsg("ou_alice", "/reqs", "e-g-5"));
        assertTrue(awaitFeishuReply().contains("需要先绑定个人身份"));
        feishuRouter.onMessage(feishuMsg("ou_alice", "/dev REQ-1", "e-g-6"));
        assertTrue(awaitFeishuReply().contains("需要先绑定个人身份"));
    }

    @Test
    void boundSenderRunsSensitiveOpsAndWhoamiHitsPersonal() throws Exception {
        setupFeishuRouter();
        identityConfig.mappings.put("ou_alice", "alice");
        ProjectView p = mock(ProjectView.class);
        when(p.id()).thenReturn("p1");
        when(p.name()).thenReturn("演示项目");
        when(projectService.get("p1")).thenReturn(p);

        feishuRouter.onMessage(feishuMsg("ou_alice", "/whoami", "e-b-1"));
        assertTrue(awaitFeishuReply().contains("alice（个人绑定"));

        feishuRouter.onMessage(feishuMsg("ou_alice", "/bind p1", "e-b-2"));
        assertTrue(awaitFeishuReply().contains("已绑定项目：演示项目"));

        // 个人身份优先于 binding.actor（四级链第一级）
        bindings.get("feishu|oc-1").setActor("group-admin");
        feishuRouter.onMessage(feishuMsg("ou_alice", "/whoami", "e-b-3"));
        assertTrue(awaitFeishuReply().contains("alice（个人绑定"));
    }

    @Test
    void unboundFallsBackToBindingActor() throws Exception {
        setupFeishuRouter();
        // 先绑定 ou_alice 占住 binding，再预置 binding.actor，换未绑定 sender 提问
        BotBindingEntity b = new BotBindingEntity();
        b.setChannel("feishu");
        b.setExternalChatId("oc-1");
        b.setActor("group-admin");
        bindings.put("feishu|oc-1", b);

        feishuRouter.onMessage(feishuMsg("ou_bob", "/whoami", "e-f-1"));
        String whoamiReply = awaitFeishuReply();
        assertTrue(whoamiReply.contains("group-admin（群绑定身份"), whoamiReply);
    }

    @Test
    void interactionLoggedOkDedupedRejected() throws Exception {
        setupFeishuRouter();
        feishuRouter.onMessage(feishuMsg("ou_alice", "/help", "e-l-1"));
        awaitFeishuReply();
        // 同 eventId 重发 → DEDUPED 落库（无回复）
        feishuRouter.onMessage(feishuMsg("ou_alice", "/help", "e-l-1"));
        // 未绑定发敏感指令 → REJECTED
        feishuRouter.onMessage(feishuMsg("ou_alice", "/build", "e-l-2"));
        awaitFeishuReply();

        List<BotInteractionEntity> rows = awaitInteractions(3);
        BotInteractionEntity ok = rows.stream()
                .filter(r -> BotInteractionEntity.STATUS_OK.equals(r.getStatus())).findFirst()
                .orElseThrow(() -> new AssertionError("缺 OK 行: " + rows.size()));
        BotInteractionEntity dup = rows.stream()
                .filter(r -> BotInteractionEntity.STATUS_DEDUPED.equals(r.getStatus())).findFirst()
                .orElseThrow(() -> new AssertionError("缺 DEDUPED 行"));
        BotInteractionEntity rej = rows.stream()
                .filter(r -> BotInteractionEntity.STATUS_REJECTED.equals(r.getStatus())).findFirst()
                .orElseThrow(() -> new AssertionError("缺 REJECTED 行"));

        assertTrue("help".equals(ok.getCommand()), ok.getCommand());
        assertTrue(ok.getReplyText() != null && ok.getReplyText().contains("可用指令"),
                ok.getReplyText());
        assertTrue("bot-admin".equals(ok.getActor()), ok.getActor());
        assertTrue("ou_alice".equals(ok.getSenderId()), ok.getSenderId());
        assertTrue(ok.getDurationMs() != null, "durationMs 未记录");
        assertTrue("DEDUPED".equals(dup.getCommand()), dup.getCommand());
        assertTrue("build".equals(rej.getCommand()), rej.getCommand());
        assertTrue(rej.getReplyText() != null && rej.getReplyText().contains("需要先绑定个人身份"),
                rej.getReplyText());
    }

    private List<BotInteractionEntity> awaitInteractions(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (interactions) {
                if (interactions.size() >= n) {
                    return new ArrayList<>(interactions);
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("交互日志落库不足: 期望>=" + n + " 实际=" + interactions.size());
    }

    @Test
    void replyFailureMarksInteractionFailed() throws Exception {
        // 回推失败 = 用户视角的失败（群里什么都收不到），行状态必须改落 FAILED 而非留着 OK
        adapter.failOnReply = new IllegalStateException("飞书 code=230001 msg=param invalid");
        router.onMessage(msg("/help", "e-rf-1"));

        List<BotInteractionEntity> rows = awaitInteractions(1);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            BotInteractionEntity r = rows.get(0);
            if (BotInteractionEntity.STATUS_FAILED.equals(r.getStatus())) {
                assertTrue(r.getError() != null && r.getError().contains("回复发送失败")
                        && r.getError().contains("230001"), r.getError());
                assertTrue(r.getReplyText() != null && r.getReplyText().contains("可用指令"),
                        "回复文本应保留（便于排查该发什么）: " + r.getReplyText());
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("回推失败未把交互行改落 FAILED: status=" + rows.get(0).getStatus());
    }
}
