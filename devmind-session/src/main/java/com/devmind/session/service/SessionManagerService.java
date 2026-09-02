package com.devmind.session.service;

import com.devmind.common.agent.AgentEventFrame;
import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.auth.IdentityService;
import com.devmind.common.notification.NotificationEvent;
import com.devmind.knowledge.KnowledgeInjector;
import com.devmind.notification.NotificationPublisher;
import com.devmind.project.WorktreeManager;
import com.devmind.project.workspace.Workspace;
import com.devmind.project.workspace.WorkspaceService;
import com.devmind.project.RequirementService;
import com.devmind.project.WorkItemService;
import com.devmind.project.model.Project;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.project.ProjectService;import com.devmind.session.config.SessionProperties;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.SessionView;
import com.devmind.session.dto.TemplateView;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionEvent;
import com.devmind.session.model.SessionEventEntity;
import com.devmind.session.model.SessionState;
import com.devmind.session.model.SessionTemplateEntity;
import com.devmind.session.repo.SessionEventRepository;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.repo.SessionTemplateRepository;
import org.springframework.transaction.annotation.Transactional;
import com.devmind.session.runtime.CliEventParser;
import com.devmind.session.runtime.RemoteSessionRuntime;
import com.devmind.session.runtime.RuntimeListener;
import com.devmind.session.runtime.SessionEventSaver;
import com.devmind.session.runtime.SessionExecutor;
import com.devmind.session.runtime.SessionHandle;
import com.devmind.session.runtime.SessionRuntime;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 会话生命周期入口：create/list/get/events/input/authorize/suspend/resume/kill/diff/worktree/模板。
 * 持有运行时注册表，协调 Worktree/Injector/Executor/事件落库/通知。
 */
@Service
public class SessionManagerService {

    private static final Logger log = LoggerFactory.getLogger(SessionManagerService.class);

    private final IdentityService identityService;
    private final ProjectService projectService;
    private final WorkItemService workItemService;
    private final RequirementService requirementService;
    private final WorktreeManager worktreeManager;
    private final WorkspaceService workspaceService;
    private final KnowledgeInjector knowledgeInjector;
    private final NotificationPublisher notificationPublisher;
    private final DomainEventPublisher eventPublisher;
    private final SessionRepository sessionRepo;
    private final SessionEventRepository eventRepo;
    private final SessionTemplateRepository templateRepo;
    private final SessionEventSaver eventSaver;
    private final SessionProperties props;
    private final ObjectMapper mapper;
    private final CliEventParser parser;
    private final Collection<SessionExecutor> executors;
    /** CAP-21：远程节点连接（devmind-agent 装配时可用；ObjectProvider 探测防循环依赖） */
    private final ObjectProvider<AgentNodeConnector> connectorProvider;

    /** 运行中会话注册表（本地/远程统一句柄）。 */
    private final Map<String, SessionHandle> runtimes = new ConcurrentHashMap<>();

    public SessionManagerService(IdentityService identityService,
                                 ProjectService projectService,
                                 WorkItemService workItemService,
                                 RequirementService requirementService,
                                 WorktreeManager worktreeManager,
                                 WorkspaceService workspaceService,
                                 KnowledgeInjector knowledgeInjector,
                                 NotificationPublisher notificationPublisher,
                                 DomainEventPublisher eventPublisher,
                                 SessionRepository sessionRepo,
                                 SessionEventRepository eventRepo,
                                 SessionTemplateRepository templateRepo,
                                 SessionEventSaver eventSaver,
                                 SessionProperties props,
                                 ObjectMapper mapper,
                                 CliEventParser parser,
                                 Collection<SessionExecutor> executors,
                                 ObjectProvider<AgentNodeConnector> connectorProvider) {
        this.identityService = identityService;
        this.projectService = projectService;
        this.workItemService = workItemService;
        this.requirementService = requirementService;
        this.worktreeManager = worktreeManager;
        this.workspaceService = workspaceService;
        this.knowledgeInjector = knowledgeInjector;
        this.notificationPublisher = notificationPublisher;
        this.eventPublisher = eventPublisher;
        this.sessionRepo = sessionRepo;
        this.eventRepo = eventRepo;
        this.templateRepo = templateRepo;
        this.eventSaver = eventSaver;
        this.props = props;
        this.mapper = mapper;
        this.parser = parser;
        this.executors = executors;
        this.connectorProvider = connectorProvider;
    }

    private final RuntimeListener listener = new RuntimeListener() {
        @Override
        public void onStateChange(String sessionId, SessionState state, SessionEvent stateEvent) {
            // DONE/FAILED 不再走旧通知通道：onExit 发布 session.completed 领域事件，由统一监听器分级路由
            switch (state) {
                case WAITING_AUTH -> notificationPublisher.publish(NotificationEvent.of(
                        "WAITING_AUTH", sessionId, "会话需要授权", stateEvent.content()));
                case WAITING_INPUT -> notificationPublisher.publish(NotificationEvent.of(
                        "WAITING_INPUT", sessionId, "会话在等待你的输入", stateEvent.content()));
                default -> { }
            }
        }

        @Override
        public void onExit(String sessionId, int exitCode, boolean success, String summary) {
            runtimes.remove(sessionId);
            sessionRepo.findById(sessionId).ifPresent(ent -> {
                ent.setStatus((success ? SessionState.DONE : SessionState.FAILED).name());
                ent.setSummary(summary == null || summary.isBlank() ? null : summary);
                ent.setFinishedAt(Instant.now());
                ent.setUpdatedAt(Instant.now());
                sessionRepo.save(ent);
                // 统一事件总线：会话结束广播（CAP-14 流程引擎据此推进需求主流程；通知走 DomainEventNotificationListener）
                eventPublisher.publish(SimpleDomainEvent.of("session.completed", ent.getProjectId(),
                        ent.getWorkItemId(), ent.getCreatedBy(),
                        "会话 " + sessionId + (success ? " 完成" : " 失败")
                                + (ent.getRequirementId() != null ? "（需求 " + ent.getRequirementId() + "）" : ""),
                        "SESSION", sessionId, success));
            });
        }
    };

    // ---------------- 创建 / 生命周期 ----------------

    public SessionView create(CreateSessionRequest req) {
        // CAP-13 关联约定：workItemId/requirementId 与 projectId 不一致时报错；projectId 空时反推
        WorkItemEntity workItem = null;
        RequirementEntity requirement = null;
        String projectId = req.projectId();
        if (req.workItemId() != null && !req.workItemId().isBlank()) {
            workItem = workItemService.requireById(req.workItemId());
            requirement = requirementService.requireById(workItem.getRequirementId());
            if (req.requirementId() != null && !req.requirementId().isBlank()
                    && !req.requirementId().equals(requirement.getId())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "工作单元 " + req.workItemId() + " 不属于需求 " + req.requirementId());
            }
            if (projectId == null || projectId.isBlank()) {
                projectId = workItem.getProjectId();
            } else if (!projectId.equals(workItem.getProjectId())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "工作单元 " + req.workItemId() + " 不属于项目 " + projectId);
            }
        } else if (req.requirementId() != null && !req.requirementId().isBlank()) {
            // 分析型会话：直挂需求，不算 Work Item
            requirement = requirementService.requireById(req.requirementId());
            if (projectId == null || projectId.isBlank()) {
                projectId = requirement.getProjectId();
            } else if (!projectId.equals(requirement.getProjectId())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "需求 " + req.requirementId() + " 不属于项目 " + projectId);
            }
        }
        Project project = resolveProject(projectId);
        String id = shortId();
        String baseBranch = req.baseBranch() != null && !req.baseBranch().isBlank()
                ? req.baseBranch()
                : (project != null ? project.baseBranch() : "");
        ensureCapacity();

        String taskSpec = req.taskSpec();
        if (req.templateCode() != null && !req.templateCode().isBlank()) {
            taskSpec = renderTemplate(req.templateCode(), req.taskSpec(), project);
        }

        // CAP-21：指定执行节点 = 远程会话——工作目录在节点侧（runner 项目路径映射），
        // 服务端不建 worktree、不做知识注入（知识注入依赖本地文件系统，远程暂不支持）
        boolean remote = req.agentNodeId() != null && !req.agentNodeId().isBlank();

        // P1-3：会话只面向 Workspace 接口，本地实现为 git worktree（远程/容器预留）
        Workspace workspace = null;
        if (!remote && project != null) {
            workspace = workspaceService.prepareSessionWorkspace(project, id);
        }
        Path worktree = workspace != null ? workspace.path() : null;
        if (!remote && project != null && worktree != null) {
            try {
                knowledgeInjector.apply(worktree.toString(), project, taskSpec);
            } catch (Exception e) {
                log.warn("知识注入失败(不阻塞会话): session={} err={}", id, e.getMessage());
            }
        }

        String model = req.model() != null && !req.model().isBlank() ? req.model() : props.getModel();
        String pm = req.permissionMode() != null && !req.permissionMode().isBlank()
                ? req.permissionMode() : props.getPermissionMode();

        Process proc = null;
        RemoteSessionRuntime remoteRt = null;
        if (remote) {
            AgentNodeConnector connector = requireConnector();
            remoteRt = new RemoteSessionRuntime(id, req.agentNodeId(), connector, eventSaver, listener, props);
            // 先注册再 launch：ack 之后 runner 事件即刻上行，注册晚于 ack 会丢开头事件
            runtimes.put(id, remoteRt);
            try {
                connector.launch(req.agentNodeId(), new AgentLaunchCommand(
                        id, project != null ? project.id() : null, taskSpec, model, pm));
            } catch (Exception e) {
                runtimes.remove(id);
                if (e instanceof DevMindException de) {
                    throw de;
                }
                throw new DevMindException(ErrorCode.CONFLICT, "下发远程会话失败: " + e.getMessage(), e);
            }
        } else {
            SessionExecutor executor = resolveExecutor();
            try {
                proc = executor.launch(new SessionExecutor.LaunchContext(id, worktree, taskSpec, model, pm));
            } catch (IOException e) {
                if (workspace != null) {
                    workspace.cleanup();
                }
                throw new DevMindException(ErrorCode.INTERNAL, "启动执行器失败: " + e.getMessage(), e);
            }
        }

        Instant now = Instant.now();
        SessionEntity ent = new SessionEntity();
        ent.setId(id);
        ent.setProjectId(project != null ? project.id() : null);
        ent.setWorkItemId(workItem != null ? workItem.getId() : null);
        ent.setRequirementId(requirement != null ? requirement.getId() : null);
        ent.setTaskSpec(req.taskSpec());
        ent.setBaseBranch(baseBranch);
        ent.setStatus(SessionState.RUNNING.name());
        ent.setWorktreePath(worktree != null ? worktree.toString() : null);
        ent.setAgentNodeId(remote ? req.agentNodeId() : null);
        ent.setPid(proc != null ? proc.pid() : null);
        ent.setModel(model);
        ent.setCreatedBy(identityService.currentActor());
        ent.setCreatedAt(now);
        ent.setUpdatedAt(now);
        sessionRepo.save(ent);

        SessionHandle handle;
        if (remote) {
            handle = remoteRt;
        } else {
            SessionRuntime rt = new SessionRuntime(id, proc, mapper, parser, eventSaver, listener, props);
            runtimes.put(id, rt);
            rt.start();
            handle = rt;
        }

        notificationPublisher.publish(NotificationEvent.of("SESSION_STARTED", id, "会话已启动",
                preview(taskSpec, 80)));
        return toView(ent, handle.state());
    }

    public List<SessionView> list(String status, String projectId, String workItemId, String requirementId) {
        List<SessionEntity> entities;
        if (workItemId != null && !workItemId.isBlank()) {
            entities = sessionRepo.findByWorkItemIdOrderByCreatedAtDesc(workItemId);
        } else if (requirementId != null && !requirementId.isBlank()) {
            entities = sessionRepo.findByRequirementIdOrderByCreatedAtDesc(requirementId);
        } else if (projectId != null && !projectId.isBlank()) {
            entities = sessionRepo.findByProjectIdOrderByCreatedAtDesc(projectId);
        } else if (status != null && !status.isBlank()) {
            entities = sessionRepo.findByStatusOrderByCreatedAtDesc(status);
        } else {
            entities = new ArrayList<>(sessionRepo.findAll());
            entities.sort(Comparator.comparing(SessionEntity::getCreatedAt).reversed());
        }
        return entities.stream().map(e -> toView(e, liveState(e))).toList();
    }

    public SessionView get(String id) {
        SessionEntity ent = requireEntity(id);
        return toView(ent, liveState(ent));
    }

    public List<SessionEvent> events(String id, long afterSeq) {
        SessionEntity ent = requireEntity(id);
        return eventRepo.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(id, afterSeq).stream()
                .map(this::toEvent)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private SessionEvent toEvent(SessionEventEntity e) {
        Map<String, Object> payload = Map.of();
        if (e.getPayload() != null && !e.getPayload().isBlank()) {
            try {
                payload = mapper.readValue(e.getPayload(), Map.class);
            } catch (Exception ex) {
                log.warn("payload 反序列化失败: session={} seq={} err={}", e.getSessionId(), e.getSeq(), ex.getMessage());
            }
        }
        return SessionEvent.of(e.getSeq(), e.getType(), e.getContent(), e.getSource(),
                e.getCreatedAt().toEpochMilli(), payload);
    }

    // ---------------- 交互 ----------------

    public void input(String id, String text) {
        SessionHandle rt = requireRuntime(id);
        rt.injectInput(text);
    }

    public void authorize(String id, boolean accepted, String scope, String requestId) {
        SessionHandle rt = requireRuntime(id);
        rt.authorize(requestId, accepted, scope);
    }

    public SessionView suspend(String id) {
        SessionHandle rt = requireRuntime(id);
        rt.suspend();
        updateStatus(id, SessionState.SUSPENDED, null);
        return get(id);
    }

    public SessionView resume(String id) {
        SessionEntity ent = requireEntity(id);
        if (!SessionState.SUSPENDED.name().equals(ent.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "只有 SUSPENDED 会话可以恢复");
        }
        runtimes.remove(id);

        // CAP-21 远程会话恢复：重新向节点下发 launch（workdir 仍由 runner 项目路径映射解析）
        if (ent.getAgentNodeId() != null && !ent.getAgentNodeId().isBlank()) {
            AgentNodeConnector connector = requireConnector();
            RemoteSessionRuntime rt = new RemoteSessionRuntime(id, ent.getAgentNodeId(), connector,
                    eventSaver, listener, props);
            runtimes.put(id, rt);
            try {
                connector.launch(ent.getAgentNodeId(), new AgentLaunchCommand(
                        id, ent.getProjectId(), ent.getTaskSpec(), ent.getModel(), props.getPermissionMode()));
            } catch (Exception e) {
                runtimes.remove(id);
                if (e instanceof DevMindException de) {
                    throw de;
                }
                throw new DevMindException(ErrorCode.CONFLICT, "恢复远程会话失败: " + e.getMessage(), e);
            }
            ent.setStatus(SessionState.RUNNING.name());
            ent.setUpdatedAt(Instant.now());
            sessionRepo.save(ent);
            return toView(ent, rt.state());
        }

        Project project = resolveProject(ent.getProjectId());
        Path worktree = ent.getWorktreePath() != null && !ent.getWorktreePath().isBlank()
                ? Path.of(ent.getWorktreePath()) : null;

        SessionExecutor executor = resolveExecutor();
        String pm = props.getPermissionMode();
        Process proc;
        try {
            proc = executor.launch(new SessionExecutor.LaunchContext(
                    id, worktree, ent.getTaskSpec(), ent.getModel(), pm));
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "恢复会话失败: " + e.getMessage(), e);
        }
        SessionRuntime rt = new SessionRuntime(id, proc, mapper, parser, eventSaver, listener, props);
        runtimes.put(id, rt);
        rt.start();

        ent.setStatus(SessionState.RUNNING.name());
        ent.setPid(proc.pid());
        ent.setUpdatedAt(Instant.now());
        sessionRepo.save(ent);
        return toView(ent, rt.state());
    }

    public SessionView kill(String id) {
        SessionHandle rt = requireRuntime(id);
        rt.kill();
        updateStatus(id, SessionState.TERMINATED, "已手动终止");
        return get(id);
    }

    /** 优雅结束：关 stdin，claude 读完后自然退出 → DONE/FAILED。 */
    public void finish(String id) {
        SessionHandle rt = requireRuntime(id);
        rt.finish();
    }

    /** 订阅实时事件流，返回回放（环形缓冲快照）。 */
    public List<SessionEvent> subscribe(String id, Consumer<SessionEvent> consumer) {
        SessionHandle rt = requireRuntime(id);
        return rt.subscribe(consumer);
    }

    public void unsubscribe(String id, Consumer<SessionEvent> consumer) {
        SessionHandle rt = runtimes.get(id);
        if (rt != null) {
            rt.unsubscribe(consumer);
        }
    }

    // ---------------- worktree / diff ----------------

    public WorktreeManager.DiffResult diff(String id) {
        SessionEntity ent = requireEntity(id);
        Project project = resolveProject(ent.getProjectId());
        if (project == null || ent.getWorktreePath() == null || ent.getWorktreePath().isBlank()) {
            return WorktreeManager.DiffResult.empty();
        }
        return worktreeManager.diff(project, Path.of(ent.getWorktreePath()));
    }

    public void removeWorktree(String id) {
        SessionEntity ent = requireEntity(id);
        if (ent.getWorktreePath() == null || ent.getWorktreePath().isBlank()) {
            return;
        }
        Project project = resolveProject(ent.getProjectId());
        if (project == null) {
            return;
        }
        workspaceService.cleanupSessionWorkspace(project, id, Path.of(ent.getWorktreePath()));
        ent.setWorktreePath(null);
        ent.setUpdatedAt(Instant.now());
        sessionRepo.save(ent);
    }

    // ---------------- 模板 ----------------

    /** 删除会话：杀进程（若在跑）、清理 worktree、删除事件与记录。 */
    @Transactional
    public void deleteSession(String id) {
        SessionHandle rt = runtimes.remove(id);
        if (rt != null) {
            rt.unsubscribeAll();
            rt.kill();
        }
        SessionEntity ent = requireEntity(id);
        if (ent.getWorktreePath() != null && !ent.getWorktreePath().isBlank()) {
            try {
                Project project = resolveProject(ent.getProjectId());
                if (project != null) {
                    workspaceService.cleanupSessionWorkspace(project, id, Path.of(ent.getWorktreePath()));
                }
            } catch (Exception e) {
                log.warn("删除会话时清理 worktree 失败: {} err={}", id, e.getMessage());
            }
        }
        eventRepo.deleteBySessionId(id);
        sessionRepo.delete(ent);
    }

    public List<TemplateView> listTemplates() {
        return templateRepo.findAll().stream()
                .sorted(Comparator.comparingInt(SessionTemplateEntity::getSortOrder))
                .map(TemplateView::from).toList();
    }

    public TemplateView saveTemplate(Long id, String code, String name, String prompt,
                                     Integer sortOrder, Boolean enabled) {
        SessionTemplateEntity t = id == null ? new SessionTemplateEntity()
                : templateRepo.findById(id).orElseThrow(() ->
                        new DevMindException(ErrorCode.NOT_FOUND, "模板不存在: " + id));
        if (code != null) t.setCode(code);
        if (name != null) t.setName(name);
        if (prompt != null) t.setPrompt(prompt);
        if (sortOrder != null) t.setSortOrder(sortOrder);
        if (enabled != null) t.setEnabled(enabled);
        return TemplateView.from(templateRepo.save(t));
    }

    public void deleteTemplate(Long id) {
        templateRepo.deleteById(id);
    }

    /** 渲染模板用于预览（三个占位符独立给定）。 */
    public String previewTemplate(String code, String task, String projectName, String branch) {
        SessionTemplateEntity t = templateRepo.findByCode(code)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "模板不存在: " + code));
        String prompt = t.getPrompt() == null ? "" : t.getPrompt();
        return prompt
                .replace("{{task}}", task == null ? "" : task)
                .replace("{{project}}", projectName == null ? "" : projectName)
                .replace("{{branch}}", branch == null ? "" : branch);
    }

    /** 渲染模板：替换 {{task}}/{{project}}/{{branch}} 占位符。 */
    public String renderTemplate(String code, String task, Project project) {
        SessionTemplateEntity t = templateRepo.findByCode(code)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "模板不存在: " + code));
        String prompt = t.getPrompt() == null ? "" : t.getPrompt();
        String projectName = project != null ? project.name() : "";
        String branch = project != null ? project.baseBranch() : "";
        return prompt
                .replace("{{task}}", task == null ? "" : task)
                .replace("{{project}}", projectName)
                .replace("{{branch}}", branch);
    }

    // ---------------- 启动/关闭 ----------------

    @PostConstruct
    public void restoreOnStartup() {
        // 服务重启后，上一次的进程已随旧实例消亡：遗留的"活动"状态全部标记 TERMINATED
        List<String> stale = List.of(SessionState.RUNNING.name(), SessionState.WAITING_INPUT.name(),
                SessionState.WAITING_AUTH.name());
        for (SessionEntity ent : sessionRepo.findAll()) {
            if (stale.contains(ent.getStatus())) {
                ent.setStatus(SessionState.TERMINATED.name());
                ent.setSummary("服务重启，会话已终止（进程随旧实例退出）");
                ent.setFinishedAt(Instant.now());
                ent.setUpdatedAt(Instant.now());
                sessionRepo.save(ent);
            }
        }
        int n = sessionRepo.findAll().stream()
                .filter(e -> SessionState.TERMINATED.name().equals(e.getStatus())).toList().size();
        log.info("启动恢复完成，遗留活动会话已标记 TERMINATED");
    }

    @PreDestroy
    public void shutdown() {
        for (SessionHandle rt : runtimes.values()) {
            try {
                rt.kill();
            } catch (Exception e) {
                log.warn("关闭时终止会话异常: session={}", rt.id(), e);
            }
        }
        runtimes.clear();
    }

    // ---------------- 内部 ----------------

    private Project resolveProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return null; // 无项目裸跑（fake 模式）
        }
        return projectService.requireProject(projectId);
    }

    private void ensureCapacity() {
        long active = runtimes.values().stream().filter(r -> r.state().isActive()).count();
        if (active >= props.getMaxConcurrent()) {
            throw new DevMindException(ErrorCode.TOO_MANY_SESSIONS,
                    "并发会话数已达上限 " + props.getMaxConcurrent());
        }
    }

    private SessionExecutor resolveExecutor() {
        for (SessionExecutor ex : executors) {
            if (ex.name().equalsIgnoreCase(props.getExecutor())) {
                return ex;
            }
        }
        throw new DevMindException(ErrorCode.BAD_REQUEST, "未知执行器: " + props.getExecutor());
    }

    /** CAP-21：取节点连接 SPI；devmind-agent 未装配时报错（远程会话不可用）。 */
    private AgentNodeConnector requireConnector() {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "远程 agent 模块未装配，无法创建远程会话");
        }
        return connector;
    }

    // ---------------- CAP-21 远程事件入口（RemoteAgentBridge 路由至此） ----------------

    /** runner 回传的已解析事件 → 对应远程运行时 ingest（驱动状态机/落库/WS 广播）。 */
    public void onRemoteEvent(String nodeId, AgentEventFrame frame) {
        SessionHandle h = runtimes.get(frame.sessionId());
        if (h instanceof RemoteSessionRuntime r && r.nodeId().equals(nodeId)) {
            r.ingest(frame);
        }
    }

    /** runner 侧子进程退出。 */
    public void onRemoteExit(String nodeId, String sessionId, int exitCode) {
        SessionHandle h = runtimes.get(sessionId);
        if (h instanceof RemoteSessionRuntime r && r.nodeId().equals(nodeId)) {
            r.handleExit(exitCode);
        }
    }

    /** runner hello 对账：不在存活清单里的会话标记 FAILED；清单内的恢复在线标记。 */
    public void onRemoteHello(String nodeId, List<String> activeSessionIds) {
        for (SessionHandle h : runtimes.values()) {
            if (h instanceof RemoteSessionRuntime r && r.nodeId().equals(nodeId)) {
                if (activeSessionIds != null && activeSessionIds.contains(r.id())) {
                    r.noteReconnected();
                } else {
                    r.markLost("runner 重连后对账：会话不在存活清单（进程已随 runner 旧实例退出）");
                }
            }
        }
    }

    /** 节点断线：该节点远程会话打失联标记事件，不判 FAILED。 */
    public void onNodeDisconnected(String nodeId) {
        for (SessionHandle h : runtimes.values()) {
            if (h instanceof RemoteSessionRuntime r && r.nodeId().equals(nodeId)) {
                r.noteDisconnected();
            }
        }
    }

    private SessionHandle requireRuntime(String id) {
        SessionHandle rt = runtimes.get(id);
        if (rt == null) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "会话不在运行中: " + id);
        }
        return rt;
    }

    private SessionEntity requireEntity(String id) {
        return sessionRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "会话不存在: " + id));
    }

    private SessionState liveState(SessionEntity ent) {
        SessionHandle rt = runtimes.get(ent.getId());
        return rt != null ? rt.state() : SessionState.valueOf(ent.getStatus());
    }

    private SessionView toView(SessionEntity ent, SessionState state) {
        return new SessionView(
                ent.getId(), ent.getProjectId(), ent.getWorkItemId(), ent.getRequirementId(), ent.getTaskSpec(),
                state.name(), state, ent.getWorktreePath(), ent.getPid(),
                ent.getModel(), ent.getSummary(), ent.getAgentNodeId(),
                ent.getCreatedAt(), ent.getUpdatedAt(), ent.getFinishedAt());
    }

    private void updateStatus(String id, SessionState st, String summary) {
        sessionRepo.findById(id).ifPresent(ent -> {
            ent.setStatus(st.name());
            if (summary != null) {
                ent.setSummary(summary);
            }
            if (st == SessionState.TERMINATED || st == SessionState.SUSPENDED) {
                ent.setFinishedAt(Instant.now());
            }
            ent.setUpdatedAt(Instant.now());
            sessionRepo.save(ent);
        });
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
