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
import com.devmind.common.agent.exec.ContextManifest;
import com.devmind.common.agent.runtime.AbstractSessionRuntime;
import com.devmind.common.agent.runtime.RemoteSessionRuntime;
import com.devmind.common.agent.runtime.RuntimeListener;
import com.devmind.common.agent.runtime.RuntimeSettings;
import com.devmind.common.agent.runtime.SessionHandle;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.common.attachment.AttachmentContentResolver;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
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

    /** 运行中问答注册表（本地/远程统一句柄）。 */
    private final Map<String, SessionHandle> runtimes = new ConcurrentHashMap<>();

    public ChatManagerService(IdentityService identityService,
                              NotificationPublisher notificationPublisher,
                              ChatSessionRepository chatRepo,
                              ChatEventRepository eventRepo,
                              ChatEventSaver eventSaver,
                              ChatProperties props,
                              ObjectMapper mapper,
                              ObjectProvider<AgentNodeConnector> connectorProvider,
                              ObjectProvider<AttachmentContentResolver> attachmentResolverProvider,
                              ObjectProvider<ChatContextPreparer> contextPreparerProvider) {
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
        this.settings = props.toRuntimeSettings();
    }

    private final RuntimeListener listener = new RuntimeListener() {
        @Override
        public void onStateChange(String sessionId, SessionState state, SessionEvent stateEvent) {
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

    // ---------------- 创建 / 生命周期 ----------------

    public ChatView create(CreateChatRequest req) {
        ensureCapacity();
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
        requireOwned(id);
        requireRuntime(id).injectInput(text, resolveImages(images));
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
        requireOwned(id);
        requireRuntime(id).authorize(requestId, accepted, scope);
    }

    public ChatView suspend(String id) {
        requireOwned(id);
        SessionHandle rt = requireRuntime(id);
        rt.suspend();
        updateStatus(id, SessionState.SUSPENDED, null);
        return get(id);
    }

    public ChatView resume(String id) {
        ChatSessionEntity ent = requireOwned(id);
        if (!SessionState.SUSPENDED.name().equals(ent.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "只有 SUSPENDED 问答可以恢复");
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
                    Map.of(), null, "chat", null, manifest));
        } catch (Exception e) {
            runtimes.remove(id);
            if (e instanceof DevMindException de) {
                throw de;
            }
            throw new DevMindException(ErrorCode.CONFLICT, "恢复远程问答失败: " + e.getMessage(), e);
        }
        ent.setStatus(SessionState.RUNNING.name());
        ent.setUpdatedAt(Instant.now());
        chatRepo.save(ent);
        return toView(ent, rt.state());
    }

    public ChatView kill(String id) {
        requireOwned(id);
        SessionHandle rt = requireRuntime(id);
        rt.kill();
        updateStatus(id, SessionState.TERMINATED, "已手动终止");
        return get(id);
    }

    /** 优雅结束：关 stdin，agent 读完后自然退出 → DONE/FAILED。 */
    public void finish(String id) {
        requireOwned(id);
        requireRuntime(id).finish();
    }

    /** 订阅实时事件流，返回回放（环形缓冲快照）。 */
    public List<SessionEvent> subscribe(String id, Consumer<SessionEvent> consumer) {
        return requireRuntime(id).subscribe(consumer);
    }

    public void unsubscribe(String id, Consumer<SessionEvent> consumer) {
        SessionHandle rt = runtimes.get(id);
        if (rt != null) {
            rt.unsubscribe(consumer);
        }
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
        for (ChatSessionEntity ent : chatRepo.findAll()) {
            if (stale.contains(ent.getStatus())
                    && (ent.getAgentNodeId() == null || ent.getAgentNodeId().isBlank())) {
                ent.setStatus(SessionState.TERMINATED.name());
                ent.setSummary("服务重启，问答已终止（进程随旧实例退出）");
                ent.setFinishedAt(Instant.now());
                ent.setUpdatedAt(Instant.now());
                chatRepo.save(ent);
                n++;
            }
        }
        if (n > 0) {
            log.info("启动恢复完成，{} 个遗留活动问答已标记 TERMINATED（远程问答留待 hello 对账）", n);
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
            r.ingest(frame);
        }
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

    private void ensureCapacity() {
        long active = runtimes.values().stream().filter(r -> r.state().isActive()).count();
        if (active >= props.getMaxConcurrent()) {
            throw new DevMindException(ErrorCode.TOO_MANY_SESSIONS,
                    "并发问答数已达上限 " + props.getMaxConcurrent());
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

    private SessionState liveState(ChatSessionEntity ent) {
        SessionHandle rt = runtimes.get(ent.getId());
        return rt != null ? rt.state() : SessionState.valueOf(ent.getStatus());
    }

    private ChatView toView(ChatSessionEntity ent, SessionState state) {
        return new ChatView(ent.getId(), ent.getTitle(), state.name(), state, ent.getPid(),
                ent.getModel(), ent.getPermissionMode(), ent.getSummary(), ent.getAgentNodeId(),
                ent.getCreatedBy(), ent.getCreatedAt(), ent.getUpdatedAt(), ent.getFinishedAt());
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
