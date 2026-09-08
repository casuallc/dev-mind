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
import com.devmind.common.agent.AgentEventFrame;
import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.ChatContextPreparer;
import com.devmind.common.agent.InputImage;
import com.devmind.common.agent.SessionEvent;
import com.devmind.common.agent.exec.ContextManifest;
import com.devmind.common.agent.runtime.CliEventParser;
import com.devmind.common.agent.runtime.CliProcessLauncher;
import com.devmind.common.agent.runtime.FakeProcessLauncher;
import com.devmind.common.agent.runtime.ProcessHelper;
import com.devmind.common.agent.runtime.RuntimeSettings;
import com.devmind.common.agent.runtime.SessionExecutor;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.common.notification.NotificationEvent;
import com.devmind.notification.NotificationPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-30 ChatManagerService 单测（无 Spring 上下文，fake executor 需本机有 node）：
 * repository 用 JDK 动态代理内存 fake，事件落库跑真实 ChatEventSaver（flush 周期 50ms）。
 * CAP-34 起服务端零执行——测试内嵌 FakeRunnerConnector 模拟 runner 节点（进程内拉起
 * fake-agent.js，stdout 经 CliEventParser 解析后走 onRemoteEvent/onRemoteExit 回传），
 * 覆盖：创建（标题/runner 沙箱）→ 授权 → 多轮输入 → finish 优雅结束（DONE + 沙箱清理）
 * → 事件落库 → 删除清记录；无可用节点 409、"local" 保留值 400。
 */
class ChatManagerServiceTest {

    private static final String NODE = "fake-node";

    @TempDir
    Path sandboxRoot;

    private FakeChatRepo chats;
    private FakeEventRepo events;
    private ChatEventSaver saver;
    private ChatManagerService service;
    private FakeRunnerConnector runner;

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
                case "findByAgentNodeIdAndStatusIn" -> store.values().stream()
                        .filter(e -> args[0].equals(e.getAgentNodeId()))
                        .filter(e -> ((java.util.Collection<?>) args[1]).contains(e.getStatus()))
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

    /**
     * 进程内 fake runner：launch 时建沙箱 <sandboxRoot>/<sid> 并拉起 fake-agent.js，
     * stdout 行解析后经 onRemoteEvent 回传，进程退出后清沙箱（同 runner finalizer）并回 exit。
     */
    final class FakeRunnerConnector implements AgentNodeConnector {
        private final CliEventParser parser;
        private final CliProcessLauncher protocol;
        private final SessionExecutor fake = new FakeProcessLauncher();
        private final Map<String, Process> processes = new ConcurrentHashMap<>();
        /** 最近一次 launch 帧（断言 scenario/manifest 透传用） */
        volatile AgentLaunchCommand lastLaunch;

        FakeRunnerConnector(ObjectMapper mapper, RuntimeSettings settings) {
            this.parser = new CliEventParser(mapper, settings);
            this.protocol = new CliProcessLauncher(settings, mapper);
        }

        @Override
        public boolean isOnline(String nodeId) {
            return NODE.equals(nodeId);
        }

        @Override
        public String defaultNodeId() {
            return NODE;
        }

        @Override
        public void launch(String nodeId, AgentLaunchCommand cmd) {
            lastLaunch = cmd;
            String sid = cmd.sessionId();
            try {
                Path sandbox = sandboxRoot.resolve(sid);
                Files.createDirectories(sandbox);
                Process proc = fake.launch(new SessionExecutor.LaunchContext(
                        sid, sandbox, cmd.taskSpec(), cmd.model(), cmd.permissionMode(), Map.of()));
                processes.put(sid, proc);
                AtomicLong seq = new AtomicLong();
                Thread.ofVirtual().start(() -> readLoop(sid, proc, seq, sandbox));
            } catch (IOException e) {
                throw new RuntimeException("fake runner 拉起失败: " + e.getMessage(), e);
            }
        }

        private void readLoop(String sid, Process proc, AtomicLong seq, Path sandbox) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    for (SessionEvent ev : parser.parse(seq::incrementAndGet, line, "stdout")) {
                        service.onRemoteEvent(NODE, new AgentEventFrame(sid, ev.type(), ev.content(),
                                ev.source(), ev.timestamp(), ev.payload()));
                    }
                }
            } catch (IOException e) {
                // 进程被杀 → 流关闭，走 finally 收口
            } finally {
                int code;
                try {
                    code = proc.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    code = -1;
                }
                processes.remove(sid);
                deleteRecursively(sandbox);
                service.onRemoteExit(NODE, sid, code);
            }
        }

        @Override
        public void sendInput(String nodeId, String sessionId, String text) {
            sendInput(nodeId, sessionId, text, List.of());
        }

        @Override
        public void sendInput(String nodeId, String sessionId, String text, List<InputImage> images) {
            writeStdin(sessionId, protocol.buildUserMessage(text, images));
        }

        @Override
        public void sendAuthorize(String nodeId, String sessionId, String requestId,
                                  boolean accepted, String scope) {
            writeStdin(sessionId, protocol.buildPermissionResult(requestId, accepted, scope));
        }

        @Override
        public void sendFinish(String nodeId, String sessionId) {
            Process proc = processes.get(sessionId);
            if (proc != null) {
                try {
                    proc.getOutputStream().close();
                } catch (IOException e) {
                    // 已退出
                }
            }
        }

        @Override
        public void sendKill(String nodeId, String sessionId) {
            Process proc = processes.get(sessionId);
            if (proc != null) {
                ProcessHelper.killTree(proc);
            }
        }

        @Override
        public void sendSuspend(String nodeId, String sessionId) {
            sendKill(nodeId, sessionId);
        }

        private void writeStdin(String sessionId, String jsonLine) {
            Process proc = processes.get(sessionId);
            if (proc == null || !proc.isAlive()) {
                return;
            }
            synchronized (proc) {
                try {
                    OutputStream out = proc.getOutputStream();
                    out.write((jsonLine + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    // 进程已退出
                }
            }
        }

        private static void deleteRecursively(Path dir) {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            } catch (IOException e) {
                // best-effort
            }
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
        props.setEventFlushMs(50);
        ObjectMapper mapper = JsonMapper.builder().build();
        saver = new ChatEventSaver(events.jpa(), props, mapper);
        saver.start();
        NotificationPublisher noopPublisher = (NotificationEvent e) -> { };
        service = new ChatManagerService(fakeIdentity(), noopPublisher,
                chats.jpa(), events.jpa(), saver, props, mapper,
                objectProviderOf(runner = new FakeRunnerConnector(mapper, props.toRuntimeSettings())),
                // 无 attachment 模块：getIfAvailable 恒 null → 带图输入报错
                emptyObjectProvider(),
                // 无 session 模块：getIfAvailable 恒 null → 传 scenarioCode 报 409
                emptyObjectProvider());
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> objectProviderOf(T bean) {
        return proxy(org.springframework.beans.factory.ObjectProvider.class, (p, m, args) -> switch (m.getName()) {
            case "getIfAvailable" -> bean;
            case "forEach" -> null;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> emptyObjectProvider() {
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
        ChatView v = service.create(new CreateChatRequest("帮我解释下 worktree 是什么", "", "", NODE));
        assertEquals(SessionState.RUNNING.name(), v.status());
        assertEquals("帮我解释下 worktree 是什么", v.title());
        assertEquals(NODE, v.agentNodeId());
        Path sandbox = sandboxRoot.resolve(v.id());
        assertTrue(Files.isDirectory(sandbox), "创建后 runner 沙箱目录应存在");

        // fake-agent 约 2s 后发 permission_request → WAITING_AUTH
        await("进入 WAITING_AUTH", () -> service.get(v.id()).state() == SessionState.WAITING_AUTH);
        service.authorize(v.id(), true, "once", null);
        await("授权后回 RUNNING", () -> service.get(v.id()).state() == SessionState.RUNNING);

        // 多轮输入
        service.input(v.id(), "继续展开说说");
        await("收到 fake 回复", () -> events.store.stream()
                .anyMatch(e -> e.getContent() != null && e.getContent().contains("收到：继续展开说说")));

        // 优雅结束：stdin EOF → fake 发 result 退出 → DONE + 沙箱递归清理（runner finalizer）
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
    void 空节点路由平台默认_无可用节点409_local保留值400() {
        // 留空 → 平台默认节点（fake connector 的 defaultNodeId）
        ChatView v = service.create(new CreateChatRequest("默认路由", "", "", null));
        assertEquals(NODE, v.agentNodeId());
        service.kill(v.id());

        // "local" 保留值已废除 → 400
        assertThrows(com.devmind.common.exception.DevMindException.class,
                () -> service.create(new CreateChatRequest("x", "", "", "local")));
    }

    @Test
    void 场景问答_预设生效_manifest随帧下发_快照落库() {
        ContextManifest manifest = new ContextManifest(2, 100, "abc123");
        ChatContextPreparer preparer = new ChatContextPreparer() {
            @Override
            public ScenarioPreset preset(String scenarioCode) {
                assertEquals("qa", scenarioCode);
                return new ScenarioPreset("claude-opus", "plan", NODE, null);
            }

            @Override
            public PreparedContext prepare(String chatId, String scenarioCode, String message) {
                return new PreparedContext(manifest, "{\"scenarioCode\":\"" + scenarioCode + "\"}",
                        "渲染后：" + message);
            }
        };
        // 换上带 preparer 的 service（setUp 里的是「session 模块未装配」形态）
        ChatProperties props = new ChatProperties();
        service.shutdown();
        service = new ChatManagerService(fakeIdentity(), (NotificationEvent e) -> { },
                chats.jpa(), events.jpa(), saver, props, JsonMapper.builder().build(),
                objectProviderOf(runner), emptyObjectProvider(), objectProviderOf(preparer));

        // 显式全空 → 场景预设的 model/pm/node 生效
        ChatView v = service.create(new CreateChatRequest("你好", null, null, null, "qa"));
        assertEquals("claude-opus", v.model());
        assertEquals("plan", v.permissionMode());
        assertEquals(NODE, v.agentNodeId());

        // launch 帧：渲染后 prompt + manifest
        assertEquals("渲染后：你好", runner.lastLaunch.taskSpec());
        assertEquals(manifest, runner.lastLaunch.contextManifest());

        // 落库三列 + 快照端点
        ChatSessionEntity ent = chats.store.get(v.id());
        assertEquals("qa", ent.getScenarioCode());
        assertEquals("你好", ent.getInitialPrompt());
        assertTrue(ent.getContextManifestJson().contains("\"qa\""));
        assertTrue(service.contextManifest(v.id()).contains("\"qa\""));
        service.kill(v.id());
    }

    @Test
    void 场景问答但session模块未装配时409() {
        assertThrows(com.devmind.common.exception.DevMindException.class,
                () -> service.create(new CreateChatRequest("x", "", "", NODE, "qa")));
    }

    @Test
    void 无场景问答无快照404() {
        ChatView v = service.create(new CreateChatRequest("无场景", "", "", NODE));
        assertThrows(com.devmind.common.exception.DevMindException.class,
                () -> service.contextManifest(v.id()));
        service.kill(v.id());
    }

    @Test
    void 带图输入但附件模块未装配时报错不静默丢图() throws Exception {
        ChatView v = service.create(new CreateChatRequest("看图", "", "", NODE));
        // resolver getIfAvailable 恒 null → CONFLICT
        assertThrows(com.devmind.common.exception.DevMindException.class,
                () -> service.input(v.id(), "", List.of(new ImageRef("abc123", "a.png", "image/png"))));
        // 纯文本输入不受影响
        service.input(v.id(), "纯文本没问题");
        service.kill(v.id());
    }

    @Test
    void 强杀标记终止并清沙箱() throws Exception {
        ChatView v = service.create(new CreateChatRequest("测试 kill", "", "", NODE));
        Path sandbox = sandboxRoot.resolve(v.id());
        await("进程拉起", () -> Files.isDirectory(sandbox));
        service.kill(v.id());
        assertEquals(SessionState.TERMINATED.name(), service.get(v.id()).status());
        await("沙箱目录已清理", () -> !Files.exists(sandbox));
    }

    @Test
    void 挂起恢复_沙箱幂等重建() throws Exception {
        ChatView v = service.create(new CreateChatRequest("测试挂起", "", "", NODE));
        Path sandbox = sandboxRoot.resolve(v.id());
        await("进程拉起", () -> Files.isDirectory(sandbox));
        service.suspend(v.id());
        assertEquals(SessionState.SUSPENDED.name(), service.get(v.id()).status());
        await("挂起后沙箱随进程退出清理", () -> !Files.exists(sandbox));

        ChatView r = service.resume(v.id());
        assertEquals(SessionState.RUNNING.name(), r.status());
        await("恢复后沙箱重建", () -> Files.isDirectory(sandbox));
        await("恢复后进入 WAITING_AUTH", () -> service.get(v.id()).state() == SessionState.WAITING_AUTH);
        service.kill(v.id());
    }

    @Test
    void 历史本机问答不可恢复() {
        ChatSessionEntity ent = new ChatSessionEntity();
        ent.setId("legacy01");
        ent.setTitle("历史本机问答");
        ent.setStatus(SessionState.SUSPENDED.name());
        ent.setAgentNodeId(null); // 本机时代遗留行
        ent.setCreatedBy("tester");
        ent.setCreatedAt(java.time.Instant.now());
        ent.setUpdatedAt(java.time.Instant.now());
        chats.store.put(ent.getId(), ent);
        assertThrows(com.devmind.common.exception.DevMindException.class,
                () -> service.resume("legacy01"));
    }

    @Test
    void 服务端重启后hello对账_清单内reattach_清单外判FAILED() throws Exception {
        // 模拟服务端重启：DB 里两条该节点的 RUNNING 问答，内存 runtimes 为空（本测试未 create）
        for (String id : new String[]{"chat-alive", "chat-gone"}) {
            ChatSessionEntity ent = new ChatSessionEntity();
            ent.setId(id);
            ent.setTitle("重启前问答");
            ent.setStatus(SessionState.RUNNING.name());
            ent.setAgentNodeId(NODE);
            ent.setCreatedBy("tester");
            ent.setCreatedAt(java.time.Instant.now());
            ent.setUpdatedAt(java.time.Instant.now());
            chats.store.put(id, ent);
        }

        service.onRemoteHello(NODE, List.of("chat-alive"));

        // 清单外 → FAILED
        assertEquals(SessionState.FAILED.name(), chats.store.get("chat-gone").getStatus());
        // 清单内 → reattach 挂回：状态不变，且后续 exit 帧可路由（reattach 的证明）
        assertEquals(SessionState.RUNNING.name(), chats.store.get("chat-alive").getStatus());
        service.onRemoteExit(NODE, "chat-alive", 0);
        await("reattach 后 exit 帧路由收口 DONE",
                () -> SessionState.DONE.name().equals(chats.store.get("chat-alive").getStatus()));
    }

    @Test
    void 启动恢复_远程问答不判死留待对账() {
        ChatSessionEntity remote = new ChatSessionEntity();
        remote.setId("chat-remote");
        remote.setTitle("远程问答");
        remote.setStatus(SessionState.RUNNING.name());
        remote.setAgentNodeId(NODE);
        remote.setCreatedBy("tester");
        remote.setCreatedAt(java.time.Instant.now());
        remote.setUpdatedAt(java.time.Instant.now());
        chats.store.put(remote.getId(), remote);

        service.restoreOnStartup();

        assertEquals(SessionState.RUNNING.name(), chats.store.get("chat-remote").getStatus(),
                "远程问答进程在 runner 侧可能仍存活，启动恢复不得判死");
    }
}
