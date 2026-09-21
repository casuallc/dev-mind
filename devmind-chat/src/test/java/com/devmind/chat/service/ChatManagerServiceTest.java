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
import com.devmind.common.exception.DevMindException;
import com.devmind.common.knowledge.KnowledgeRetriever;
import com.devmind.common.knowledge.KnowledgeRetriever.KbOverview;
import com.devmind.common.knowledge.KnowledgeRetriever.RetrievedChunk;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private FakeModelEndpointProvider endpoints;
    /** setUp 用的配置实例（service 持同一引用，用例可改配置而不重建 service） */
    private ChatProperties props;
    /** CAP-49 假 SSE 端点（真起 JDK HttpServer）与它收到的请求体 */
    private com.sun.net.httpserver.HttpServer sse;
    private final List<String> sseRequests = new java.util.concurrent.CopyOnWriteArrayList<>();

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
                case "findByModelEndpointIdAndStatusIn" -> store.values().stream()
                        .filter(e -> args[0].equals(e.getModelEndpointId()))
                        .filter(e -> ((java.util.Collection<?>) args[1]).contains(e.getStatus()))
                        .toList();
                case "updateLiveStatus" -> {
                    ChatSessionEntity e = store.get((String) args[0]);
                    if (e == null) {
                        yield 0;
                    }
                    e.setStatus((String) args[1]);
                    e.setUpdatedAt((java.time.Instant) args[2]);
                    e.setFinishedAt(null); // 与真实 JPQL 同语义：能写活动状态就说明"还没结束"
                    yield 1;
                }
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
                // CAP-49：模型执行体重建上下文——最近 N 条，倒序（调用方反转）
                case "findByChatIdAndSeqLessThanOrderBySeqDesc" -> {
                    int limit = ((org.springframework.data.domain.Pageable) args[2]).getPageSize();
                    yield store.stream()
                            .filter(e -> e.getChatId().equals(args[0]) && e.getSeq() < (Long) args[1])
                            .sorted(Comparator.comparingLong(ChatEventEntity::getSeq).reversed())
                            .limit(limit)
                            .toList();
                }
                case "deleteByChatId" -> {
                    store.removeIf(e -> e.getChatId().equals(args[0]));
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    /**
     * CAP-49 假端点表：内存 map（activeEndpoint 取不到 = 不存在/已停用）。
     * 端点视图的 baseUrl 由用例给（假 SSE 服务器地址，或指向不可用端口测失败路径）。
     */
    static class FakeModelEndpointProvider implements ModelEndpointProvider {
        final Map<Long, ModelEndpointView> store = new ConcurrentHashMap<>();
        volatile ModelEndpointView defaultChat;

        ModelEndpointView add(long id, String kind, String name, String model, String baseUrl) {
            ModelEndpointView v = new ModelEndpointView(id, kind, "openai-compatible", name, baseUrl,
                    "sk-test-key", model, null, 5, 16, null, null);
            store.put(id, v);
            return v;
        }

        @Override
        public Optional<ModelEndpointView> resolve(Long kbEndpointId) {
            return Optional.empty();
        }

        @Override
        public Optional<ModelEndpointView> defaultEndpoint() {
            return Optional.empty();
        }

        @Override
        public Optional<ModelEndpointView> defaultEndpoint(String kind) {
            ModelEndpointView d = defaultChat;
            return d != null && d.kind().equals(kind) ? Optional.of(d) : Optional.empty();
        }

        @Override
        public Optional<ModelEndpointView> activeEndpoint(long id) {
            return Optional.ofNullable(store.get(id));
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
        endpoints = new FakeModelEndpointProvider();
        props = new ChatProperties();
        props.setEventFlushMs(50);
        // CAP-49 用例要"趁生成中"做事（中断/并发注入），增量必须立刻成事件而不是攒到回合结束
        props.setStreamFlushChars(1);
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
                emptyObjectProvider(),
                // 无 knowledge 模块：getIfAvailable 恒 null → 绑库创建 409
                emptyObjectProvider(),
                objectProviderOf(endpoints));
    }

    /** 换上带指定检索器的服务实例（CAP-46 用例重建 service 专用）。 */
    private void rebuildWith(KnowledgeRetriever retriever) {
        service.shutdown();
        props = new ChatProperties();
        props.setStreamFlushChars(1);
        service = new ChatManagerService(fakeIdentity(), (NotificationEvent e) -> { },
                chats.jpa(), events.jpa(), saver, props, JsonMapper.builder().build(),
                objectProviderOf(runner), emptyObjectProvider(), emptyObjectProvider(),
                objectProviderOf(retriever), objectProviderOf(endpoints));
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

    /**
     * CAP-49 假 SSE 端点：每次请求回一段 {text} 的流并落 DONE，请求体记进 {@link #sseRequests}
     * （断言"发出去的 messages"用）。holdAfterFirstMs &gt; 0 时首片之后挂住，供中断用例。
     */
    private String serveSse(String text, long holdAfterFirstMs) throws IOException {
        com.sun.net.httpserver.HttpServer srv =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/chat/completions", ex -> {
            try {
                sseRequests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                OutputStream out = ex.getResponseBody();
                out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (holdAfterFirstMs > 0) {
                    Thread.sleep(holdAfterFirstMs);
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception ignored) {
                // 中断/看门狗收口会让写失败
            } finally {
                try {
                    ex.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        });
        srv.start();
        sse = srv;
        return "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (sse != null) {
            sse.stop(0);
        }
        service.shutdown();
        saver.stop();
        // 残留 fake 进程（node 子进程）必须杀掉并等 readLoop 收口完——Windows 下进程 cwd 句柄
        // 不释放 / 收口线程与删除竞态会让 @TempDir 清理抛 "Failed to close extension context"（flaky）
        for (Process p : runner.processes.values()) {
            ProcessHelper.killTree(p);
        }
        long deadline = System.currentTimeMillis() + 10_000;
        while (!runner.processes.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
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
                objectProviderOf(runner), emptyObjectProvider(), objectProviderOf(preparer),
                emptyObjectProvider(), emptyObjectProvider());

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
    void init事件捕获cliSessionId落库() throws Exception {
        ChatView v = service.create(new CreateChatRequest("测试捕获", "", "", NODE));
        // fake-agent 启动即发 system/init（session_id = 会话 id）→ onRemoteEvent 捕获落库
        await("cliSessionId 落库", () -> v.id().equals(chats.store.get(v.id()).getCliSessionId()));
        service.kill(v.id());
    }

    @Test
    void DONE问答可继续对话_launch带resumeSessionId且finishedAt清空() throws Exception {
        ChatView v = service.create(new CreateChatRequest("测试终态恢复", "", "", NODE));
        await("进程拉起", () -> Files.isDirectory(sandboxRoot.resolve(v.id())));
        await("cliSessionId 落库", () -> chats.store.get(v.id()).getCliSessionId() != null);
        service.finish(v.id());
        await("问答 DONE", () -> service.get(v.id()).status().equals(SessionState.DONE.name()));

        ChatView r = service.resume(v.id());
        assertEquals(SessionState.RUNNING.name(), r.status());
        // launch 帧带 --resume 目标（= 捕获的 cliSessionId），对话历史由 claude 续接
        assertEquals(chats.store.get(v.id()).getCliSessionId(), runner.lastLaunch.resumeSessionId());
        assertNull(chats.store.get(v.id()).getFinishedAt(), "恢复后 finishedAt 应清空");
        await("恢复后沙箱重建", () -> Files.isDirectory(sandboxRoot.resolve(v.id())));
        service.kill(v.id());
    }

    @Test
    void 终态无cliSessionId不可恢复_活跃态不可恢复() throws Exception {
        // 升级前的历史 DONE 记录（无 cliSessionId）→ 409
        ChatSessionEntity ent = new ChatSessionEntity();
        ent.setId("legacy-done");
        ent.setTitle("历史已完成问答");
        ent.setStatus(SessionState.DONE.name());
        ent.setAgentNodeId(NODE);
        ent.setCreatedBy("tester");
        ent.setCreatedAt(java.time.Instant.now());
        ent.setUpdatedAt(java.time.Instant.now());
        chats.store.put(ent.getId(), ent);
        assertThrows(com.devmind.common.exception.DevMindException.class,
                () -> service.resume("legacy-done"));

        // 活跃态 → 409（无需恢复）
        ChatView v = service.create(new CreateChatRequest("运行中", "", "", NODE));
        assertThrows(com.devmind.common.exception.DevMindException.class,
                () -> service.resume(v.id()));
        service.kill(v.id());
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

    // ---------------- CAP-46 知识库问答 ----------------

    /** 可变 fake 检索器：hits 可切换、fail=true 模拟检索异常、记录调用次数。 */
    static class FakeRetriever implements KnowledgeRetriever {
        volatile List<RetrievedChunk> hits = List.of();
        volatile boolean fail = false;
        final AtomicInteger retrieveCalls = new AtomicInteger();

        @Override
        public boolean vectorAvailable() {
            return true;
        }

        @Override
        public List<RetrievedChunk> retrieve(List<Long> kbIds, String query, int topK) {
            retrieveCalls.incrementAndGet();
            if (fail) {
                throw new RuntimeException("检索炸了");
            }
            return hits;
        }

        @Override
        public Optional<KbOverview> overview(long kbId) {
            return kbId == 7L
                    ? Optional.of(new KbOverview("研发规范库", "团队研发规范", "RAG",
                            List.of("构建规范", "部署规范")))
                    : Optional.empty();
        }
    }

    @Test
    void 绑库问答_启动注入库概览_每轮检索注入前缀() throws Exception {
        FakeRetriever retriever = new FakeRetriever();
        retriever.hits = List.of(new RetrievedChunk(1L, "构建规范", 7L, "构建前必须先跑单测", 0.9));
        rebuildWith(retriever);

        // 库不存在 → 400
        assertThrows(DevMindException.class,
                () -> service.create(new CreateChatRequest("x", null, null, NODE, null, 99L)));

        ChatView v = service.create(new CreateChatRequest("构建规范有哪些", null, null, NODE, null, 7L));
        assertEquals(7L, v.knowledgeBaseId());
        assertEquals(7L, chats.store.get(v.id()).getKnowledgeBaseId(), "knowledge_base_id 落库");

        // 启动注入：库概览节拼进初始 prompt 前缀
        String prompt = runner.lastLaunch.taskSpec();
        assertTrue(prompt.startsWith("<knowledge-base>"), "库概览节在初始 prompt 最前");
        assertTrue(prompt.contains("研发规范库") && prompt.contains("注入模式：RAG"));
        assertTrue(prompt.contains("- 构建规范") && prompt.contains("- 部署规范"));
        assertTrue(prompt.endsWith("构建规范有哪些"), "概览节后接原始首条消息");

        // 每轮注入：fake agent 回显可见 <knowledge-context> 前缀与来源标注
        service.input(v.id(), "构建前要做什么");
        await("检索注入回显", () -> events.store.stream().anyMatch(e ->
                e.getContent() != null && e.getContent().contains("<knowledge-context>")));
        assertTrue(events.store.stream().anyMatch(e ->
                e.getContent() != null && e.getContent().contains("（来源：构建规范）")), "命中块带来源标注");
        assertTrue(retriever.retrieveCalls.get() >= 1);
        service.kill(v.id());
    }

    @Test
    void 绑库问答_检索无命中或异常时原样发送() throws Exception {
        FakeRetriever retriever = new FakeRetriever();
        rebuildWith(retriever);
        ChatView v = service.create(new CreateChatRequest("首问", null, null, NODE, null, 7L));

        // 无命中（hits 空）→ 原样发送，不带注入壳
        service.input(v.id(), "问题甲");
        await("无命中回显", () -> events.store.stream().anyMatch(e ->
                e.getContent() != null && e.getContent().contains("问题甲")));
        assertTrue(events.store.stream().noneMatch(e ->
                e.getContent() != null && e.getContent().contains("<knowledge-context>")),
                "无命中不注入空壳");

        // 检索异常 → 同样原样发送
        retriever.fail = true;
        service.input(v.id(), "问题乙");
        await("异常降级回显", () -> events.store.stream().anyMatch(e ->
                e.getContent() != null && e.getContent().contains("问题乙")));
        assertTrue(events.store.stream().noneMatch(e ->
                e.getContent() != null && e.getContent().contains("<knowledge-context>")),
                "检索异常按无命中降级");
        service.kill(v.id());
    }

    @Test
    void 绑库但knowledge模块未装配时409() {
        assertThrows(DevMindException.class,
                () -> service.create(new CreateChatRequest("x", null, null, NODE, null, 7L)));
    }

    @Test
    void 未绑库问答不调用检索() throws Exception {
        FakeRetriever retriever = new FakeRetriever();
        rebuildWith(retriever);
        ChatView v = service.create(new CreateChatRequest("普通问答", null, null, NODE, null));
        assertNull(v.knowledgeBaseId());

        service.input(v.id(), "随便聊聊");
        await("普通回显", () -> events.store.stream().anyMatch(e ->
                e.getContent() != null && e.getContent().contains("随便聊聊")));
        assertEquals(0, retriever.retrieveCalls.get(), "未绑库不得触发检索");
        service.kill(v.id());
    }

    // ---------------- CAP-49 模型执行体 ----------------

    /** 模型执行体建会话请求（Agent 专有字段一律留空）。 */
    private static CreateChatRequest modelRequest(String message, Long endpointId) {
        return new CreateChatRequest(message, null, null, null, null, null,
                ChatSessionEntity.EXECUTOR_MODEL, endpointId);
    }

    private boolean hasEvent(String type, String contains) {
        return events.store.stream().anyMatch(e -> type.equals(e.getType())
                && e.getContent() != null && e.getContent().contains(contains));
    }

    /** haystack 里 needle 出现次数（断言"只出现一次"：重复回流的历史块就是这么抓出来的） */
    private static int count(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    @Test
    void 模型问答_无节点也能出流_空闲状态落库() throws Exception {
        endpoints.defaultChat = endpoints.add(21L, "CHAT", "本地 vLLM", "qwen2.5", serveSse("你好，世界", 0));

        ChatView v = service.create(modelRequest("在吗", null));

        await("模型回答落库", () -> hasEvent("assistant", "你好，世界"));
        await("回合结束回空闲", () -> service.get(v.id()).state() == SessionState.WAITING_INPUT);
        assertEquals(ChatSessionEntity.EXECUTOR_MODEL, v.executor());
        assertEquals(21L, v.modelEndpointId());
        assertEquals("本地 vLLM", v.modelEndpointName(), "视图带端点展示名（前端徽标用）");
        assertEquals("qwen2.5", v.model(), "模型身份来自端点");
        assertEquals("qwen2.5", v.modelEndpointModel());
        assertEquals(SessionState.WAITING_INPUT.name(), chats.store.get(v.id()).getStatus(),
                "空闲状态必须落库：重启后才知道这场问答可以继续");
        assertNull(chats.store.get(v.id()).getAgentNodeId(), "模型执行体不进节点路由");
        assertNull(chats.store.get(v.id()).getPermissionMode());
        assertNull(runner.lastLaunch, "模型执行体不 launch 任何节点");
        assertTrue(events.store.stream().anyMatch(e -> "assistant".equals(e.getType())
                && "model".equals(e.getSource())), "事件来源标记 model（排障要能区分 CLI 产出）");
    }

    @Test
    void 模型问答_第二轮历史由事件重建_含系统提示() throws Exception {
        endpoints.defaultChat = endpoints.add(21L, "CHAT", "本地 vLLM", "qwen2.5", serveSse("第一答", 0));
        ChatView v = service.create(modelRequest("第一问", null));
        await("首轮完成", () -> hasEvent("assistant", "第一答"));
        await("可再次输入", () -> service.get(v.id()).state() == SessionState.WAITING_INPUT);

        service.input(v.id(), "第二问");
        await("第二轮完成", () -> sseRequests.size() >= 2);

        String body = sseRequests.get(1);
        // 服务端无内存态：多轮上下文每次都从 chat_events 重建（重启后照样能续问）
        assertTrue(body.contains("第一问"), "历轮提问要带上: " + body);
        assertTrue(body.contains("第一答"), "历轮回答要带上");
        assertTrue(body.contains("\"role\":\"user\"") && body.contains("\"role\":\"assistant\""));
        assertTrue(body.contains("Dev-Mind 平台的通用问答助手"), "system 提示每轮都发");
        assertEquals(1, count(body, "第一答"),
                "同一轮回答只进一次上下文（DB 与内存环形缓冲按 seq 归并，不是两份叠加）");
    }

    @Test
    void 模型问答_绑库时系统提示带库概览_本轮检索前缀原样保留() throws Exception {
        FakeRetriever retriever = new FakeRetriever();
        retriever.hits = List.of(new RetrievedChunk(1L, "构建规范", 7L, "构建前必须先跑单测", 0.9));
        rebuildWith(retriever);
        endpoints.defaultChat = endpoints.add(21L, "CHAT", "本地 vLLM", "qwen2.5", serveSse("答", 0));

        ChatView v = service.create(new CreateChatRequest("构建规范有哪些", null, null, null, null, 7L,
                ChatSessionEntity.EXECUTOR_MODEL, null));
        await("首轮完成", () -> hasEvent("assistant", "答"));

        String system = sseRequests.get(0);
        assertTrue(system.contains("研发规范库"), "库概览节进 system（AGENT 走初始 prompt，模型执行体走 system）");
        assertTrue(system.contains("构建规范"), "库内条目也在");
        assertTrue(system.contains("\"role\":\"system\""));
        service.input(v.id(), "构建前要做什么");
        await("第二轮发出", () -> sseRequests.size() >= 2);
        assertTrue(sseRequests.get(1).contains("<knowledge-context>"),
                "本轮新检索块原样发给模型");
        assertTrue(sseRequests.get(1).contains("构建前必须先跑单测"), "命中内容在");
    }

    @Test
    void 模型问答_多轮历史剥掉历轮检索前缀() throws Exception {
        FakeRetriever retriever = new FakeRetriever();
        retriever.hits = List.of(new RetrievedChunk(1L, "构建规范", 7L, "构建前必须先跑单测", 0.9));
        rebuildWith(retriever);
        endpoints.defaultChat = endpoints.add(21L, "CHAT", "本地 vLLM", "qwen2.5", serveSse("答一", 0));
        ChatView v = service.create(new CreateChatRequest("甲问", null, null, null, null, 7L,
                ChatSessionEntity.EXECUTOR_MODEL, null));
        await("首轮完成", () -> hasEvent("assistant", "答一"));

        service.input(v.id(), "乙问");
        await("第二轮发出", () -> sseRequests.size() >= 2);
        assertTrue(sseRequests.get(1).contains("<knowledge-context>"), "第二轮带本轮检索块");

        service.input(v.id(), "丙问");
        await("第三轮发出", () -> sseRequests.size() >= 3);

        String body = sseRequests.get(2);
        // 数闭合标签：system 提示里有一句提到 <knowledge-context>（讲规则），只有真正的注入块才带闭合标签
        assertEquals(1, count(body, "</knowledge-context>"), "历轮的检索块不许回流进历史"
                + "（旧检索结果当新资料既浪费 token 又误导模型）：" + body);
        assertTrue(body.contains("乙问"), "但历轮提问原文要在");
        assertEquals(1, count(body, "<knowledge-base>"),
                "库概览只在 system 里出现这一次（历轮消息里的注入块都该剥干净）：" + body);
    }

    @Test
    void 模型问答_端点解析_显式非对话端点400_无默认409_模块未装配409() throws Exception {
        // 显式给了向量端点 → 400（拿 embedding 模型名去打 /chat/completions 是必坏的组合）
        endpoints.add(31L, "EMBEDDING", "向量端点", "bge-m3", "http://127.0.0.1:1/v1");
        assertThrows(DevMindException.class, () -> service.create(modelRequest("x", 31L)));
        // 显式给了不存在的端点 → 400
        assertThrows(DevMindException.class, () -> service.create(modelRequest("x", 99L)));
        // 未给 + 无平台默认 CHAT 端点 → 409（不回落向量端点）
        assertThrows(DevMindException.class, () -> service.create(modelRequest("x", null)));

        // 模型模块未装配 → 409
        service.shutdown();
        ChatProperties p = new ChatProperties();
        service = new ChatManagerService(fakeIdentity(), (NotificationEvent e) -> { },
                chats.jpa(), events.jpa(), saver, p, JsonMapper.builder().build(),
                objectProviderOf(runner), emptyObjectProvider(), emptyObjectProvider(),
                emptyObjectProvider(), emptyObjectProvider());
        assertThrows(DevMindException.class, () -> service.create(modelRequest("x", null)));
    }

    @Test
    void 模型问答_Agent专有字段一律400不静默忽略() throws Exception {
        endpoints.defaultChat = endpoints.add(21L, "CHAT", "本地 vLLM", "qwen2.5", serveSse("答", 0));
        // 场景资产靠 runner manifest 物化，模型执行体拿不到——静默丢上下文比报错坏得多
        assertThrows(DevMindException.class, () -> service.create(new CreateChatRequest(
                "x", null, null, null, "qa", null, ChatSessionEntity.EXECUTOR_MODEL, null)));
        assertThrows(DevMindException.class, () -> service.create(new CreateChatRequest(
                "x", null, null, NODE, null, null, ChatSessionEntity.EXECUTOR_MODEL, null)));
        assertThrows(DevMindException.class, () -> service.create(new CreateChatRequest(
                "x", null, "plan", null, null, null, ChatSessionEntity.EXECUTOR_MODEL, null)));
        assertThrows(DevMindException.class, () -> service.create(new CreateChatRequest(
                "x", "claude-opus", null, null, null, null, ChatSessionEntity.EXECUTOR_MODEL, null)));
        // 未知执行体也不许静默当 Agent 用
        assertThrows(DevMindException.class, () -> service.create(new CreateChatRequest(
                "x", null, null, null, null, null, "FOO", null)));
    }

    @Test
    void 模型问答_图片400_挂起409_授权409_中断Agent409() throws Exception {
        endpoints.defaultChat = endpoints.add(21L, "CHAT", "本地 vLLM", "qwen2.5", serveSse("答", 0));
        ChatView v = service.create(modelRequest("在吗", null));
        await("首轮完成", () -> hasEvent("assistant", "答"));

        assertThrows(DevMindException.class,
                () -> service.input(v.id(), "", List.of(new ImageRef("abc123", "a.png", "image/png"))),
                "模型执行体无图片能力（附件解析前就拒，不静默丢图）");
        assertThrows(DevMindException.class, () -> service.suspend(v.id()), "无进程可挂起");
        assertThrows(DevMindException.class, () -> service.authorize(v.id(), true, "once", null),
                "无授权概念（不会产生授权请求）：不能操作成功了但什么都没发生");
        service.input(v.id(), "继续");
        await("第二轮完成", () -> service.get(v.id()).state() == SessionState.WAITING_INPUT);
        assertThrows(DevMindException.class, () -> service.interrupt(v.id()), "没有在生成的回合 → 409");

        // Agent 会话的中断 → 409（claude 按回合输出，没有"中断一轮"的语义）
        ChatView a = service.create(new CreateChatRequest("agent 问答", "", "", NODE));
        assertThrows(DevMindException.class, () -> service.interrupt(a.id()));
        service.kill(a.id());
    }

    @Test
    void 模型问答_中断保留已产出正文_会话可继续() throws Exception {
        // 首片之后挂住 2s：足够"趁生成中"点停止
        endpoints.defaultChat = endpoints.add(21L, "CHAT", "本地 vLLM", "qwen2.5", serveSse("前半段", 2000));
        ChatView v = service.create(modelRequest("写长点", null));
        await("首片已到", () -> hasEvent("text_delta", "前半段"));

        service.interrupt(v.id());

        await("中断落 result", () -> hasEvent("result", "前半段"));
        await("回空闲可继续", () -> service.get(v.id()).state() == SessionState.WAITING_INPUT);
        assertTrue(events.store.stream().anyMatch(e -> "assistant".equals(e.getType())
                && "前半段".equals(e.getContent())), "已产出的部分要留下（用户已经看到了）");
        assertTrue(events.store.stream().anyMatch(e -> "result".equals(e.getType())
                && e.getPayload() != null && e.getPayload().contains("interrupted")));
        assertEquals(SessionState.WAITING_INPUT.name(), chats.store.get(v.id()).getStatus());
    }

    @Test
    void 模型问答_端点失败不判死_留在WAITING_INPUT() throws Exception {
        // 端口 1 必连不上（无监听）
        endpoints.defaultChat = endpoints.add(21L, "CHAT", "坏端点", "qwen", "http://127.0.0.1:1/v1");
        ChatView v = service.create(modelRequest("在吗", null));

        await("落 error", () -> hasEvent("error", ""));
        await("落 result 回空闲", () -> service.get(v.id()).state() == SessionState.WAITING_INPUT);
        assertTrue(events.store.stream().anyMatch(e -> "result".equals(e.getType())
                && e.getPayload() != null && e.getPayload().contains("\"isError\":true")));
        assertEquals(SessionState.WAITING_INPUT.name(), chats.store.get(v.id()).getStatus(),
                "端点这一轮没成不等于会话结束：留在 WAITING_INPUT 让用户改完重试");
    }

    @Test
    void 模型问答_懒重挂_端点停用后明确409() throws Exception {
        // 模拟服务端重启：DB 里有一条空闲的模型问答（执行体/端点已钉住），内存 runtimes 为空
        ChatSessionEntity ent = new ChatSessionEntity();
        ent.setId("model-idle");
        ent.setTitle("重启前的模型问答");
        ent.setStatus(SessionState.WAITING_INPUT.name());
        ent.setExecutor(ChatSessionEntity.EXECUTOR_MODEL);
        ent.setModelEndpointId(21L);
        ent.setModel("qwen2.5");
        ent.setCreatedBy("tester");
        ent.setCreatedAt(java.time.Instant.now());
        ent.setUpdatedAt(java.time.Instant.now());
        chats.store.put(ent.getId(), ent);
        endpoints.add(21L, "CHAT", "本地 vLLM", "qwen2.5", serveSse("续答", 0));

        // 直接提问 → 运行时不在内存也会被重挂（历史在 DB，无需节点、无需端点解析到创建时那份）
        service.input("model-idle", "继续问");
        await("重挂后照常出流", () -> hasEvent("assistant", "续答"));
        assertEquals(21L, chats.store.get("model-idle").getModelEndpointId(), "端点不漂移");

        // 端点被停用/删除 → 明确 409（不悄悄换一个端点继续说话，也不拿着旧快照继续说话）
        endpoints.store.remove(21L);
        DevMindException gone = assertThrows(DevMindException.class,
                () -> service.input("model-idle", "再问一句"));
        assertTrue(gone.getMessage().contains("已停用或删除"),
                "要说清是端点没了（而不是难懂的连接错误）：" + gone.getMessage());
        // 读历史不受影响：端点没了也要能打开看
        assertFalse(service.events("model-idle", 0).isEmpty());
    }

    @Test
    void 模型问答_续对话不需要cliSessionId也不launch() throws Exception {
        endpoints.defaultChat = endpoints.add(21L, "CHAT", "本地 vLLM", "qwen2.5", serveSse("答", 0));
        ChatView v = service.create(modelRequest("首问", null));
        await("首轮完成", () -> hasEvent("assistant", "答"));
        service.finish(v.id());
        await("结束判 DONE", () -> service.get(v.id()).status().equals(SessionState.DONE.name()));
        assertNull(chats.store.get(v.id()).getCliSessionId(), "模型执行体没有 CLI 会话");

        ChatView r = service.resume(v.id());
        assertEquals(SessionState.RUNNING.name(), r.status());
        assertNull(chats.store.get(v.id()).getFinishedAt(), "恢复后 finishedAt 清空");
    }

    @Test
    void 模型问答_启动自处_生成中判中断_空闲留着懒重挂() {
        for (String[] row : new String[][]{{"model-running", SessionState.RUNNING.name()},
                {"model-idle", SessionState.WAITING_INPUT.name()}}) {
            ChatSessionEntity e = new ChatSessionEntity();
            e.setId(row[0]);
            e.setTitle("重启前后的模型问答");
            e.setStatus(row[1]);
            e.setExecutor(ChatSessionEntity.EXECUTOR_MODEL);
            e.setModelEndpointId(21L);
            e.setCreatedBy("tester");
            e.setCreatedAt(java.time.Instant.now());
            e.setUpdatedAt(java.time.Instant.now());
            chats.store.put(e.getId(), e);
        }

        service.restoreOnStartup();

        assertEquals(SessionState.TERMINATED.name(), chats.store.get("model-running").getStatus(),
                "生成中被重启打断：这一轮 HTTP 流随旧实例没了");
        assertTrue(chats.store.get("model-running").getSummary().contains("已中断"));
        assertEquals(SessionState.WAITING_INPUT.name(), chats.store.get("model-idle").getStatus(),
                "空闲的模型问答没有进程可失去，留着懒重挂（这是模型执行体比 CLI 会话多的收益）");
    }

    @Test
    void 模型问答_容量与Agent分账且只数生成中() throws Exception {
        props.setMaxConcurrentModel(1);
        // 长流端点：第一个问答一直处于"生成中"
        endpoints.defaultChat = endpoints.add(21L, "CHAT", "慢端点", "qwen", serveSse("慢", 3000));
        ChatView v = service.create(modelRequest("写长点", null));
        await("进入生成中", () -> hasEvent("text_delta", "慢"));

        assertThrows(DevMindException.class, () -> service.create(modelRequest("再来一个", null)),
                "生成中占额度");
        // Agent 配额不受模型问答影响（两者分账，闲置模型问答更不该挤占 runner 名额）
        ChatView a = service.create(new CreateChatRequest("agent 问答", "", "", NODE));
        assertEquals(SessionState.RUNNING.name(), a.status());
        service.kill(a.id());

        service.interrupt(v.id());
        await("中断后额度释放", () -> service.get(v.id()).state() == SessionState.WAITING_INPUT);
        ChatView again = service.create(modelRequest("再来一个", null));
        assertEquals(SessionState.RUNNING.name(), again.status(), "空闲模型问答不占额度");
    }

    @Test
    void 端点引用保护_只报正在生成的模型问答() throws Exception {
        endpoints.defaultChat = endpoints.add(21L, "CHAT", "慢端点", "qwen", serveSse("慢", 3000));
        ChatView v = service.create(modelRequest("写长点", null));
        await("进入生成中", () -> hasEvent("text_delta", "慢"));

        ChatEndpointUsageProvider usage = new ChatEndpointUsageProvider(chats.jpa());
        assertEquals(List.of("问答：写长点"), usage.usagesOf(21L),
                "正在生成 → 引用方要报出来（端点删了会把这一轮打断）");

        service.interrupt(v.id());
        await("回空闲", () -> service.get(v.id()).state() == SessionState.WAITING_INPUT);
        assertTrue(usage.usagesOf(21L).isEmpty(),
                "空闲问答不锁端点删除权：问过一次就永久删不掉端点是把保护用错了地方");
    }
}
