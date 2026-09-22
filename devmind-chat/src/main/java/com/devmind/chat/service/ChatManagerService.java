package com.devmind.chat.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
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
import com.devmind.common.agent.WorkspaceQueryResult;
import com.devmind.common.agent.exec.ContextManifest;
import com.devmind.common.agent.runtime.AbstractSessionRuntime;
import com.devmind.common.agent.runtime.ModelSessionRuntime;
import com.devmind.common.agent.runtime.RemoteSessionRuntime;
import com.devmind.common.agent.runtime.RuntimeListener;
import com.devmind.common.agent.runtime.RuntimeSettings;
import com.devmind.common.agent.runtime.SessionHandle;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.common.attachment.AttachmentContentResolver;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.knowledge.KnowledgeRetriever;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
import com.devmind.common.notification.NotificationEvent;
import com.devmind.notification.NotificationPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * CAP-30 通用问答生命周期入口：create/list/get/events/input/authorize/suspend/resume/kill/finish/delete。
 * 复用 common 的 headless 会话内核（{@link RemoteSessionRuntime}），
 * 本类只做实体/节点路由的薄壳协调。
 *
 * <p>CAP-34 FR-02：不存在本机问答——节点路由 = 显式 agentNodeId &gt; 场景预设（CAP-33 FR-05）
 * &gt; 平台默认节点（无项目默认层），皆无命中直接 409；保留值 "local" 已废除（报 400）。
 * 节点离线 launch 抛 409，不静默回落。</p>
 *
 * <p>CAP-33 FR-05：scenarioCode 非空时经 {@link ChatContextPreparer}（session 模块实现）
 * 取场景预设（模型/权限/节点）并装配上下文包——骨架渲染产物作初始 prompt，manifest 随
 * launch 帧下发，快照落 context_manifest_json。</p>
 *
 * <p>沙箱 cwd：runner 侧 &lt;workspaceRoot&gt;/_chat/&lt;chatId&gt;（launch 帧 kind:"chat"），
 * 服务端不再建本地目录。</p>
 *
 * <p>CAP-49 执行体分流：{@code executor=MODEL} 时走 {@link ModelSessionRuntime}——服务端直连
 * 一个已接入的 {@code kind=CHAT} 端点，<b>不需要任何节点在线、也不拉起任何进程</b>（这是 CAP-34
 * "服务端零执行"的收敛性例外：只有出站 HTTP）。与 AGENT 的四点差异：</p>
 * <ul>
 *   <li><b>状态不以内存为准</b>：多轮上下文每轮从 chat_events 重建，运行时可以随时重挂
 *       （{@link #requireOrReattachRuntime}）——重启后模型问答仍可继续提问，这是 CLI 会话做不到的。</li>
 *   <li><b>无授权、无挂起</b>：没有进程可以挂起来，也没有授权请求会产生（两个动作都 409）。</li>
 *   <li><b>容量分账</b>：AGENT 数"活动会话"（每个都占 runner 进程），MODEL 只数"正在生成"的。</li>
 *   <li><b>失败不判死</b>：端点这一轮没成只落 error+result{isError}，会话留在 WAITING_INPUT
 *       等用户改完重试（模型会话是"只有人让它结束"的会话）。</li>
 * </ul>
 */
@Service
public class ChatManagerService {

    private static final Logger log = LoggerFactory.getLogger(ChatManagerService.class);

    private final IdentityService identityService;
    private final NotificationPublisher notificationPublisher;
    private final ChatSessionRepository chatRepo;
    private final ChatEventRepository eventRepo;
    private final ChatEventSaver eventSaver;
    private final ChatProperties props;
    private final ObjectMapper mapper;
    private final RuntimeSettings settings;
    /** CAP-21：远程节点连接（devmind-agent 装配时可用；ObjectProvider 探测防循环依赖） */
    private final ObjectProvider<AgentNodeConnector> connectorProvider;
    /** CAP-32：附件内容解析（devmind-attachment 装配时可用）；未装配时带附件输入报错，不静默丢图 */
    private final ObjectProvider<AttachmentContentResolver> attachmentResolverProvider;
    /** CAP-33 FR-05：场景问答装配（devmind-session 装配时可用；ObjectProvider 探测防循环依赖） */
    private final ObjectProvider<ChatContextPreparer> contextPreparerProvider;
    /** CAP-46：知识库检索（devmind-knowledge 装配时可用）；绑库问答未装配即 409 */
    private final ObjectProvider<KnowledgeRetriever> retrieverProvider;
    /** CAP-49：模型端点解析（devmind-model 装配时可用）；未装配即无法建模型问答（409） */
    private final ObjectProvider<ModelEndpointProvider> endpointProvider;

    /** 运行中问答注册表（本地/远程统一句柄）。 */
    private final Map<String, SessionHandle> runtimes = new ConcurrentHashMap<>();

    /** CAP-49：模型执行体的 system 提示（多轮装配每轮复用；不落事件流，不会进用户可见文本） */
    private static final String MODEL_SYSTEM_PROMPT = """
            你是 Dev-Mind 平台的通用问答助手，直接回答用户的问题，不调用任何工具。
            用中文回答，简洁、准确；不确定就明确说不确定，不要编造。
            这是多轮对话，请保持上下文连贯。""";

    /** 订阅路径可重挂的状态（活动态）：终态会话不该因为"被打开过"就在内存里留一个运行时 */
    private static final List<String> LIVE_STATUSES = List.of(SessionState.RUNNING.name(),
            SessionState.WAITING_INPUT.name(), SessionState.WAITING_AUTH.name());

    public ChatManagerService(IdentityService identityService,
                              NotificationPublisher notificationPublisher,
                              ChatSessionRepository chatRepo,
                              ChatEventRepository eventRepo,
                              ChatEventSaver eventSaver,
                              ChatProperties props,
                              ObjectMapper mapper,
                              ObjectProvider<AgentNodeConnector> connectorProvider,
                              ObjectProvider<AttachmentContentResolver> attachmentResolverProvider,
                              ObjectProvider<ChatContextPreparer> contextPreparerProvider,
                              ObjectProvider<KnowledgeRetriever> retrieverProvider,
                              ObjectProvider<ModelEndpointProvider> endpointProvider) {
        this.identityService = identityService;
        this.notificationPublisher = notificationPublisher;
        this.chatRepo = chatRepo;
        this.eventRepo = eventRepo;
        this.eventSaver = eventSaver;
        this.props = props;
        this.mapper = mapper;
        this.connectorProvider = connectorProvider;
        this.attachmentResolverProvider = attachmentResolverProvider;
        this.contextPreparerProvider = contextPreparerProvider;
        this.retrieverProvider = retrieverProvider;
        this.endpointProvider = endpointProvider;
        this.settings = props.toRuntimeSettings();
    }

    private final RuntimeListener listener = new RuntimeListener() {
        @Override
        public void onStateChange(String sessionId, SessionState state, SessionEvent stateEvent) {
            persistModelLiveState(sessionId, state);
            switch (state) {
                case WAITING_AUTH -> notificationPublisher.publish(NotificationEvent.of(
                        "WAITING_AUTH", sessionId, "问答需要授权", stateEvent.content()));
                case WAITING_INPUT -> notificationPublisher.publish(NotificationEvent.of(
                        "WAITING_INPUT", sessionId, "问答在等待你的输入", stateEvent.content()));
                default -> { }
            }
        }

        @Override
        public void onExit(String sessionId, int exitCode, boolean success, String summary) {
            runtimes.remove(sessionId);
            chatRepo.findById(sessionId).ifPresent(ent -> {
                ent.setStatus((success ? SessionState.DONE : SessionState.FAILED).name());
                ent.setSummary(summary == null || summary.isBlank() ? null : summary);
                ent.setFinishedAt(Instant.now());
                ent.setUpdatedAt(Instant.now());
                chatRepo.save(ent);
            });
        }
    };

    /**
     * CAP-49：把模型执行体的实时状态落库（RUNNING=正在生成 / WAITING_INPUT=空闲可续问）。
     *
     * <p>模型执行体没有进程可探测，DB 状态就是唯一凭据，两个消费方都靠它：重启自处
     * （RUNNING → 生成已中断；WAITING_INPUT → 留着懒重挂）与端点引用保护（只锁"正在生成"的）。</p>
     *
     * <p>只写 status/updated_at（批量 UPDATE）：状态变更发生在生成线程上，且问答删除的事务里
     * 若正好完成一轮生成，整实体 save 会把已删除的行插回来。AGENT 会话不在此路径——它的状态
     * 由 onExit / updateStatus 写。</p>
     */
    private void persistModelLiveState(String sessionId, SessionState state) {
        SessionHandle h = runtimes.get(sessionId);
        if (!(h instanceof ModelSessionRuntime)) {
            return;
        }
        if (state != SessionState.RUNNING && state != SessionState.WAITING_INPUT) {
            return;
        }
        try {
            chatRepo.updateLiveStatus(sessionId, state.name(), Instant.now());
        } catch (Exception e) {
            log.warn("模型问答实时状态落库失败: chat={} state={} err={}", sessionId, state, e.getMessage());
        }
    }

    // ---------------- 创建 / 生命周期 ----------------

    public ChatView create(CreateChatRequest req) {
        String executor = executorOf(req.executor());
        boolean modelExecutor = ChatSessionEntity.EXECUTOR_MODEL.equals(executor);
        if (modelExecutor) {
            rejectModelConflicts(req);
            ensureModelCapacity();
        } else {
            ensureCapacity();
        }
        String id = shortId();

        // CAP-33 FR-05 场景预设：模型/权限/节点优先级 = 显式 > 场景 > 配置/平台默认
        ChatContextPreparer preparer = null;
        ChatContextPreparer.ScenarioPreset preset = null;
        if (req.scenarioCode() != null && !req.scenarioCode().isBlank()) {
            preparer = contextPreparerProvider.getIfAvailable();
            if (preparer == null) {
                throw new DevMindException(ErrorCode.CONFLICT, "会话模块未装配，无法使用场景问答");
            }
            preset = preparer.preset(req.scenarioCode()); // 场景不存在 404
        }
        String model = firstNonBlank(req.model(), preset != null ? preset.model() : null, props.getModel());
        String pm = firstNonBlank(req.permissionMode(), preset != null ? preset.permissionMode() : null,
                props.getPermissionMode());

        // CAP-46 FR-01：绑库问答——库概览用于启动注入；未装配 knowledge 模块 409，库不存在/已归档 400
        KnowledgeRetriever.KbOverview kbOverview = null;
        if (req.knowledgeBaseId() != null) {
            KnowledgeRetriever retriever = retrieverProvider.getIfAvailable();
            if (retriever == null) {
                throw new DevMindException(ErrorCode.CONFLICT, "知识库模块未装配，无法绑定知识库问答");
            }
            kbOverview = retriever.overview(req.knowledgeBaseId())
                    .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST,
                            "知识库不存在或已归档: " + req.knowledgeBaseId()));
        }

        // CAP-49：模型执行体自成一格——无节点路由、无场景装配、无 launch，端点即执行环境
        if (modelExecutor) {
            return createModelChat(id, req, kbOverview);
        }

        // CAP-34 FR-02：取消本机问答——路由 = 显式 > 场景预设 > 平台默认，皆无命中 409，不存在本机回落
        if ("local".equalsIgnoreCase(req.agentNodeId())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "CAP-34 起不存在本机问答：agentNodeId=\"local\" 保留值已废除，请指定 runner 节点或留空走平台默认");
        }
        String agentNodeId = firstNonBlank(req.agentNodeId(),
                preset != null ? preset.agentNodeId() : null, platformDefaultNodeId());
        if (agentNodeId == null || agentNodeId.isBlank()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "无可用执行节点：请显式指定执行节点，或配置平台默认节点");
        }

        AgentNodeConnector connector = requireConnector();
        // 场景装配先于注册/launch：绑定资产失效（严格 404）即创建失败，不留半拉子运行时；
        // 装配产物同时入 session 侧缓存，供 runner 凭 manifest 拉包
        ChatContextPreparer.PreparedContext prepared = preparer != null
                ? preparer.prepare(id, req.scenarioCode(), req.message()) : null;
        String launchPrompt = prepared != null ? prepared.renderedPrompt() : req.message();
        ContextManifest manifest = prepared != null ? prepared.manifest() : null;
        // CAP-46 FR-02：绑库时库概览节拼进初始 prompt 前缀，首轮即知挂了哪个库、库里有什么
        if (kbOverview != null) {
            launchPrompt = overviewSection(kbOverview) + launchPrompt;
        }

        RemoteSessionRuntime remoteRt = new RemoteSessionRuntime(id, agentNodeId, connector, eventSaver, listener, settings);
        // 先注册再 launch：ack 之后 runner 事件即刻上行，注册晚于 ack 会丢开头事件
        runtimes.put(id, remoteRt);
        try {
            // kind="chat"：runner 用 <workspaceRoot>/_chat/<sid> 沙箱，无 clone/push 语义
            connector.launch(agentNodeId, new AgentLaunchCommand(
                    id, null, launchPrompt, model, pm, Map.of(), null, "chat", null, manifest));
        } catch (Exception e) {
            runtimes.remove(id);
            if (e instanceof DevMindException de) {
                throw de;
            }
            throw new DevMindException(ErrorCode.CONFLICT, "下发远程问答失败: " + e.getMessage(), e);
        }

        Instant now = Instant.now();
        ChatSessionEntity ent = new ChatSessionEntity();
        ent.setId(id);
        ent.setTitle(titleOf(req.message()));
        ent.setStatus(SessionState.RUNNING.name());
        // CAP-34：新问答恒有执行节点；pid 为本机时代字段，新行恒 null
        ent.setAgentNodeId(agentNodeId);
        ent.setPid(null);
        ent.setModel(model);
        ent.setPermissionMode(pm);
        ent.setScenarioCode(preset != null ? req.scenarioCode().strip() : null);
        ent.setKnowledgeBaseId(req.knowledgeBaseId());
        ent.setExecutor(ChatSessionEntity.EXECUTOR_AGENT);
        ent.setContextManifestJson(prepared != null ? prepared.snapshotJson() : null);
        ent.setInitialPrompt(req.message());
        ent.setCreatedBy(identityService.currentActor());
        ent.setCreatedAt(now);
        ent.setUpdatedAt(now);
        chatRepo.save(ent);

        // 首条提问随 launch 作初始 prompt 下发、agent 回显被解析器跳过——补记 user 事件，开场气泡可见
        ((AbstractSessionRuntime) remoteRt).noteUserMessage(req.message());

        notificationPublisher.publish(NotificationEvent.of("CHAT_STARTED", id, "问答已启动",
                preview(req.message(), 80)));
        return toView(ent, remoteRt.state());
    }

    /**
     * CAP-49：建模型执行体问答——解析端点 → 装运行时 → 落库 → 起首轮。
     * 全程无 launch、无节点通知（本就无节点参与），首轮与后续轮走同一条生成路径。
     */
    private ChatView createModelChat(String id, CreateChatRequest req, KnowledgeRetriever.KbOverview kbOverview) {
        ModelEndpointView endpoint = resolveModelEndpoint(req.modelEndpointId());
        ModelSessionRuntime rt = newModelRuntime(id, endpoint, modelSystemPrompt(kbOverview));
        runtimes.put(id, rt);

        Instant now = Instant.now();
        ChatSessionEntity ent = new ChatSessionEntity();
        ent.setId(id);
        ent.setTitle(titleOf(req.message()));
        ent.setStatus(SessionState.RUNNING.name());
        ent.setAgentNodeId(null);                     // 模型执行体不进节点路由（无节点也能用）
        ent.setExecutor(ChatSessionEntity.EXECUTOR_MODEL);
        ent.setModelEndpointId(endpoint.id());        // 钉住具体端点：默认端点日后被换掉，历史会话身份不漂移
        ent.setModel(endpoint.model());               // 模型身份来自端点，不取 req.model
        ent.setPermissionMode(null);
        ent.setScenarioCode(null);
        ent.setKnowledgeBaseId(req.knowledgeBaseId());
        ent.setInitialPrompt(req.message());
        ent.setCreatedBy(identityService.currentActor());
        ent.setCreatedAt(now);
        ent.setUpdatedAt(now);
        // 先落库再起首轮：首轮立刻会写 RUNNING/WAITING_INPUT 实时状态，行必须先存在
        chatRepo.save(ent);

        rt.startFirstTurn(req.message());

        notificationPublisher.publish(NotificationEvent.of("CHAT_STARTED", id, "问答已启动",
                preview(req.message(), 80)));
        return toView(ent, liveState(ent));
    }

    /** 个人问答列表：当前用户 + 时间倒序，可选状态过滤。 */
    public List<ChatView> list(String status) {
        return chatRepo.findByCreatedByOrderByCreatedAtDesc(identityService.currentActor()).stream()
                .filter(e -> status == null || status.isBlank() || status.equals(e.getStatus()))
                .map(e -> toView(e, liveState(e)))
                .toList();
    }

    public ChatView get(String id) {
        ChatSessionEntity ent = requireOwned(id);
        return toView(ent, liveState(ent));
    }

    public List<SessionEvent> events(String id, long afterSeq) {
        requireOwned(id);
        return eventRepo.findByChatIdAndSeqGreaterThanOrderBySeqAsc(id, afterSeq).stream()
                .map(this::toEvent)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private SessionEvent toEvent(ChatEventEntity e) {
        Map<String, Object> payload = Map.of();
        if (e.getPayload() != null && !e.getPayload().isBlank()) {
            try {
                payload = mapper.readValue(e.getPayload(), Map.class);
            } catch (Exception ex) {
                log.warn("payload 反序列化失败: chat={} seq={} err={}", e.getChatId(), e.getSeq(), ex.getMessage());
            }
        }
        return SessionEvent.of(e.getSeq(), e.getType(), e.getContent(), e.getSource(),
                e.getCreatedAt().toEpochMilli(), payload);
    }

    // ---------------- 交互 ----------------

    public void input(String id, String text) {
        input(id, text, List.of());
    }

    /** CAP-32：注入用户输入（可带图片附件）。附件经 AttachmentContentResolver 解析为 base64 下发 claude。 */
    public void input(String id, String text, List<ImageRef> images) {
        ChatSessionEntity ent = requireOwned(id);
        if (ent.isModel() && images != null && !images.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "模型执行体不支持图片输入：请去掉图片，或改用智能体（Agent）执行体");
        }
        if (ent.isModel()) {
            // 内存里的 runtime 拿的是创建时的 baseUrl/apiKey/model 快照：端点被停用或删除后它会照旧发出去
            // （模型服务还在跑的话甚至"成功"了）——用户刚删掉的东西不该继续被悄悄使用。
            // 故每轮提问都核一次端点，把这种静默失效变成明确的 409。只管提问路径：
            // 读历史（subscribe）与收口（finish）不该因为端点没了就打不开。
            // 注意核的是"端点还在不在"，用的仍是快照——模型身份钉住不漂移；要重读端点（如换了密钥）
            // 走 resume（它会重建 runtime）。
            requirePinnedEndpoint(ent);
        }
        // CAP-46 每轮检索注入对两条执行体同样生效（模型执行体也只是"拼个前缀再发出去"）
        requireOrReattachRuntime(id, ent).injectInput(withKnowledgeContext(ent, text), resolveImages(images));
    }

    /**
     * CAP-46 FR-03 每轮检索注入：会话绑库时按提问内容检索库内分块，非空则包成
     * {@code <knowledge-context>} 前缀拼进用户消息；无命中/未绑库/检索异常一律原样发送。
     * 本路径是 REST 线程（非 WS 事件链），同步远程 embedding 调用安全。
     */
    private String withKnowledgeContext(ChatSessionEntity ent, String text) {
        if (ent.getKnowledgeBaseId() == null || text == null || text.isBlank()) {
            return text;
        }
        KnowledgeRetriever retriever = retrieverProvider.getIfAvailable();
        if (retriever == null) {
            return text;
        }
        List<KnowledgeRetriever.RetrievedChunk> hits;
        try {
            hits = retriever.retrieve(List.of(ent.getKnowledgeBaseId()), text, 0);
        } catch (Exception e) {
            log.warn("问答知识检索失败，按无命中降级原样发送: chat={} kb={} err={}",
                    ent.getId(), ent.getKnowledgeBaseId(), e.toString());
            return text;
        }
        if (hits == null || hits.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder("<knowledge-context>\n");
        for (KnowledgeRetriever.RetrievedChunk hit : hits) {
            sb.append(hit.content()).append("\n（来源：").append(hit.entryName()).append("）\n\n");
        }
        sb.append("</knowledge-context>\n\n").append(text);
        return sb.toString();
    }

    /** 附件引用 → InputImage（base64）；解析失败一律报错，不静默丢图（用户需要知道 claude 没看到图）。 */
    private List<InputImage> resolveImages(List<ImageRef> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        AttachmentContentResolver resolver = attachmentResolverProvider.getIfAvailable();
        if (resolver == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "附件模块未装配，无法发送图片");
        }
        List<InputImage> out = new ArrayList<>();
        for (ImageRef ref : images) {
            if (ref == null || ref.attachmentId() == null || ref.attachmentId().isBlank()) {
                continue;
            }
            AttachmentContentResolver.ResolvedAttachment resolved = resolver.resolve(ref.attachmentId())
                    .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST,
                            "图片附件不存在或不是图片类型: " + ref.attachmentId()));
            out.add(new InputImage(ref.attachmentId(), ref.name(), resolved.contentType(),
                    Base64.getEncoder().encodeToString(resolved.bytes())));
        }
        return out;
    }

    public void authorize(String id, boolean accepted, String scope, String requestId) {
        ChatSessionEntity ent = requireOwned(id);
        if (ent.isModel()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "模型执行体没有授权概念（不会产生授权请求），无法受理授权操作");
        }
        requireRuntime(id).authorize(requestId, accepted, scope);
    }

    public ChatView suspend(String id) {
        ChatSessionEntity ent = requireOwned(id);
        if (ent.isModel()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "模型执行体没有进程与工作区，不支持挂起：请直接终止，或结束当前回答后继续对话");
        }
        SessionHandle rt = requireRuntime(id);
        rt.suspend();
        updateStatus(id, SessionState.SUSPENDED, null);
        return get(id);
    }

    public ChatView resume(String id) {
        ChatSessionEntity ent = requireOwned(id);
        SessionState cur = SessionState.valueOf(ent.getStatus());
        if (cur.isActive()) {
            throw new DevMindException(ErrorCode.CONFLICT, "问答正在运行中，无需恢复");
        }
        // CAP-49：模型执行体的"继续对话"不依赖 CLI 会话记录与节点——重建运行时即可
        if (ent.isModel()) {
            return resumeModelChat(ent);
        }
        // 终态恢复依赖 claude --resume 续接对话历史；无 CLI 会话记录的历史数据只能全新开始，不允许
        if (cur != SessionState.SUSPENDED
                && (ent.getCliSessionId() == null || ent.getCliSessionId().isBlank())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "该问答缺少 CLI 会话记录（历史数据），无法继续对话");
        }
        runtimes.remove(id);

        // CAP-34 FR-02：历史本机问答（agent_node_id 空）不可恢复——本机执行路径已下线
        if (ent.getAgentNodeId() == null || ent.getAgentNodeId().isBlank()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "历史本机问答（无执行节点）不可恢复，请新建问答");
        }
        // 远程恢复：重新下发 launch（runner 侧 _chat/<sid> 幂等复用）
        AgentNodeConnector connector = requireConnector();
        // CAP-33：挂场景的问答恢复时重装配上下文（资产可能已变更）；装配失败降级为无上下文恢复，
        // 不阻塞恢复主链路（场景/资产删除不应让旧问答永远起不来）
        ContextManifest manifest = refreshScenarioContext(ent);
        RemoteSessionRuntime rt = new RemoteSessionRuntime(id, ent.getAgentNodeId(), connector,
                eventSaver, listener, settings);
        runtimes.put(id, rt);
        try {
            connector.launch(ent.getAgentNodeId(), new AgentLaunchCommand(
                    id, null, "", ent.getModel(), ent.getPermissionMode(),
                    Map.of(), null, "chat", null, manifest, ent.getCliSessionId()));
        } catch (Exception e) {
            runtimes.remove(id);
            if (e instanceof DevMindException de) {
                throw de;
            }
            throw new DevMindException(ErrorCode.CONFLICT, "恢复远程问答失败: " + e.getMessage(), e);
        }
        ent.setStatus(SessionState.RUNNING.name());
        ent.setFinishedAt(null);
        ent.setUpdatedAt(Instant.now());
        chatRepo.save(ent);
        return toView(ent, rt.state());
    }

    public ChatView kill(String id) {
        ChatSessionEntity ent = requireOwned(id);
        SessionHandle rt = requireRuntime(id);
        rt.kill();
        // kill 不走 onExit（能力层自己写终态），运行时不会自我摘除——手动摘掉，别让死句柄常驻内存
        runtimes.remove(id);
        updateStatus(id, SessionState.TERMINATED, "已手动终止");
        return toView(chatRepo.findById(id).orElse(ent), SessionState.TERMINATED);
    }

    /** 优雅结束：关 stdin，agent 读完后自然退出 → DONE/FAILED。 */
    public void finish(String id) {
        ChatSessionEntity ent = requireOwned(id);
        requireOrReattachRuntime(id, ent).finish();
    }

    /**
     * CAP-49「停止生成」：中断在跑的模型回合，保留已产出的部分正文与会话（随后可继续提问）。
     *
     * <p>只对模型执行体开放：Agent 执行体没有"中断一轮"的语义（claude 按回合输出），
     * 要停就结束/终止会话，故这里 409 而不是"悄悄转成 finish"。</p>
     */
    public ChatView interrupt(String id) {
        ChatSessionEntity ent = requireOwned(id);
        SessionHandle rt = requireOrReattachRuntime(id, ent);
        if (!(rt instanceof ModelSessionRuntime model)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "只有模型执行体的问答支持中断生成：Agent 执行体的回答请用「结束会话」");
        }
        if (!model.interruptTurn()) {
            throw new DevMindException(ErrorCode.CONFLICT, "当前没有正在生成的回答");
        }
        return toView(chatRepo.findById(id).orElse(ent), SessionState.RUNNING);
    }

    /** 订阅实时事件流，返回回放（环形缓冲快照）。 */
    public List<SessionEvent> subscribe(String id, Consumer<SessionEvent> consumer) {
        ChatSessionEntity ent = requireOwned(id);
        return requireSubscribeRuntime(id, ent).subscribe(consumer);
    }

    public void unsubscribe(String id, Consumer<SessionEvent> consumer) {
        SessionHandle rt = runtimes.get(id);
        if (rt != null) {
            rt.unsubscribe(consumer);
        }
    }

    // ---------------- CAP-54 工作区实时视图（旁路：最新值缓存 + WS 订阅，不入事件流/不落库） ----------------

    /** chatId → 最新工作区快照（进程退出后保留最终态，删除问答时清除）。 */
    private final Map<String, Map<String, Object>> workspaceSnapshots = new ConcurrentHashMap<>();
    /** chatId → 工作区快照订阅者（浏览器 WS /ws/chats/{id} 的 workspace 帧）。 */
    private final Map<String, Set<Consumer<Map<String, Object>>>> workspaceSubs = new ConcurrentHashMap<>();

    /**
     * runner 上行 workspace_status（ChatAgentBridge 路由至此）：认领本模块问答后缓存最新值
     * 并推订阅者。运行时在册按节点匹配；刚退出的尾帧运行时已注销，按 DB 归属节点兜底认领。
     */
    public void onWorkspaceStatus(String nodeId, String sessionId, Map<String, Object> snapshot) {
        SessionHandle h = runtimes.get(sessionId);
        if (!(h instanceof RemoteSessionRuntime r) || !r.nodeId().equals(nodeId)) {
            ChatSessionEntity ent = chatRepo.findById(sessionId).orElse(null);
            if (ent == null || !nodeId.equals(ent.getAgentNodeId())) {
                return; // 非本模块会话（项目会话等）或节点不符——忽略
            }
        }
        workspaceSnapshots.put(sessionId, snapshot);
        Set<Consumer<Map<String, Object>>> subs = workspaceSubs.get(sessionId);
        if (subs != null) {
            for (Consumer<Map<String, Object>> c : subs) {
                try {
                    c.accept(snapshot);
                } catch (Exception e) {
                    log.debug("workspace 快照推送失败: chat={} err={}", sessionId, e.getMessage());
                }
            }
        }
    }

    /** 最新缓存快照（WS 连接建立时补发；无 = runner 未推过/版本过低）。 */
    public Map<String, Object> latestWorkspaceSnapshot(String id) {
        return workspaceSnapshots.get(id);
    }

    public void subscribeWorkspace(String id, Consumer<Map<String, Object>> consumer) {
        workspaceSubs.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(consumer);
    }

    public void unsubscribeWorkspace(String id, Consumer<Map<String, Object>> consumer) {
        Set<Consumer<Map<String, Object>>> subs = workspaceSubs.get(id);
        if (subs != null) {
            subs.remove(consumer);
            if (subs.isEmpty()) {
                workspaceSubs.remove(id, subs);
            }
        }
    }

    /**
     * 工作区只读查询透传（REST → workspace_query 帧 → runner 读盘）。
     * 协议版本门控在 connector 内（老 runner 409 引导升级）；查询失败抛 CONFLICT 带 runner 原因。
     * chat 沙箱无 git，不提供 diff action。
     */
    public Map<String, Object> workspaceQuery(String id, String action, String path) {
        ChatSessionEntity ent = requireOwned(id);
        if (ent.isModel() || ent.getAgentNodeId() == null || ent.getAgentNodeId().isBlank()) {
            throw new DevMindException(ErrorCode.CONFLICT, "该问答无执行节点（模型执行体），工作区视图不可用");
        }
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
        }
        WorkspaceQueryResult r = connector.workspaceQuery(ent.getAgentNodeId(), id, action, null, path);
        if (!r.ok()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    r.error() == null || r.error().isBlank() ? "工作区查询失败" : r.error());
        }
        return r.payload();
    }

    /** 删除问答：杀进程（若在跑）、删事件与记录；远程沙箱由 runner finalizer 负责。 */
    @Transactional
    public void deleteChat(String id) {
        ChatSessionEntity ent = requireOwned(id);
        SessionHandle rt = runtimes.remove(id);
        if (rt != null) {
            rt.unsubscribeAll();
            rt.kill();
        }
        eventRepo.deleteByChatId(id);
        chatRepo.delete(ent);
        workspaceSnapshots.remove(id); // CAP-54：旁路缓存随记录清除
        workspaceSubs.remove(id);
    }

    // ---------------- 启动/关闭 ----------------

    @PostConstruct
    public void restoreOnStartup() {
        // 服务重启后，本机时代的进程已随旧实例消亡：遗留的"活动"状态标记 TERMINATED。
        // CAP-34 FR-04：远程问答（agent_node_id 非空）进程在 runner 侧可能仍存活，不在此判死——
        // 留给 runner 重连后的 hello 对账（onRemoteHello）：清单内 reattach，清单外 FAILED
        List<String> stale = List.of(SessionState.RUNNING.name(), SessionState.WAITING_INPUT.name(),
                SessionState.WAITING_AUTH.name());
        int n = 0;
        int modelStuck = 0;
        for (ChatSessionEntity ent : chatRepo.findAll()) {
            if (!stale.contains(ent.getStatus())) {
                continue;
            }
            // CAP-49 模型执行体（agent_node_id 恒空，不能按上方判据判死）：
            // 空闲的（WAITING_INPUT）原样留着——它没有进程可失去，历史在 DB，下次提问会自动重挂；
            // 生成中的（RUNNING）才是真被打断：这一轮 HTTP 流随旧实例没了，标 TERMINATED 让用户续问
            if (ent.isModel()) {
                if (SessionState.WAITING_INPUT.name().equals(ent.getStatus())) {
                    continue;
                }
                ent.setStatus(SessionState.TERMINATED.name());
                ent.setSummary("服务重启，进行中的生成已中断（可继续对话）");
                ent.setFinishedAt(Instant.now());
                ent.setUpdatedAt(Instant.now());
                chatRepo.save(ent);
                modelStuck++;
                continue;
            }
            if (ent.getAgentNodeId() == null || ent.getAgentNodeId().isBlank()) {
                ent.setStatus(SessionState.TERMINATED.name());
                ent.setSummary("服务重启，问答已终止（进程随旧实例退出）");
                ent.setFinishedAt(Instant.now());
                ent.setUpdatedAt(Instant.now());
                chatRepo.save(ent);
                n++;
            }
        }
        if (n > 0 || modelStuck > 0) {
            log.info("启动恢复完成，{} 个遗留活动问答已标记 TERMINATED（远程问答留待 hello 对账；"
                    + "模型问答 {} 个生成中被打断，空闲的留待懒重挂）", n, modelStuck);
        }
    }

    @PreDestroy
    public void shutdown() {
        for (SessionHandle rt : runtimes.values()) {
            try {
                rt.kill();
            } catch (Exception e) {
                log.warn("关闭时终止问答异常: chat={}", rt.id(), e);
            }
        }
        runtimes.clear();
    }

    // ---------------- CAP-21 远程事件入口（ChatAgentBridge 路由至此；未持有该 id 则 no-op） ----------------

    public void onRemoteEvent(String nodeId, AgentEventFrame frame) {
        SessionHandle h = runtimes.get(frame.sessionId());
        if (h instanceof RemoteSessionRuntime r && r.nodeId().equals(nodeId)) {
            captureCliSessionId(frame);
            r.ingest(frame);
        }
    }

    /** init 事件 payload 带 claude 侧 session_id → 落库（resume 时以 --resume 续接对话历史）。 */
    private void captureCliSessionId(AgentEventFrame frame) {
        if (!"system".equals(frame.type()) || frame.payload() == null) {
            return;
        }
        Object subtype = frame.payload().get("subtype");
        Object cliId = frame.payload().get("sessionId");
        if (!"init".equals(subtype) || !(cliId instanceof String id) || id.isBlank()) {
            return;
        }
        chatRepo.findById(frame.sessionId()).ifPresent(ent -> {
            if (!id.equals(ent.getCliSessionId())) {
                ent.setCliSessionId(id);
                chatRepo.save(ent);
            }
        });
    }

    public void onRemoteExit(String nodeId, String sessionId, int exitCode) {
        SessionHandle h = runtimes.get(sessionId);
        if (h instanceof RemoteSessionRuntime r && r.nodeId().equals(nodeId)) {
            r.handleExit(exitCode);
        }
    }

    public void onRemoteHello(String nodeId, List<String> activeSessionIds) {
        for (SessionHandle h : runtimes.values()) {
            if (h instanceof RemoteSessionRuntime r && r.nodeId().equals(nodeId)) {
                if (activeSessionIds != null && activeSessionIds.contains(r.id())) {
                    r.noteReconnected();
                } else {
                    r.markLost("runner 重连后对账：问答不在存活清单（进程已随 runner 旧实例退出）");
                }
            }
        }
        reconcileFromDb(nodeId, activeSessionIds);
    }

    /**
     * CAP-34 FR-04 服务端重启盲区：内存 runtimes 已丢失，DB 里该节点的活动状态存量问答
     * 按 hello 清单对账——清单内 reattach 重建 RemoteSessionRuntime 挂回；清单外判 FAILED。
     */
    private void reconcileFromDb(String nodeId, List<String> activeSessionIds) {
        List<ChatSessionEntity> stale = chatRepo.findByAgentNodeIdAndStatusIn(nodeId,
                List.of(SessionState.RUNNING.name(), SessionState.WAITING_INPUT.name(),
                        SessionState.WAITING_AUTH.name()));
        for (ChatSessionEntity ent : stale) {
            if (runtimes.containsKey(ent.getId())) {
                continue; // 内存对账已处理
            }
            if (activeSessionIds != null && activeSessionIds.contains(ent.getId())) {
                AgentNodeConnector connector = connectorProvider.getIfAvailable();
                if (connector == null) {
                    continue;
                }
                RemoteSessionRuntime rt = new RemoteSessionRuntime(ent.getId(), nodeId, connector,
                        eventSaver, listener, settings);
                runtimes.put(ent.getId(), rt);
                rt.noteReconnected();
                log.info("服务端重启后对账：问答 {} reattach 到节点 {}", ent.getId(), nodeId);
            } else {
                ent.setStatus(SessionState.FAILED.name());
                ent.setSummary("服务端重启后对账：问答进程已不存在（不在 runner 存活清单）");
                ent.setFinishedAt(Instant.now());
                ent.setUpdatedAt(Instant.now());
                chatRepo.save(ent);
                log.info("服务端重启后对账：问答 {} 判 FAILED（不在节点 {} 存活清单）", ent.getId(), nodeId);
            }
        }
    }

    public void onNodeDisconnected(String nodeId) {
        for (SessionHandle h : runtimes.values()) {
            if (h instanceof RemoteSessionRuntime r && r.nodeId().equals(nodeId)) {
                r.noteDisconnected();
            }
        }
    }

    // ---------------- 内部 ----------------

    /** 归属校验：问答属个人空间，非创建人且非 ADMIN 一律按不存在处理（不暴露存在性）。 */
    private ChatSessionEntity requireOwned(String id) {
        ChatSessionEntity ent = chatRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "问答不存在: " + id));
        String actor = identityService.currentActor();
        if (ent.getCreatedBy() != null && !ent.getCreatedBy().equals(actor) && !isAdmin()) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "问答不存在: " + id);
        }
        return ent;
    }

    private boolean isAdmin() {
        try {
            return identityService.currentUser()
                    .map(u -> UserEntity.ROLE_ADMIN.equals(u.getRole()))
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Agent 执行体配额：只数 Agent 会话（每个都占一个 runner 进程；模型会话空闲时不占任何外部资源）。 */
    private void ensureCapacity() {
        long active = runtimes.values().stream()
                .filter(r -> !(r instanceof ModelSessionRuntime))
                .filter(r -> r.state().isActive())
                .count();
        if (active >= props.getMaxConcurrent()) {
            throw new DevMindException(ErrorCode.TOO_MANY_SESSIONS,
                    "并发问答数已达上限 " + props.getMaxConcurrent());
        }
    }

    /**
     * CAP-49 模型执行体配额：只数<b>正在生成</b>的（空闲的模型问答不该挤占额度——
     * 4 个闲置模型问答把平台卡到无法新建问答，是把额度用错了地方）。
     */
    private void ensureModelCapacity() {
        long generating = runtimes.values().stream()
                .filter(r -> r instanceof ModelSessionRuntime m && m.generating())
                .count();
        if (generating >= props.getMaxConcurrentModel()) {
            throw new DevMindException(ErrorCode.TOO_MANY_SESSIONS,
                    "并发模型问答数已达上限 " + props.getMaxConcurrentModel());
        }
    }

    private AgentNodeConnector requireConnector() {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "远程 agent 模块未装配，无法创建远程问答");
        }
        return connector;
    }

    private String platformDefaultNodeId() {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        return connector != null ? connector.defaultNodeId() : null;
    }

    // ---------------- CAP-49 模型执行体 ----------------

    /** 执行体解析：空/未传 = AGENT（历史行同口径）；未知值 400，不静默当 AGENT 用。 */
    private static String executorOf(String requested) {
        if (!notBlank(requested)) {
            return ChatSessionEntity.EXECUTOR_AGENT;
        }
        if (ChatSessionEntity.EXECUTOR_AGENT.equalsIgnoreCase(requested)) {
            return ChatSessionEntity.EXECUTOR_AGENT;
        }
        if (ChatSessionEntity.EXECUTOR_MODEL.equalsIgnoreCase(requested)) {
            return ChatSessionEntity.EXECUTOR_MODEL;
        }
        throw new DevMindException(ErrorCode.BAD_REQUEST,
                "未知执行体: " + requested + "（可选 AGENT / MODEL）");
    }

    /**
     * 模型执行体下 Agent 专有字段一律 400，不静默忽略：用户以为"选了场景"而模型执行体拿不到
     * 场景资产（资产靠 runner manifest 物化），是最坏的一种失败——上下文悄悄没了。
     */
    private static void rejectModelConflicts(CreateChatRequest req) {
        if (notBlank(req.scenarioCode())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "模型执行体不支持场景问答（场景资产需 runner 物化）：请不选场景，或改用 Agent 执行体");
        }
        if (notBlank(req.agentNodeId())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "模型执行体不使用执行节点（无节点也可用）：请清空 agentNodeId");
        }
        if (notBlank(req.permissionMode())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "模型执行体无权限模式概念（不执行工具）：请清空 permissionMode");
        }
        if (notBlank(req.model())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "模型执行体的模型名来自所选端点：请清空 model（指定 CLI 模型请用 Agent 执行体）");
        }
    }

    /** 创建路径解析端点：显式 id 校验（存在/active/CHAT 类型），未给走平台默认 CHAT 端点（无则 409）。 */
    private ModelEndpointView resolveModelEndpoint(Long endpointId) {
        ModelEndpointProvider provider = requireEndpointProvider();
        if (endpointId != null) {
            ModelEndpointView ep = provider.activeEndpoint(endpointId)
                    .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST,
                            "模型端点不存在或已停用: " + endpointId));
            if (!ep.chat()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "该端点不是通用对话端点（kind=" + ep.kind() + "），不能用于问答");
            }
            return requireCompleteEndpoint(ep);
        }
        // 显式未给 = 跟随平台默认；没配默认就是没配（不回落向量端点，那是必坏的组合）
        return requireCompleteEndpoint(provider.defaultEndpoint(ModelEndpointView.KIND_CHAT)
                .orElseThrow(() -> new DevMindException(ErrorCode.CONFLICT,
                        "未配置通用对话端点：请在「后台 → 模型接入」登记 kind=CHAT 的端点并设为默认，"
                                + "或在新建时选择端点")));
    }

    /**
     * 重挂路径必须用会话当初<b>钉住</b>的那个端点：默认端点日后被换掉/删除，不该让历史会话
     * 悄悄换一个模型继续说话（对话历史与新模型不匹配，错误比 409 隐蔽得多）。
     */
    private ModelEndpointView requirePinnedEndpoint(ChatSessionEntity ent) {
        ModelEndpointProvider provider = requireEndpointProvider();
        Long endpointId = ent.getModelEndpointId();
        if (endpointId == null) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "该问答未记录模型端点（历史数据），无法继续对话：请新建问答");
        }
        return requireCompleteEndpoint(provider.activeEndpoint(endpointId)
                .orElseThrow(() -> new DevMindException(ErrorCode.CONFLICT,
                        "该问答绑定的模型端点已停用或删除（#" + endpointId + "），无法继续对话："
                                + "请在后台恢复该端点，或新建问答")));
    }

    /** mock provider 之类没配 baseUrl/model 的端点在这里拦住——放过去只会在调用时报难懂的错。 */
    private static ModelEndpointView requireCompleteEndpoint(ModelEndpointView ep) {
        if (!notBlank(ep.baseUrl()) || !notBlank(ep.model())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "模型端点未配置完整（baseUrl/model 为空），无法用于问答: " + ep.display());
        }
        return ep;
    }

    private ModelEndpointProvider requireEndpointProvider() {
        ModelEndpointProvider provider = endpointProvider.getIfAvailable();
        if (provider == null) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "模型模块未装配，无法使用模型执行体：请改用 Agent 执行体，或启用 devmind-model 模块");
        }
        return provider;
    }

    /** 装配模型运行时（创建与懒重挂共用一段：两边参数一致，才不会出现"重挂后行为不一样"）。 */
    private ModelSessionRuntime newModelRuntime(String id, ModelEndpointView ep, String systemPrompt) {
        ModelSessionRuntime.StreamTuning tuning = new ModelSessionRuntime.StreamTuning(
                props.getStreamFlushMs(), props.getStreamFlushChars(), props.getAnswerMaxChars());
        return new ModelSessionRuntime(id,
                new ModelSessionRuntime.ModelTarget(ep.baseUrl(), ep.apiKey(), ep.model(), ep.timeoutSeconds()),
                new ChatModelTurnSupplier(id, systemPrompt, eventRepo),
                tuning, eventSaver, listener, settings);
    }

    /** 模型执行体的 system：固定提示 + 绑库时的库概览（与 Agent 的启动注入同一段文案，不许两份漂移）。 */
    private static String modelSystemPrompt(KnowledgeRetriever.KbOverview kb) {
        if (kb == null) {
            return MODEL_SYSTEM_PROMPT;
        }
        return MODEL_SYSTEM_PROMPT + "\n\n" + overviewSection(kb)
                + "用户消息前缀里的 <knowledge-context> 是自动检索到的参考资料，据此作答即可，"
                + "不要在回答里复述出处标记。\n";
    }

    /** 重挂时的库概览：库被删/归档/检索异常就按无库继续（与 CAP-46 的降级口径一致，不阻塞对话）。 */
    private KnowledgeRetriever.KbOverview overviewOrNull(Long kbId) {
        if (kbId == null) {
            return null;
        }
        KnowledgeRetriever retriever = retrieverProvider.getIfAvailable();
        if (retriever == null) {
            return null;
        }
        try {
            return retriever.overview(kbId).orElse(null);
        } catch (Exception e) {
            log.warn("知识库概览加载失败，按无库继续: kb={} err={}", kbId, e.toString());
            return null;
        }
    }

    /**
     * 懒重挂（CAP-49）：运行时不在内存时按需重建。
     *
     * <p>模型执行体没有进程也没有节点，重建 = 重新装一个"HTTP 回合入口"：历史在 chat_events、
     * 端点按会话钉住的那份解析。<b>不写 DB</b>——重建后的状态变化由真正发生的动作落库
     * （提问 → RUNNING，收尾 → WAITING_INPUT / 终态），凭空写 RUNNING 会把已结束的会话显示成运行中。</p>
     */
    private ModelSessionRuntime reattachModelRuntime(ChatSessionEntity ent) {
        ModelEndpointView ep = requirePinnedEndpoint(ent);
        ModelSessionRuntime rt = newModelRuntime(ent.getId(), ep,
                modelSystemPrompt(overviewOrNull(ent.getKnowledgeBaseId())));
        runtimes.put(ent.getId(), rt);
        log.info("模型问答懒重挂: chat={} endpoint=#{} model={}", ent.getId(), ep.id(), ep.model());
        return rt;
    }

    /** 输入/中断/结束路径：模型执行体一律可按需重挂（历史在 DB，重挂即复活）。 */
    private SessionHandle requireOrReattachRuntime(String id, ChatSessionEntity ent) {
        SessionHandle rt = runtimes.get(id);
        if (rt != null) {
            return rt;
        }
        if (ent.isModel()) {
            return reattachModelRuntime(ent);
        }
        throw new DevMindException(ErrorCode.NOT_FOUND, "问答不在运行中: " + id);
    }

    /** 订阅路径：只对活动状态重挂——终态会话不该因为"被打开过"就在内存里常驻一个运行时。 */
    private SessionHandle requireSubscribeRuntime(String id, ChatSessionEntity ent) {
        SessionHandle rt = runtimes.get(id);
        if (rt != null) {
            return rt;
        }
        if (ent.isModel() && LIVE_STATUSES.contains(ent.getStatus())) {
            return reattachModelRuntime(ent);
        }
        throw new DevMindException(ErrorCode.NOT_FOUND, "问答不在运行中: " + id);
    }

    /** CAP-49：模型执行体的"继续对话"——无 cliSessionId、无节点、无 launch，重建运行时即可。 */
    private ChatView resumeModelChat(ChatSessionEntity ent) {
        runtimes.remove(ent.getId());
        reattachModelRuntime(ent); // 端点已停用/删除 → 409 并说明
        ent.setStatus(SessionState.RUNNING.name());
        ent.setFinishedAt(null);
        ent.setUpdatedAt(Instant.now());
        chatRepo.save(ent);
        return toView(ent, SessionState.RUNNING);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** CAP-33：挂场景问答的重装配（resume 用）；失败/无产出 = null（降级无上下文）。 */
    private ContextManifest refreshScenarioContext(ChatSessionEntity ent) {
        if (ent.getScenarioCode() == null || ent.getScenarioCode().isBlank()) {
            return null;
        }
        ChatContextPreparer preparer = contextPreparerProvider.getIfAvailable();
        if (preparer == null) {
            return null;
        }
        try {
            ChatContextPreparer.PreparedContext prepared = preparer.prepare(
                    ent.getId(), ent.getScenarioCode(), ent.getInitialPrompt());
            if (prepared != null && prepared.manifest() != null) {
                ent.setContextManifestJson(prepared.snapshotJson()); // 快照随 resume 刷新
                return prepared.manifest();
            }
        } catch (Exception e) {
            log.warn("问答恢复时场景上下文重装配失败，降级无上下文恢复: chat={} scenario={} err={}",
                    ent.getId(), ent.getScenarioCode(), e.getMessage());
        }
        return null;
    }

    /** CAP-33 FR-07：已注入上下文快照（未挂场景/无快照 404）。 */
    public String contextManifest(String id) {
        ChatSessionEntity ent = requireOwned(id);
        if (ent.getContextManifestJson() == null || ent.getContextManifestJson().isBlank()) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "该问答无上下文快照（未挂场景或装配为空）: " + id);
        }
        return ent.getContextManifestJson();
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }

    private SessionHandle requireRuntime(String id) {
        SessionHandle rt = runtimes.get(id);
        if (rt == null) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "问答不在运行中: " + id);
        }
        return rt;
    }

    /**
     * 实时状态：内存运行时优先，无运行时回落持久化状态。
     *
     * <p>CAP-49：模型运行时是"按需重建"的，新装好的运行时初始态即 RUNNING，直接采信会把
     * 一场早已结束（甚至被终止）的问答显示成"运行中"。故空闲（未在生成）时一律以 DB 为准——
     * <b>模型执行体的 DB 状态由运行时自己写</b>（每轮 2 次，见 persistModelLiveState）。</p>
     */
    private SessionState liveState(ChatSessionEntity ent) {
        SessionHandle rt = runtimes.get(ent.getId());
        if (rt instanceof ModelSessionRuntime m && !m.generating()) {
            return SessionState.valueOf(ent.getStatus());
        }
        return rt != null ? rt.state() : SessionState.valueOf(ent.getStatus());
    }

    /** CAP-46 FR-02：库概览节（启动注入初始 prompt 前缀）；描述截断防爆。 */
    private static String overviewSection(KnowledgeRetriever.KbOverview kb) {
        StringBuilder sb = new StringBuilder();
        sb.append("<knowledge-base>\n本会话已绑定知识库「").append(kb.name()).append("」");
        if (kb.injectMode() != null && !kb.injectMode().isBlank()) {
            sb.append("（注入模式：").append(kb.injectMode()).append("）");
        }
        sb.append('\n');
        if (kb.description() != null && !kb.description().isBlank()) {
            String desc = kb.description().replace('\n', ' ').strip();
            sb.append("库描述：").append(desc.length() <= 200 ? desc : desc.substring(0, 200) + "…").append('\n');
        }
        if (kb.entryNames() != null && !kb.entryNames().isEmpty()) {
            sb.append("库内条目（").append(kb.entryNames().size()).append(" 条）：\n");
            for (String name : kb.entryNames()) {
                sb.append("- ").append(name).append('\n');
            }
        }
        // 注意：概览文本刻意不出现字面 <knowledge-context>——初始 prompt 回显进事件流后，
        // 「本轮是否注入了检索块」的断言（单测/E2E）靠的是该标签只在真正注入时出现
        sb.append("后续每轮提问会按内容自动检索该库并附上相关分块供参考。\n</knowledge-base>\n\n");
        return sb.toString();
    }

    private ChatView toView(ChatSessionEntity ent, SessionState state) {
        ModelEndpointView endpoint = endpointViewOrNull(ent.getModelEndpointId());
        return new ChatView(ent.getId(), ent.getTitle(), state.name(), state, ent.getPid(),
                ent.getModel(), ent.getPermissionMode(), ent.getSummary(), ent.getAgentNodeId(),
                ent.getCreatedBy(), ent.getCreatedAt(), ent.getUpdatedAt(), ent.getFinishedAt(),
                ent.getKnowledgeBaseId(),
                ent.getExecutor() == null ? ChatSessionEntity.EXECUTOR_AGENT : ent.getExecutor(),
                ent.getModelEndpointId(),
                // 端点被停用/删除后名字拿不到（视图仍可看历史；要继续提问会 409 并说明原因）
                endpoint == null ? null : endpoint.display(),
                endpoint == null ? null : endpoint.model());
    }

    /** 端点视图（视图层只用来显示名字与模型名；拿不到 = null，不影响会话本身可读）。 */
    private ModelEndpointView endpointViewOrNull(Long endpointId) {
        if (endpointId == null) {
            return null;
        }
        ModelEndpointProvider provider = endpointProvider.getIfAvailable();
        return provider == null ? null : provider.activeEndpoint(endpointId).orElse(null);
    }

    private void updateStatus(String id, SessionState st, String summary) {
        chatRepo.findById(id).ifPresent(ent -> {
            ent.setStatus(st.name());
            if (summary != null) {
                ent.setSummary(summary);
            }
            if (st == SessionState.TERMINATED || st == SessionState.SUSPENDED) {
                ent.setFinishedAt(Instant.now());
            }
            ent.setUpdatedAt(Instant.now());
            chatRepo.save(ent);
        });
    }

    /** 标题 = 首条消息前 30 字符（去换行）。 */
    private static String titleOf(String message) {
        String one = message.replace('\n', ' ').replace('\r', ' ').strip();
        return one.length() <= 30 ? one : one.substring(0, 30) + "…";
    }

    private String shortId() {
        String base = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(base.charAt(ThreadLocalRandom.current().nextInt(base.length())));
        }
        return sb.toString();
    }

    private String preview(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }
}
