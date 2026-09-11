package com.devmind.session.service;

import com.devmind.common.agent.AgentEventFrame;
import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.auth.IdentityService;
import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.common.notification.NotificationEvent;
import com.devmind.notification.NotificationPublisher;
import com.devmind.project.WorktreeManager;
import com.devmind.project.workspace.WorkspaceService;
import com.devmind.project.RequirementService;
import com.devmind.project.WorkItemService;
import com.devmind.project.model.Project;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.project.dto.RepoView;
import com.devmind.project.ProjectService;import com.devmind.session.config.SessionProperties;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.RepoDiffView;
import com.devmind.session.dto.SessionView;
import com.devmind.session.model.SessionEntity;
import com.devmind.common.agent.SessionEvent;
import com.devmind.session.model.SessionEventEntity;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.session.model.SessionRepoEntity;
import com.devmind.session.model.SessionScenarioEntity;
import com.devmind.session.repo.SessionEventRepository;
import com.devmind.session.repo.SessionRepoRepository;
import com.devmind.session.repo.SessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.devmind.common.agent.runtime.RemoteSessionRuntime;
import com.devmind.common.agent.runtime.RuntimeListener;
import com.devmind.session.runtime.SessionEventSaver;
import com.devmind.common.agent.runtime.SessionHandle;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 会话生命周期入口：create/list/get/events/input/authorize/suspend/resume/kill/diff/worktree/模板。
 * 持有运行时注册表，协调节点路由/上下文包/事件落库/通知。
 * CAP-34 FR-02：服务端零执行——所有会话路由到 runner 节点，无本机分支。
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
    /** CAP-34 FR-03：上下文包装配（launch 帧 contextManifest + runner HTTP 拉包的供给侧） */
    private final SessionContextService sessionContextService;
    private final NotificationPublisher notificationPublisher;
    private final DomainEventPublisher eventPublisher;
    private final SessionRepository sessionRepo;
    private final SessionEventRepository eventRepo;
    /** CAP-33：场景解析/渲染/预设（session_templates 升级版；同槽位替换原 templateRepo） */
    private final ScenarioService scenarioService;
    /** CAP-31：会话仓库快照（session_repos，创建时从 project_repos 拷值） */
    private final SessionRepoRepository sessionRepoRepo;
    private final SessionEventSaver eventSaver;
    private final SessionProperties props;
    private final ObjectMapper mapper;
    /** CAP-21：远程节点连接（devmind-agent 装配时可用；ObjectProvider 探测防循环依赖） */
    private final ObjectProvider<AgentNodeConnector> connectorProvider;
    /** CAP-24：Git 提交身份解析（devmind-integration 装配时可用；未装配回退系统 git 配置） */
    private final ObjectProvider<GitIdentityProvider> gitIdentityProvider;
    /** CAP-25：远程工作区凭据解析（devmind-integration 装配时可用；未装配/无凭据降级为节点自理） */
    private final ObjectProvider<RepoGitGateway> repoGitGateway;
    /** CAP-31：远程会话 diff（服务端克隆缓存 fetch + git diff） */
    private final RemoteDiffService remoteDiffService;
    /** 启动期存量清理的事务边界（@PostConstruct 不经代理，@Transactional 不生效） */
    private final PlatformTransactionManager txManager;

    /** 运行中会话注册表（本地/远程统一句柄）。 */
    private final Map<String, SessionHandle> runtimes = new ConcurrentHashMap<>();

    public SessionManagerService(IdentityService identityService,
                                 ProjectService projectService,
                                 WorkItemService workItemService,
                                 RequirementService requirementService,
                                 WorktreeManager worktreeManager,
                                 WorkspaceService workspaceService,
                                 SessionContextService sessionContextService,
                                 NotificationPublisher notificationPublisher,
                                 DomainEventPublisher eventPublisher,
                                 SessionRepository sessionRepo,
                                 SessionEventRepository eventRepo,
                                 ScenarioService scenarioService,
                                 SessionRepoRepository sessionRepoRepo,
                                 SessionEventSaver eventSaver,
                                 SessionProperties props,
                                 ObjectMapper mapper,
                                 ObjectProvider<AgentNodeConnector> connectorProvider,
                                 ObjectProvider<GitIdentityProvider> gitIdentityProvider,
                                 ObjectProvider<RepoGitGateway> repoGitGateway,
                                 RemoteDiffService remoteDiffService,
                                 PlatformTransactionManager txManager) {
        this.identityService = identityService;
        this.projectService = projectService;
        this.workItemService = workItemService;
        this.requirementService = requirementService;
        this.worktreeManager = worktreeManager;
        this.workspaceService = workspaceService;
        this.sessionContextService = sessionContextService;
        this.notificationPublisher = notificationPublisher;
        this.eventPublisher = eventPublisher;
        this.sessionRepo = sessionRepo;
        this.eventRepo = eventRepo;
        this.scenarioService = scenarioService;
        this.sessionRepoRepo = sessionRepoRepo;
        this.eventSaver = eventSaver;
        this.props = props;
        this.mapper = mapper;
        this.connectorProvider = connectorProvider;
        this.gitIdentityProvider = gitIdentityProvider;
        this.repoGitGateway = repoGitGateway;
        this.remoteDiffService = remoteDiffService;
        this.txManager = txManager;
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
        // CAP-33：场景解析（templateCode 兼容 = 等同 scenarioCode；PROJECT 场景限本项目）+ 骨架渲染
        SessionScenarioEntity scenario = resolveScenario(req, projectId);
        if (scenario != null) {
            taskSpec = scenarioService.render(scenario, req.taskSpec(), project,
                    requirement != null ? requirement.getTitle() : null);
        }

        // CAP-34 FR-02：取消本机会话——会话必有执行节点；CAP-33：场景预设插在显式与项目默认之间。
        // 显式指定 > 场景预设 > 项目默认 > 平台默认（agent_nodes.is_default），皆无命中直接 409，
        // 不存在本机回落；节点离线由 launch ack 报错，不静默起失败进程。
        // CAP-28 的 agentNodeId="local" 保留值同步废除。
        if ("local".equalsIgnoreCase(req.agentNodeId())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "CAP-34 起不存在本机会话：agentNodeId=\"local\" 保留值已废除，请指定 runner 节点或留空走默认路由");
        }
        AgentNodeConnector connector = requireConnector();
        List<String> requiredLabels = parseCsv(req.requiredLabels());
        String scenarioNodeId = scenario != null ? scenario.getAgentNodeId() : null;
        String projectDefault = project != null && project.agentNodeId() != null
                && !project.agentNodeId().isBlank() ? project.agentNodeId() : null;
        String agentNodeId = routeAgentNode(req.agentNodeId(), scenarioNodeId, projectDefault,
                platformDefaultNodeId(), requiredLabels, connector, req.requiredLabels());

        // CAP-31：会话仓库快照（创建时从 project_repos 拷值，生命周期以快照为准）；
        // 空 = 项目无仓库行（兼容旧单库路径，按 projects 镜像列跑）
        List<SessionRepoEntity> repoRows = resolveRepoSnapshot(project, req, id, baseBranch);

        // CAP-34：服务端不建 worktree、不做本机知识注入——工作区与上下文物化均在 runner 侧
        // （launch 帧 repos + contextManifest，runner 拉包物化，见 SessionContextService）

        // CAP-33：model/permissionMode 优先级 = 请求显式 > 场景预设 > 全局配置
        String model = firstNonBlank(req.model(), scenario != null ? scenario.getModel() : null,
                props.getModel());
        String pm = firstNonBlank(req.permissionMode(),
                scenario != null ? scenario.getPermissionMode() : null, props.getPermissionMode());

        // CAP-24 FR-03：按会话发起人 + 主库 remoteUrl host 解析提交身份，随进程 env 注入
        Map<String, String> gitEnv = resolveGitEnv(identityService.currentActor(), project);
        // CAP-33 FR-02：三层合并装配上下文包（场景绑定 + 项目自动命中 + 请求追加）。
        // 场景绑定的资产失效（DevMindException 404）fail-visible 向上传播；其它装配异常降级
        // 为无上下文启动（沿用知识注入不阻塞会话的语义）
        SessionContextService.Prepared prepared = prepareContext(id, project, scenario, taskSpec,
                req.extraSkillIds(), req.extraDocIds(), req.extraKnowledgeTags());
        RemoteSessionRuntime remoteRt = new RemoteSessionRuntime(id, agentNodeId, connector,
                eventSaver, listener, props.toRuntimeSettings());
        // 先注册再 launch：ack 之后 runner 事件即刻上行，注册晚于 ack 会丢开头事件
        runtimes.put(id, remoteRt);
        try {
            // CAP-31：repos=全量快照（含 name，新 runner 多库模式）；repo=首个（主库）保持旧 runner 降级
            List<AgentLaunchCommand.RepoSpec> specs = buildRepoSpecs(project, repoRows, baseBranch,
                    worktreeManager.branchFor(id), identityService.currentActor());
            // CAP-34 FR-03：上下文包清单随帧下发，runner 凭 manifest 拉包物化
            connector.launch(agentNodeId, new AgentLaunchCommand(
                    id, project != null ? project.id() : null, taskSpec, model, pm, gitEnv,
                    specs.isEmpty() ? null : specs.get(0), "session",
                    specs.size() > 1 ? specs : null, prepared != null ? prepared.manifest() : null));
        } catch (Exception e) {
            runtimes.remove(id);
            if (e instanceof DevMindException de) {
                throw de;
            }
            throw new DevMindException(ErrorCode.CONFLICT, "下发会话到执行节点失败: " + e.getMessage(), e);
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
        // CAP-34：新会话恒有执行节点；worktree_path/pid 为本机时代字段，新行恒 null
        ent.setWorktreePath(null);
        ent.setAgentNodeId(agentNodeId);
        ent.setPid(null);
        ent.setModel(model);
        ent.setPermissionMode(pm);
        ent.setCreatedBy(identityService.currentActor());
        // CAP-33：场景 code 落库（resume 据此重渲染重装配；FR-07 快照在装配后落）
        ent.setScenarioCode(scenario != null ? scenario.getCode() : null);
        ent.setContextManifestJson(prepared != null ? prepared.snapshotJson() : null);
        ent.setCreatedAt(now);
        ent.setUpdatedAt(now);
        sessionRepo.save(ent);
        // CAP-31：仓库快照随会话记录一并落库（resume/清理/远程 diff 读快照，不回查项目现值）
        if (!repoRows.isEmpty()) {
            sessionRepoRepo.saveAll(repoRows);
        }

        notificationPublisher.publish(NotificationEvent.of("SESSION_STARTED", id, "会话已启动",
                preview(taskSpec, 80)));
        return toView(ent, remoteRt.state());
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
        SessionState cur = SessionState.valueOf(ent.getStatus());
        if (cur.isActive()) {
            throw new DevMindException(ErrorCode.CONFLICT, "会话正在运行中，无需恢复");
        }
        // 终态恢复依赖 claude --resume 续接对话历史；无 CLI 会话记录的历史数据只能全新开始，不允许
        if (cur != SessionState.SUSPENDED
                && (ent.getCliSessionId() == null || ent.getCliSessionId().isBlank())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "该会话缺少 CLI 会话记录（历史数据），无法继续对话");
        }
        runtimes.remove(id);
        // 恢复用创建时持久化的权限模式（老记录无值回退全局默认）
        String pm = ent.getPermissionMode() != null && !ent.getPermissionMode().isBlank()
                ? ent.getPermissionMode() : props.getPermissionMode();

        // CAP-34 FR-02：历史本机会话（agent_node_id IS NULL）不可恢复——本机执行路径已下线
        if (ent.getAgentNodeId() == null || ent.getAgentNodeId().isBlank()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "历史本机会话（无执行节点）不可恢复，请新建会话");
        }
        // CAP-21 远程会话恢复：重新向节点下发 launch（workdir 仍由 runner 项目路径映射解析）
        if (ent.getAgentNodeId() != null && !ent.getAgentNodeId().isBlank()) {
            AgentNodeConnector connector = requireConnector();
            RemoteSessionRuntime rt = new RemoteSessionRuntime(id, ent.getAgentNodeId(), connector,
                    eventSaver, listener, props.toRuntimeSettings());
            runtimes.put(id, rt);
            SessionContextService.Prepared prepared = null;
            try {
                Project proj = resolveProject(ent.getProjectId());
                // CAP-31：从快照重建远程工作区描述（仓库可能已改名/改 URL，会话以创建时为准）
                List<AgentLaunchCommand.RepoSpec> specs = buildRepoSpecs(proj,
                        sessionRepoRepo.findBySessionIdOrderBySortOrderAscIdAsc(id), ent.getBaseBranch(),
                        worktreeManager.branchFor(id), ent.getCreatedBy());
                // CAP-33：resume 按落库 scenarioCode 重渲染重装配（修复此前用未渲染原文重装配的
                // 偏差；场景已删降级按原文；③层请求追加为创建时一次性，resume 不重放）；
                // resume = 重新注入（hitCount 再累计一次，与创建同语义）
                SessionScenarioEntity scenario = resumeScenario(ent);
                String renderedTask = scenario != null
                        ? scenarioService.render(scenario, ent.getTaskSpec(), proj, requirementTitleOf(ent))
                        : ent.getTaskSpec();
                prepared = prepareContext(id, proj, scenario, renderedTask, null, null, null);
                connector.launch(ent.getAgentNodeId(), new AgentLaunchCommand(
                        id, ent.getProjectId(), renderedTask, ent.getModel(),
                        pm,
                        resolveGitEnv(ent.getCreatedBy(), proj),
                        specs.isEmpty() ? null : specs.get(0), "session",
                        specs.size() > 1 ? specs : null,
                        prepared != null ? prepared.manifest() : null,
                        ent.getCliSessionId()));
            } catch (Exception e) {
                runtimes.remove(id);
                if (e instanceof DevMindException de) {
                    throw de;
                }
                throw new DevMindException(ErrorCode.CONFLICT, "恢复远程会话失败: " + e.getMessage(), e);
            }
            ent.setStatus(SessionState.RUNNING.name());
            ent.setContextManifestJson(prepared != null ? prepared.snapshotJson() : null);
            ent.setFinishedAt(null);
            ent.setUpdatedAt(Instant.now());
            sessionRepo.save(ent);
            return toView(ent, rt.state());
        }
        // 上方已拒绝 NULL 节点历史会话，编译器不可知，此处不可达
        throw new DevMindException(ErrorCode.CONFLICT, "历史本机会话（无执行节点）不可恢复，请新建会话");
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

    /**
     * CAP-31 diff（按库返回）：本地会话逐库 worktree diff（多库 = 聚合根下各子目录）；
     * 远程会话工作区在节点侧已随结束清理，走 {@link RemoteDiffService}（服务端克隆缓存 fetch + diff）。
     * 单库失败只填该行 error，不拖垮整组。
     */
    public List<RepoDiffView> diff(String id) {
        SessionEntity ent = requireEntity(id);
        List<SessionRepoEntity> rows = sessionRepoRepo.findBySessionIdOrderBySortOrderAscIdAsc(id);
        if (ent.getAgentNodeId() != null && !ent.getAgentNodeId().isBlank()) {
            return remoteDiffService.diff(ent, rows);
        }
        Project project = resolveProject(ent.getProjectId());
        if (project == null || ent.getWorktreePath() == null || ent.getWorktreePath().isBlank()) {
            return List.of();
        }
        Path root = Path.of(ent.getWorktreePath());
        if (rows.isEmpty()) {
            // 旧路径兼容：项目无仓库行，按 projects 镜像列单库
            WorktreeManager.DiffResult d = worktreeManager.diff(project, root);
            return List.of(RepoDiffView.of(project.name(), true, d.stat(), d.files()));
        }
        // 单库 cwd=worktree 本身；多库逐库定位聚合根下子目录（与 prepare 同算法推导）
        List<Path> dirs = rows.size() == 1 ? List.of(root)
                : WorkspaceService.childDirs(root, rows.stream().map(SessionRepoEntity::getName).toList());
        List<RepoDiffView> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            SessionRepoEntity row = rows.get(i);
            boolean primary = Boolean.TRUE.equals(row.getIsPrimary());
            try {
                WorktreeManager.DiffResult d = worktreeManager.diff(row.getBaseBranch(), dirs.get(i));
                out.add(RepoDiffView.of(row.getName(), primary, d.stat(), d.files()));
            } catch (Exception e) {
                out.add(RepoDiffView.error(row.getName(), primary, "diff 失败: " + e.getMessage()));
            }
        }
        return out;
    }

    public void removeWorktree(String id) {
        SessionEntity ent = requireEntity(id);
        if (ent.getWorktreePath() == null || ent.getWorktreePath().isBlank()) {
            return;
        }
        if (resolveProject(ent.getProjectId()) == null) {
            return;
        }
        cleanupWorkspace(ent);
        ent.setWorktreePath(null);
        ent.setUpdatedAt(Instant.now());
        sessionRepo.save(ent);
    }

    // ---------------- 删除会话 ----------------

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
                cleanupWorkspace(ent);
            } catch (Exception e) {
                log.warn("删除会话时清理 worktree 失败: {} err={}", id, e.getMessage());
            }
        }
        eventRepo.deleteBySessionId(id);
        sessionRepoRepo.deleteBySessionId(id);
        sessionRepo.delete(ent);
    }

    /**
     * CAP-33 场景解析：scenarioCode 优先，templateCode 兼容等同（模板行已迁移为场景）。
     * 按 code 解析不查 enabled（旧模板语义）；PROJECT 场景限本项目会话使用。
     */
    private SessionScenarioEntity resolveScenario(CreateSessionRequest req, String projectId) {
        String code = req.scenarioCode() != null && !req.scenarioCode().isBlank()
                ? req.scenarioCode() : req.templateCode();
        if (code == null || code.isBlank()) {
            return null;
        }
        SessionScenarioEntity s = scenarioService.requireByCode(code);
        if (ScenarioService.SCOPE_PROJECT.equals(s.getScope())
                && (projectId == null || projectId.isBlank() || !projectId.equals(s.getProjectId()))) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "场景 " + code + " 是项目 " + s.getProjectId() + " 的私有场景，不能在当前项目下使用");
        }
        return s;
    }

    // ---------------- 启动/关闭 ----------------

    @PostConstruct
    public void restoreOnStartup() {
        // CAP-31 会话拆分：无项目的旧会话整体删除（含事件）——通用问答迁 /chats（CAP-30），
        // CAP-28 one-shot 历史同属此类（运行期即建即弃，删除无影响）
        List<SessionEntity> orphans = sessionRepo.findAll().stream()
                .filter(e -> e.getProjectId() == null || e.getProjectId().isBlank())
                .toList();
        if (!orphans.isEmpty()) {
            new TransactionTemplate(txManager).executeWithoutResult(tx -> {
                for (SessionEntity e : orphans) {
                    eventRepo.deleteBySessionId(e.getId());
                    sessionRepo.delete(e);
                }
            });
            log.info("存量无项目会话已清理: {} 条（通用问答请用 AI 问答 /chats）", orphans.size());
        }

        // 服务重启后，本机时代的进程已随旧实例消亡：遗留的"活动"状态标记 TERMINATED。
        // CAP-34 FR-04：远程会话（agent_node_id 非空）进程在 runner 侧可能仍存活，不在此判死——
        // 留给 runner 重连后的 hello 对账（onRemoteHello）：清单内 reattach，清单外 FAILED
        List<String> stale = List.of(SessionState.RUNNING.name(), SessionState.WAITING_INPUT.name(),
                SessionState.WAITING_AUTH.name());
        for (SessionEntity ent : sessionRepo.findAll()) {
            if (stale.contains(ent.getStatus())
                    && (ent.getAgentNodeId() == null || ent.getAgentNodeId().isBlank())) {
                ent.setStatus(SessionState.TERMINATED.name());
                ent.setSummary("服务重启，会话已终止（进程随旧实例退出）");
                ent.setFinishedAt(Instant.now());
                ent.setUpdatedAt(Instant.now());
                sessionRepo.save(ent);
            }
        }
        log.info("启动恢复完成，遗留活动会话已标记 TERMINATED（远程会话留待 hello 对账）");
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

    /**
     * CAP-24 FR-03：解析会话提交身份 env（GIT_AUTHOR_NAME 等变量）。
     * 身份 = 用户在主库 remoteUrl host 的个人凭证署名 → 回退 displayName/username（仅 name）。
     * SPI 未装配/无项目/解析失败均返回空 Map（保持现状，系统 git 配置兜底）。
     */
    private Map<String, String> resolveGitEnv(String username, Project project) {
        GitIdentityProvider provider = gitIdentityProvider.getIfAvailable();
        if (provider == null || username == null || username.isBlank()) {
            return Map.of();
        }
        try {
            String repoHost = null;
            if (project != null) {
                String remoteUrl = projectService.primaryRepo(project.id()).getRemoteUrl();
                repoHost = hostOf(remoteUrl);
            }
            return provider.resolveAuthor(username, repoHost)
                    .map(author -> {
                        Map<String, String> env = new java.util.HashMap<>();
                        if (author.name() != null && !author.name().isBlank()) {
                            env.put("GIT_AUTHOR_NAME", author.name());
                            env.put("GIT_COMMITTER_NAME", author.name());
                        }
                        if (author.email() != null && !author.email().isBlank()) {
                            env.put("GIT_AUTHOR_EMAIL", author.email());
                            env.put("GIT_COMMITTER_EMAIL", author.email());
                        }
                        return Map.copyOf(env);
                    })
                    .orElse(Map.of());
        } catch (Exception e) {
            // 身份解析失败不阻塞会话创建
            log.debug("Git 提交身份解析失败(忽略): user={} err={}", username, e.getMessage());
            return Map.of();
        }
    }

    /**
     * CAP-25：组装远程工作区描述（runner 据此 clone/fetch/切会话分支/结束 push）。
     * 降级一律返回 null（= 旧行为，节点 project.<id> 映射自理）：无项目 / 主库无 remoteUrl /
     * ssh 协议 / SPI 未装配 / 无可用凭据。token 仅随 launch 帧传输，严禁进日志。
     */
    private AgentLaunchCommand.RepoSpec buildRepoSpec(Project project, String baseBranch,
                                                      String branch, String actor) {
        if (project == null) {
            return null;
        }
        try {
            String remoteUrl = projectService.primaryRepo(project.id()).getRemoteUrl();
            if (remoteUrl == null || remoteUrl.isBlank()) {
                return null; // 纯本地库：远程会话无码可拉，走节点映射
            }
            String url = remoteUrl.trim();
            if (url.startsWith("git@") || url.startsWith("ssh://")) {
                log.warn("远程工作区降级：remote_url 为 ssh 协议（仅支持 http/https）: project={}", project.id());
                return null;
            }
            RepoGitGateway gw = repoGitGateway.getIfAvailable();
            if (gw == null) {
                log.warn("远程工作区降级：integration 未装配，无凭据可下发: project={}", project.id());
                return null;
            }
            // token 可空 = 匿名通道（公开仓库 / file://），与 CAP-23 匿名克隆口径一致；
            // 私有库匿名 clone 会在 runner 侧以清晰的 auth 错误失败（launch ack 回传）
            String token = gw.resolveToken(actor, hostOf(url), project.id()).orElse(null);
            if (token == null) {
                log.info("远程工作区走匿名通道（无个人 PAT / 绑定 Integration）: project={}", project.id());
            }
            return new AgentLaunchCommand.RepoSpec(url,
                    baseBranch != null && !baseBranch.isBlank() ? baseBranch : project.baseBranch(),
                    branch, token);
        } catch (Exception e) {
            log.warn("远程工作区描述组装失败(降级为节点自理): project={} err={}", project.id(), e.getMessage());
            return null;
        }
    }

    /**
     * CAP-31：解析本次会话的仓库快照（内存对象，随会话记录一并落库）。
     * repoIds 空 = 主库（旧行为）；非空校验均属该项目，缺一个都报错。排序：主库在前，其余按 sortOrder——
     * 快照 sortOrder 按此顺序重编（聚合根落主库 .devmind 下、launch repos 首元素=主库均依赖该顺序）。
     * baseBranch 覆盖只作用于主库，其余库用各自默认分支。
     */
    private List<SessionRepoEntity> resolveRepoSnapshot(Project project, CreateSessionRequest req,
                                                        String sessionId, String baseBranch) {
        if (project == null) {
            return List.of();
        }
        List<RepoView> all = projectService.listRepos(project.id());
        if (all.isEmpty()) {
            return List.of();
        }
        List<RepoView> selected;
        if (req.repoIds() == null || req.repoIds().isEmpty()) {
            selected = all.stream().filter(RepoView::primary).limit(1).toList();
        } else {
            Set<Long> want = new HashSet<>(req.repoIds());
            selected = all.stream().filter(r -> want.contains(r.id())).toList();
            if (selected.size() != want.size()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "存在不属于项目 " + project.id() + " 的仓库: " + req.repoIds());
            }
        }
        if (selected.isEmpty()) {
            return List.of();
        }
        List<RepoView> ordered = new ArrayList<>(selected);
        ordered.sort(Comparator.comparing((RepoView r) -> !r.primary()).thenComparingInt(RepoView::sortOrder));
        String branch = worktreeManager.branchFor(sessionId);
        Instant now = Instant.now();
        List<SessionRepoEntity> rows = new ArrayList<>();
        int order = 0;
        for (RepoView r : ordered) {
            SessionRepoEntity e = new SessionRepoEntity();
            e.setSessionId(sessionId);
            e.setProjectRepoId(r.id());
            e.setName(r.name());
            e.setRemoteUrl(r.remoteUrl());
            e.setLocalPath(r.path());
            e.setBaseBranch(r.primary()
                    ? baseBranch
                    : (r.defaultBranch() != null && !r.defaultBranch().isBlank()
                            ? r.defaultBranch() : project.baseBranch()));
            e.setBranch(branch);
            e.setIsPrimary(r.primary());
            e.setSortOrder(order++);
            e.setCreatedAt(now);
            rows.add(e);
        }
        return rows;
    }

    /** 快照行 → 工作区参数（保序：主库在前）。 */
    private static List<WorkspaceService.SessionRepoSpec> toWorkspaceSpecs(List<SessionRepoEntity> rows) {
        return rows.stream()
                .map(r -> new WorkspaceService.SessionRepoSpec(r.getName(), r.getLocalPath(), r.getBaseBranch()))
                .toList();
    }

    /**
     * CAP-31：按快照组装远程工作区描述列表（主库在前）。逐库解析 token（按各自 remoteUrl host）；
     * 无 remoteUrl / ssh 协议 / token 解析失败的库跳过（记日志），全跳过 = 空列表（降级为节点自理）。
     * token 仅随 launch 帧传输，严禁进日志。
     */
    private List<AgentLaunchCommand.RepoSpec> buildRepoSpecs(Project project, List<SessionRepoEntity> rows,
                                                             String baseBranch, String branch, String actor) {
        if (rows.isEmpty()) {
            // 旧路径兼容：无快照（项目无仓库行）按 projects 镜像列单库组装
            AgentLaunchCommand.RepoSpec single = buildRepoSpec(project, baseBranch, branch, actor);
            return single != null ? List.of(single) : List.of();
        }
        RepoGitGateway gw = repoGitGateway.getIfAvailable();
        if (gw == null) {
            log.warn("远程工作区降级：integration 未装配，无凭据可下发: project={}",
                    project != null ? project.id() : null);
            return List.of();
        }
        List<AgentLaunchCommand.RepoSpec> out = new ArrayList<>();
        for (SessionRepoEntity row : rows) {
            try {
                String url = row.getRemoteUrl() == null ? "" : row.getRemoteUrl().trim();
                if (url.isBlank()) {
                    log.warn("远程工作区跳过无 remoteUrl 的库（远程不拉取）: repo={}", row.getName());
                    continue;
                }
                if (url.startsWith("git@") || url.startsWith("ssh://")) {
                    log.warn("远程工作区跳过 ssh 协议的库（仅支持 http/https）: repo={}", row.getName());
                    continue;
                }
                // token 可空 = 匿名通道（公开仓库 / file://），与 CAP-23 匿名克隆口径一致
                String token = gw.resolveToken(actor, hostOf(url),
                        project != null ? project.id() : null).orElse(null);
                out.add(new AgentLaunchCommand.RepoSpec(url, row.getBaseBranch(), row.getBranch(),
                        token, row.getName()));
            } catch (Exception e) {
                log.warn("远程工作区描述组装失败(跳过该库): repo={} err={}", row.getName(), e.getMessage());
            }
        }
        return out;
    }

    /** CAP-31 工作区清理（快照驱动）：多库按快照重建聚合工作区倒序清理；单库/无快照走旧单库路径。 */
    private void cleanupWorkspace(SessionEntity ent) {
        Project project = resolveProject(ent.getProjectId());
        if (project == null) {
            return;
        }
        List<SessionRepoEntity> rows = sessionRepoRepo.findBySessionIdOrderBySortOrderAscIdAsc(ent.getId());
        if (rows.size() > 1) {
            workspaceService.cleanupSessionWorkspace(toWorkspaceSpecs(rows), ent.getId(),
                    Path.of(ent.getWorktreePath()));
        } else {
            workspaceService.cleanupSessionWorkspace(project, ent.getId(), Path.of(ent.getWorktreePath()));
        }
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return java.net.URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void ensureCapacity() {
        long active = runtimes.values().stream().filter(r -> r.state().isActive()).count();
        if (active >= props.getMaxConcurrent()) {
            throw new DevMindException(ErrorCode.TOO_MANY_SESSIONS,
                    "并发会话数已达上限 " + props.getMaxConcurrent());
        }
    }

    /** CAP-21：取节点连接 SPI；devmind-agent 未装配时报错（远程会话不可用）。 */
    private AgentNodeConnector requireConnector() {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "远程 agent 模块未装配，无法创建远程会话");
        }
        return connector;
    }

    /** CAP-21 FR-03：平台默认执行节点（无默认或 agent 模块未装配 = null，调用方按 409 处理）。 */
    private String platformDefaultNodeId() {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        return connector != null ? connector.defaultNodeId() : null;
    }

    /** FR-07：CSV → 去空白去空项的标签列表（null/空白 = 空列表 = 无标签门控）。 */
    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * FR-02 + FR-07 节点路由（纯判定，可单测）：显式指定 > 场景预设（CAP-33）> 项目默认 >
     * 平台默认，requiredLabels 对每级门控；默认链皆不符且有标签要求时 pickNodeByLabels
     * 在线兜底；仍无命中 409。
     */
    static String routeAgentNode(String explicitNodeId, String scenarioPresetNodeId,
                                 String projectDefaultNodeId, String platformDefaultNodeId,
                                 List<String> requiredLabels, AgentNodeConnector connector,
                                 String requiredLabelsRaw) {
        if (explicitNodeId != null && !explicitNodeId.isBlank()) {
            if (!connector.nodeMatches(explicitNodeId, requiredLabels)) {
                throw new DevMindException(ErrorCode.CONFLICT,
                        "指定节点 " + explicitNodeId + " 不满足标签要求: " + requiredLabelsRaw);
            }
            return explicitNodeId;
        }
        for (String candidate : new String[]{scenarioPresetNodeId, projectDefaultNodeId, platformDefaultNodeId}) {
            if (candidate != null && !candidate.isBlank()
                    && connector.nodeMatches(candidate, requiredLabels)) {
                return candidate;
            }
        }
        if (!requiredLabels.isEmpty()) {
            String picked = connector.pickNodeByLabels(requiredLabels);
            if (picked != null && !picked.isBlank()) {
                return picked;
            }
        }
        throw new DevMindException(ErrorCode.CONFLICT, requiredLabels.isEmpty()
                ? "无可用执行节点：请显式指定执行节点，或配置项目默认/平台默认节点"
                : "无满足标签的在线节点: " + requiredLabelsRaw);
    }

    /**
     * CAP-33 FR-02：装配上下文包（三层合并）。场景绑定的资产失效（DevMindException 404）
     * 向上传播 fail-visible；其它装配异常降级为 null = 无上下文启动（沿用注入不阻塞语义）。
     */
    private SessionContextService.Prepared prepareContext(String sessionId, Project project,
                                                          SessionScenarioEntity scenario,
                                                          String renderedTaskSpec,
                                                          List<String> extraSkillIds,
                                                          List<Long> extraDocIds,
                                                          List<String> extraKnowledgeTags) {
        try {
            return sessionContextService.prepare(sessionId, project, scenario, renderedTaskSpec,
                    extraSkillIds, extraDocIds, extraKnowledgeTags);
        } catch (DevMindException de) {
            throw de;
        } catch (Exception e) {
            log.warn("上下文包装配失败(不带上下文启动): session={} err={}", sessionId, e.getMessage());
            return null;
        }
    }

    /** resume 场景解析（best-effort）：无场景/场景已删 → null（按原文恢复，不阻塞 resume）。 */
    private SessionScenarioEntity resumeScenario(SessionEntity ent) {
        if (ent.getScenarioCode() == null || ent.getScenarioCode().isBlank()) {
            return null;
        }
        try {
            return scenarioService.requireByCode(ent.getScenarioCode());
        } catch (DevMindException e) {
            log.warn("resume 时场景已删除，按原始任务恢复: session={} scenario={}",
                    ent.getId(), ent.getScenarioCode());
            return null;
        }
    }

    /** {{requirement}} 占位符的需求标题（best-effort，需求已删除 = 置空）。 */
    private String requirementTitleOf(SessionEntity ent) {
        if (ent.getRequirementId() == null || ent.getRequirementId().isBlank()) {
            return null;
        }
        try {
            return requirementService.requireById(ent.getRequirementId()).getTitle();
        } catch (Exception e) {
            return null;
        }
    }

    /** CAP-33 FR-07：已注入上下文清单（装配时的快照 JSON；无快照 = 无上下文会话，404）。 */
    public String contextManifest(String id) {
        SessionEntity ent = requireEntity(id);
        if (ent.getContextManifestJson() == null || ent.getContextManifestJson().isBlank()) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "会话无上下文快照: " + id);
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

    // ---------------- CAP-21 远程事件入口（RemoteAgentBridge 路由至此） ----------------

    /** runner 回传的已解析事件 → 对应远程运行时 ingest（驱动状态机/落库/WS 广播）。 */
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
        sessionRepo.findById(frame.sessionId()).ifPresent(ent -> {
            if (!id.equals(ent.getCliSessionId())) {
                ent.setCliSessionId(id);
                sessionRepo.save(ent);
            }
        });
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
        reconcileFromDb(nodeId, activeSessionIds);
    }

    /**
     * CAP-34 FR-04 服务端重启盲区：内存 runtimes 已丢失，DB 里该节点的活动状态存量会话
     * 按 hello 清单对账——清单内 reattach 重建 RemoteSessionRuntime 挂回（后续 event/exit 帧
     * 可路由）；清单外判 FAILED（进程已随 runner 旧实例退出）。
     */
    private void reconcileFromDb(String nodeId, List<String> activeSessionIds) {
        List<SessionEntity> stale = sessionRepo.findByAgentNodeIdAndStatusIn(nodeId,
                List.of(SessionState.RUNNING.name(), SessionState.WAITING_INPUT.name(),
                        SessionState.WAITING_AUTH.name()));
        for (SessionEntity ent : stale) {
            if (runtimes.containsKey(ent.getId())) {
                continue; // 内存对账已处理
            }
            if (activeSessionIds != null && activeSessionIds.contains(ent.getId())) {
                AgentNodeConnector connector = connectorProvider.getIfAvailable();
                if (connector == null) {
                    continue;
                }
                RemoteSessionRuntime rt = new RemoteSessionRuntime(ent.getId(), nodeId, connector,
                        eventSaver, listener, props.toRuntimeSettings());
                runtimes.put(ent.getId(), rt);
                rt.noteReconnected();
                log.info("服务端重启后对账：会话 {} reattach 到节点 {}", ent.getId(), nodeId);
            } else {
                String reason = "服务端重启后对账：会话进程已不存在（不在 runner 存活清单）";
                ent.setStatus(SessionState.FAILED.name());
                ent.setSummary(reason);
                ent.setFinishedAt(Instant.now());
                ent.setUpdatedAt(Instant.now());
                sessionRepo.save(ent);
                eventPublisher.publish(SimpleDomainEvent.of("session.completed", ent.getProjectId(),
                        ent.getWorkItemId(), ent.getCreatedBy(),
                        "会话 " + ent.getId() + " 失败（" + reason + "）",
                        "SESSION", ent.getId(), false));
                log.info("服务端重启后对账：会话 {} 判 FAILED（不在节点 {} 存活清单）", ent.getId(), nodeId);
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
        List<String> repoNames = sessionRepoRepo.findBySessionIdOrderBySortOrderAscIdAsc(ent.getId())
                .stream().map(SessionRepoEntity::getName).toList();
        return new SessionView(
                ent.getId(), ent.getProjectId(), ent.getWorkItemId(), ent.getRequirementId(), ent.getTaskSpec(),
                state.name(), state, ent.getWorktreePath(), ent.getPid(),
                ent.getModel(), ent.getSummary(), ent.getAgentNodeId(), repoNames,
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
