package com.devmind.session.service;

import com.devmind.common.agent.AgentEventFrame;
import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.AgentCollectResult;
import com.devmind.common.agent.AgentProtocol;
import com.devmind.common.agent.FinalizeResult;
import com.devmind.common.agent.WorkspaceQueryResult;
import com.devmind.common.agent.WorkspaceReleaseResult;
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
import com.devmind.project.event.RequirementDeletedEvent;
import com.devmind.project.model.Project;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.project.dto.RepoView;
import com.devmind.project.dto.WorkItemRequest;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.ProjectService;import com.devmind.session.config.SessionProperties;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.CollectResultView;
import com.devmind.session.dto.OutputContentView;
import com.devmind.session.dto.OutputFileView;
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
import org.springframework.data.domain.PageRequest;
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

    /** CAP-50：事件补拉默认条数与硬上限（limit&lt;=0 走默认）。开流式后一轮就有几百条增量。 */
    static final int DEFAULT_EVENT_LIMIT = 2000;
    static final int MAX_EVENT_LIMIT = 20_000;

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
    /** CAP-39：会话产出读取（runner 回传的 session_outputs） */
    private final SessionOutputService outputService;

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
                                 PlatformTransactionManager txManager,
                                 SessionOutputService outputService) {
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
        this.outputService = outputService;
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
            requirement = requirementService.requireById(req.requirementId());
            if (projectId == null || projectId.isBlank()) {
                projectId = requirement.getProjectId();
            } else if (!projectId.equals(requirement.getProjectId())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "需求 " + req.requirementId() + " 不属于项目 " + projectId);
            }
            // CAP-38 FR-06：挂需求的执行会话自动建 DEVELOPMENT 工作单元（[flow:*] 流程会话豁免）
            workItem = autoCreateWorkItem(req, requirement);
        }
        Project project = resolveProject(projectId);
        // CAP-41：WORKLOG 项目会话——runner 持久工作区（kind:"worklog"），无仓库/分支语义，
        // 同一空间同时仅允许一个 RUNNING 会话（多会话共享同一目录，防写冲突）
        boolean worklog = project != null && ProjectEntity.KIND_WORKLOG.equals(project.kind());
        if (worklog) {
            boolean hasRunning = sessionRepo.findByProjectIdOrderByCreatedAtDesc(project.id()).stream()
                    .anyMatch(s -> SessionState.RUNNING.name().equals(s.getStatus()));
            if (hasRunning) {
                throw new DevMindException(ErrorCode.CONFLICT,
                        "该工作日志空间已有进行中的会话（同一空间共享目录，同时只允许一个会话）");
            }
        }
        String id = shortId();
        String baseBranch = worklog ? ""
                : req.baseBranch() != null && !req.baseBranch().isBlank()
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
        // CAP-41：worklog 会话属「必须认识」的新 kind——老 runner 会落入 legacy 兜底目录跑偏，
        // 协议 v5 门控，不足直接 409 提示升级（fail-visible，不静默降级）
        if (worklog && !connector.supports(agentNodeId, AgentProtocol.WORKLOG_KIND)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 runner 版本过低，不支持工作日志空间（需协议 v5+），请升级该节点 runner");
        }

        // CAP-31：会话仓库快照（创建时从 project_repos 拷值，生命周期以快照为准）；
        // 空 = 项目无仓库行（兼容旧单库路径，按 projects 镜像列跑）
        // CAP-41：WORKLOG 项目无仓库语义，跳过快照
        List<SessionRepoEntity> repoRows = worklog ? List.of()
                : resolveRepoSnapshot(project, req, requirement != null ? requirement.getId() : null,
                        id, baseBranch);

        // CAP-51 FR-01/FR-02：需求粒度工作区键与分支（服务端唯一生成点，runner 不推导）——
        // 关联需求 → worktrees/req-<需求id> + feature/req-<需求id>；无需求 repo 会话 →
        // worktrees/sid-<会话id> + feature/<会话id>。键随 launch 帧下发（协议 v10 门控），
        // 分支写进 session_repos 快照（收口/释放/resume 一律读快照，见 FR-11）。
        String requirementIdOfSession = requirement != null ? requirement.getId() : null;
        String workspaceKey = worktreeManager.workspaceKeyFor(requirementIdOfSession, id);
        String sessionBranch = worktreeManager.branchFor(requirementIdOfSession, id);

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
                req.extraSkillIds(), req.extraDocIds(), req.extraKnowledgeTags(),
                requirement != null ? requirement.getId() : null,
                SessionContextService.isExecutionSession(taskSpec, req.workItemId()));
        RemoteSessionRuntime remoteRt = new RemoteSessionRuntime(id, agentNodeId, connector,
                eventSaver, listener, props.toRuntimeSettings());
        // 先注册再 launch：ack 之后 runner 事件即刻上行，注册晚于 ack 会丢开头事件
        runtimes.put(id, remoteRt);
        // CAP-42：repo 会话固定工作区归属用户名（launch 帧 workspaceOwner；try 内解析后供落库）
        String wsOwner = null;
        // CAP-51：本会话是否真的绑定了一块工作区（repo 会话且归属用户解析成功）——据此落 workspace_key
        boolean workspaceBound = false;
        try {
            // CAP-31：repos=全量快照（含 name，新 runner 多库模式）；repo=首个（主库）保持旧 runner 降级
            // CAP-41：worklog 会话无仓库块，kind="worklog" + worklogOwner=项目归属用户（runner 目录隔离键）
            List<AgentLaunchCommand.RepoSpec> specs = worklog ? List.of()
                    : buildRepoSpecs(project, repoRows, baseBranch, sessionBranch,
                            identityService.currentActor());
            // CAP-42/CAP-51：repo 会话走工作区——归属用户解析（无登录态按 WI/需求归属人回退链）
            // + 协议门控（带 workspaceKey 需 v10+，缺 key 的存量路径仍是 v7；老 runner 会忽略新字段
            //   把不同需求写进同一 work/ 目录，fail-visible 409，绝不静默下发）
            if (!worklog && !specs.isEmpty()) {
                wsOwner = requireWorkspaceOwner(resolveWorkspaceOwner(workItem, requirement));
                requireWorkspaceProtocol(connector, agentNodeId, workspaceKey);
                // CAP-51 FR-03：需求级互斥预检（服务端视角友好报错，runner 侧 ensureUserWorktree
                // 的分支比对仍是磁盘残留的最终防线）：同需求已有进行中的会话 → 409。
                // 无需求会话不预检——sid- 键天生独占（FR-07）。
                precheckRequirementOccupancy(requirementIdOfSession, null);
                workspaceBound = true;
            }
            // CAP-34 FR-03：上下文包清单随帧下发，runner 凭 manifest 拉包物化
            connector.launch(agentNodeId, new AgentLaunchCommand(
                    id, project != null ? project.id() : null, taskSpec, model, pm, gitEnv,
                    specs.isEmpty() ? null : specs.get(0), worklog ? "worklog" : "session",
                    specs.size() > 1 ? specs : null, prepared != null ? prepared.manifest() : null,
                    null, worklog ? project.ownerId() : null, wsOwner,
                    specs.isEmpty() ? null : workspaceKey));
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
        // CAP-42：固定工作区归属用户名落库（resume 以此为准，不随当前操作者漂移）；
        // repo 会话工作区状态置 OPEN（手动收口后置 FINALIZED）
        ent.setWorkspaceOwner(wsOwner);
        ent.setWorkspaceState(wsOwner != null ? SessionEntity.WORKSPACE_OPEN : null);
        // CAP-51：需求粒度工作区键落库（收口/释放/resume 一律读本列，不再现算；null = 旧布局）
        ent.setWorkspaceKey(workspaceBound ? workspaceKey : null);
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
        // CAP-51 FR-09：需求工作区转「占用中」（归属用户冻结在需求行上，前端工作区卡片读它）
        if (requirement != null && workspaceBound) {
            requirementService.markWorkspaceOpen(requirement.getId(), wsOwner);
        }
        return toView(ent, remoteRt.state());
    }

    /**
     * CAP-38 FR-06：会话直挂需求时自动创建 DEVELOPMENT 工作单元（title=taskSpec 首行截 60 字符，
     * spec=taskSpec），会话改挂新 WI；[flow:*] 流程会话（分析/拆分）豁免——它们直挂需求不算工作单元。
     * 需求终态（ACCEPTANCE/DONE/CANCELLED）拒绝关联新会话；建 WI 触发既有 rollup 推进需求状态。
     * 包可见便于单测（构造全链路 create 成本过高）。
     */
    WorkItemEntity autoCreateWorkItem(CreateSessionRequest req, RequirementEntity requirement) {
        String spec = req.taskSpec();
        if (spec == null || spec.startsWith("[flow:")) {
            return null;
        }
        String status = requirement.getStatus();
        if (RequirementEntity.STATUS_ACCEPTANCE.equals(status)
                || RequirementEntity.STATUS_DONE.equals(status)
                || RequirementEntity.STATUS_CANCELLED.equals(status)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "需求已 " + status + "（验收/完结），不能关联新会话创建工作单元");
        }
        String firstLine = spec.lines().map(String::trim).filter(l -> !l.isEmpty())
                .findFirst().orElse("");
        String title = firstLine.isEmpty() ? "执行 - " + requirement.getTitle()
                : (firstLine.length() > 60 ? firstLine.substring(0, 60) : firstLine);
        WorkItemView view = workItemService.create(requirement.getProjectId(), requirement.getId(),
                new WorkItemRequest(WorkItemEntity.TYPE_DEVELOPMENT, title, spec, null, null, null));
        log.info("会话关联需求自动建工作单元: req={} wi={} title={}", requirement.getId(), view.id(), title);
        return workItemService.requireById(view.id());
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

    /**
     * 补拉 seq &gt; afterSeq 的事件（升序返回，前端按 seq 追加）。
     *
     * <p>CAP-50：由「取全量」改为「取最近 limit 条」——倒序取页再反转，与 chat 侧的
     * {@code ChatEventRepository} 同一手法。调用方是会话页在每次终态切换时的补拉
     * （{@code ChatPanel}，仅在该会话已不活跃时），而流式增量让单会话事件数涨十几倍，
     * 从头取会把内存与延迟都拖垮。</p>
     */
    public List<SessionEvent> events(String id, long afterSeq, int limit) {
        requireEntity(id);
        int size = limit <= 0 ? DEFAULT_EVENT_LIMIT : Math.min(limit, MAX_EVENT_LIMIT);
        List<SessionEventEntity> rows = eventRepo.findBySessionIdAndSeqGreaterThanOrderBySeqDesc(
                id, afterSeq, PageRequest.of(0, size));
        List<SessionEvent> out = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) {
            out.add(toEvent(rows.get(i)));
        }
        return out;
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
                // CAP-41：WORKLOG 项目 resume 同样走 worklog 持久工作区（无仓库快照/分支），
                // 协议 v5 门控与创建一致（老 runner 不派发，fail-visible）
                boolean worklog = proj != null && ProjectEntity.KIND_WORKLOG.equals(proj.kind());
                if (worklog && !connector.supports(ent.getAgentNodeId(), AgentProtocol.WORKLOG_KIND)) {
                    throw new DevMindException(ErrorCode.CONFLICT,
                            "节点 runner 版本过低，不支持工作日志空间（需协议 v5+），请升级该节点 runner");
                }
                // CAP-31：从快照重建远程工作区描述（仓库可能已改名/改 URL，会话以创建时为准）
                List<AgentLaunchCommand.RepoSpec> specs = worklog ? List.of()
                        : buildRepoSpecs(proj,
                                sessionRepoRepo.findBySessionIdOrderBySortOrderAscIdAsc(id), ent.getBaseBranch(),
                                worktreeManager.branchFor(ent.getRequirementId(), id), ent.getCreatedBy());
                // CAP-42/CAP-51：repo 会话工作区——归属用户取落库 workspaceOwner（旧会话回退 createdBy，
                // 新缓存经 origin/<branch> 挂回已 push 分支不丢提交）；工作区键取落库快照
                // （null = 存量旧布局 work/，不推导——推导会指到不存在的 worktrees/<key>，见 FR-11）
                String wsOwner = null;
                if (!worklog && !specs.isEmpty()) {
                    wsOwner = requireWorkspaceOwner(ent.getWorkspaceOwner() != null
                            && !ent.getWorkspaceOwner().isBlank()
                            ? ent.getWorkspaceOwner() : ent.getCreatedBy());
                    requireWorkspaceProtocol(connector, ent.getAgentNodeId(), ent.getWorkspaceKey());
                    // CAP-51 FR-03：需求级互斥预检——resume 自身要排除（被 resume 的会话本来就是
                    // 该需求的占用方，不排除必自撞），同需求另有进行中会话才 409
                    precheckRequirementOccupancy(ent.getRequirementId(), ent.getId());
                }
                // CAP-33：resume 按落库 scenarioCode 重渲染重装配（修复此前用未渲染原文重装配的
                // 偏差；场景已删降级按原文；③层请求追加为创建时一次性，resume 不重放）；
                // resume = 重新注入（hitCount 再累计一次，与创建同语义）
                SessionScenarioEntity scenario = resumeScenario(ent);
                String renderedTask = scenario != null
                        ? scenarioService.render(scenario, ent.getTaskSpec(), proj, requirementTitleOf(ent))
                        : ent.getTaskSpec();
                prepared = prepareContext(id, proj, scenario, renderedTask, null, null, null,
                        ent.getRequirementId(),
                        SessionContextService.isExecutionSession(ent.getTaskSpec(), ent.getWorkItemId()));
                connector.launch(ent.getAgentNodeId(), new AgentLaunchCommand(
                        id, ent.getProjectId(), renderedTask, ent.getModel(),
                        pm,
                        resolveGitEnv(ent.getCreatedBy(), proj),
                        specs.isEmpty() ? null : specs.get(0), worklog ? "worklog" : "session",
                        specs.size() > 1 ? specs : null,
                        prepared != null ? prepared.manifest() : null,
                        ent.getCliSessionId(), worklog ? proj.ownerId() : null, wsOwner,
                        specs.isEmpty() ? null : ent.getWorkspaceKey()));
                // CAP-51：收口后 resume 继续开发 → 需求工作区重新置「占用中」（M2 卡片状态不回退）
                if (!specs.isEmpty() && ent.getRequirementId() != null
                        && ent.getWorkspaceKey() != null && !ent.getWorkspaceKey().isBlank()) {
                    requirementService.markWorkspaceOpen(ent.getRequirementId(), wsOwner);
                }
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

    /**
     * CAP-42 手动收口（页面触发，<b>禁 @Transactional</b>——内含 WS 阻塞等 ack）：
     * 校验（归属/状态/协议版本）→ 按 session_repos 快照重建 specs → 下发 workspace_finalize
     * 帧阻塞等 ack（runner 逐库合并基线+push）→ 成功落 FINALIZED。
     * 失败（冲突/脏工作区/push 失败）透传 runner 脱敏错误，工作区保留可重试。
     *
     * <p>CAP-51 FR-04：有关联需求的会话转发到需求级语义（同一条 {@link #doFinalizeWorkspace}，
     * 成功同时把需求工作区置 FINALIZED）——收口从「会话级动作」上移为「需求级动作」，
     * 本端点保留给无需求会话（存量前端/脚本平滑过渡）。</p>
     */
    public FinalizeResult finalizeWorkspace(String id, boolean discardChanges) {
        SessionEntity ent = requireEntity(id);
        // 归属校验：创建人本人或 admin（仿 ChatManagerService 模式；固定工作区按创建者归属）
        String actor = identityService.currentActor();
        if (ent.getCreatedBy() != null && !ent.getCreatedBy().equals(actor) && !isAdmin()) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "只有会话创建者或管理员可以收口工作区");
        }
        if (ent.getWorkspaceState() == null || ent.getWorkspaceState().isBlank()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "该会话无固定工作区（旧布局或非代码会话），无需收口");
        }
        if (SessionEntity.WORKSPACE_FINALIZED.equals(ent.getWorkspaceState())) {
            throw new DevMindException(ErrorCode.CONFLICT, "工作区已收口，无需重复操作");
        }
        return doFinalizeWorkspace(ent, discardChanges);
    }

    /**
     * CAP-51 FR-04 需求级收口（前端主入口，挂在需求详情页）：
     * 取该需求最近的 workspace OPEN 会话 → 按其 session_repos 快照收口（合并需求分支到基线 + push，
     * <b>保留</b>工作树与分支，runner 侧收口后 ff 前进到新基线）；成功后需求与同需求全部 OPEN 会话行
     * 置 FINALIZED。无 OPEN 会话（未开工作区或已收口）→ 409；节点离线/老 runner → 409（不静默成功）。
     */
    public FinalizeResult finalizeRequirementWorkspace(String projectId, String requirementId,
                                                      boolean discardChanges) {
        RequirementEntity req = requirementService.requireEntity(projectId, requirementId);
        // 归属校验（FR-04）：需求创建者/负责人或 admin
        String actor = identityService.currentActor();
        boolean owner = actor != null && (actor.equals(req.getCreatedBy()) || actor.equals(req.getOwnerId()));
        if (!owner && !isAdmin()) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "只有需求创建者/负责人或管理员可以收口该需求工作区");
        }
        SessionEntity target = sessionRepo.findByRequirementIdOrderByCreatedAtDesc(requirementId).stream()
                .filter(s -> SessionEntity.WORKSPACE_OPEN.equals(s.getWorkspaceState()))
                .filter(s -> s.getAgentNodeId() != null && !s.getAgentNodeId().isBlank())
                .findFirst()
                .orElseThrow(() -> new DevMindException(ErrorCode.CONFLICT,
                        "该需求工作区未开启或已收口"));
        return doFinalizeWorkspace(target, discardChanges);
    }

    /**
     * 收口本体（会话级/需求级共用）：状态与节点校验 → 快照重建 specs → 阻塞等 ack → 落库。
     *
     * <p>CAP-51：带落库 {@code workspace_key} 的会话走 v10 门控 + 带 key 的 finalize 重载
     * （runner 定位 {@code worktrees/<key>}，且收口后<b>保留</b>工作树——需求后续会话接着用）；
     * key 为空 = 存量 CAP-42 会话，仍是 v7 + 旧布局 {@code work/} 语义（FR-11）。</p>
     */
    private FinalizeResult doFinalizeWorkspace(SessionEntity ent, boolean discardChanges) {
        String id = ent.getId();
        if (SessionState.valueOf(ent.getStatus()).isActive() || runtimes.containsKey(id)) {
            throw new DevMindException(ErrorCode.CONFLICT, "会话仍在运行中，请先结束会话再收口");
        }
        if (ent.getAgentNodeId() == null || ent.getAgentNodeId().isBlank()) {
            throw new DevMindException(ErrorCode.CONFLICT, "历史本机会话（无执行节点）不可收口");
        }
        AgentNodeConnector connector = requireConnector();
        requireWorkspaceProtocol(connector, ent.getAgentNodeId(), ent.getWorkspaceKey());
        Project proj = resolveProject(ent.getProjectId());
        List<AgentLaunchCommand.RepoSpec> specs = buildRepoSpecs(proj,
                sessionRepoRepo.findBySessionIdOrderBySortOrderAscIdAsc(id), ent.getBaseBranch(),
                worktreeManager.branchFor(ent.getRequirementId(), id), ent.getCreatedBy());
        if (specs.isEmpty()) {
            throw new DevMindException(ErrorCode.CONFLICT, "会话无仓库快照，无法收口（非代码会话）");
        }
        String wsOwner = requireWorkspaceOwner(ent.getWorkspaceOwner() != null
                && !ent.getWorkspaceOwner().isBlank() ? ent.getWorkspaceOwner() : ent.getCreatedBy());
        FinalizeResult result = connector.finalizeWorkspace(ent.getAgentNodeId(), id,
                ent.getProjectId(), wsOwner, specs, discardChanges, ent.getWorkspaceKey());
        if (!result.ok()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "收口失败（工作区已保留，可处理后重试）: " + result.error());
        }
        markRequirementWorkspaceFinalized(ent);
        return result;
    }

    /**
     * CAP-51：需求粒度收口成功后的落库——目标会话行 + 同需求全部 OPEN 会话行置 FINALIZED，
     * 需求行 {@code workspace_state=FINALIZED}。
     *
     * <p>为什么连兄弟会话一起置位：会话行的 workspace_state 是存量兼容字段，但「重复收口 409」
     * 是靠「找不到 OPEN 会话」实现的——同需求的旧会话（如分析会话）留着 OPEN 会让第二次收口
     * 又挑中它、重复合并（FR-04 验收 3 要求重复收口 409）。</p>
     */
    private void markRequirementWorkspaceFinalized(SessionEntity ent) {
        Instant now = Instant.now();
        if (ent.getRequirementId() != null && !ent.getRequirementId().isBlank()) {
            requirementService.markWorkspaceFinalized(ent.getRequirementId());
            for (SessionEntity s : sessionRepo.findByRequirementIdOrderByCreatedAtDesc(ent.getRequirementId())) {
                if (SessionEntity.WORKSPACE_OPEN.equals(s.getWorkspaceState())) {
                    s.setWorkspaceState(SessionEntity.WORKSPACE_FINALIZED);
                    s.setUpdatedAt(now);
                    sessionRepo.save(s);
                }
            }
            return;
        }
        if (SessionEntity.WORKSPACE_OPEN.equals(ent.getWorkspaceState())) {
            ent.setWorkspaceState(SessionEntity.WORKSPACE_FINALIZED);
            ent.setUpdatedAt(now);
            sessionRepo.save(ent);
        }
    }

    /** CAP-42：当前操作者是否 admin（收口越权判定用；异常按非 admin 兜底） */
    private boolean isAdmin() {
        try {
            return identityService.currentUser()
                    .map(u -> com.devmind.auth.model.UserEntity.ROLE_ADMIN.equals(u.getRole()))
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
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

    // ---------------- CAP-54 工作区实时视图（旁路：最新值缓存 + WS 订阅，不入事件流/不落库） ----------------

    /** sessionId → 最新工作区快照（进程退出后保留最终态，删除会话时清除）。 */
    private final Map<String, Map<String, Object>> workspaceSnapshots = new ConcurrentHashMap<>();
    /** sessionId → 工作区快照订阅者（浏览器 WS /ws/sessions/{id} 的 workspace 帧）。 */
    private final Map<String, Set<Consumer<Map<String, Object>>>> workspaceSubs = new ConcurrentHashMap<>();

    /**
     * runner 上行 workspace_status（RemoteAgentBridge 路由至此）：认领本模块会话后缓存最新值
     * 并推订阅者。运行时在册按节点匹配；刚退出的尾帧运行时已注销，按 DB 归属节点兜底认领。
     */
    public void onWorkspaceStatus(String nodeId, String sessionId, Map<String, Object> snapshot) {
        SessionHandle h = runtimes.get(sessionId);
        if (!(h instanceof RemoteSessionRuntime r) || !r.nodeId().equals(nodeId)) {
            SessionEntity ent = sessionRepo.findById(sessionId).orElse(null);
            if (ent == null || !nodeId.equals(ent.getAgentNodeId())) {
                return; // 非本模块会话（chat 等）或节点不符——忽略
            }
        }
        workspaceSnapshots.put(sessionId, snapshot);
        Set<Consumer<Map<String, Object>>> subs = workspaceSubs.get(sessionId);
        if (subs != null) {
            for (Consumer<Map<String, Object>> c : subs) {
                try {
                    c.accept(snapshot);
                } catch (Exception e) {
                    log.debug("workspace 快照推送失败: session={} err={}", sessionId, e.getMessage());
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
     * 工作区只读查询透传（REST → workspace_query 帧 → runner 读盘/git）。
     * 协议版本门控在 connector 内（老 runner 409 引导升级）；查询失败抛 CONFLICT 带 runner 原因。
     */
    public Map<String, Object> workspaceQuery(String id, String action, String repo, String path) {
        SessionEntity ent = requireEntity(id);
        if (ent.getAgentNodeId() == null || ent.getAgentNodeId().isBlank()) {
            throw new DevMindException(ErrorCode.CONFLICT, "会话无执行节点记录，工作区视图不可用");
        }
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
        }
        WorkspaceQueryResult r = connector.workspaceQuery(ent.getAgentNodeId(), id, action, repo, path);
        if (!r.ok()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    r.error() == null || r.error().isBlank() ? "工作区查询失败" : r.error());
        }
        return r.payload();
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

    /**
     * 删除会话：杀进程（若在跑）→ 释放节点固定工作区（CAP-42）→ 清理本机 worktree（旧布局）
     * → 删除事件与记录。
     *
     * <p><b>禁 @Transactional</b>：方法内含阻塞 WS 往返（释放工作区等 runner ack，秒级），
     * 事务里等 ack 会长时间占库连接（同 {@link #finalizeWorkspace} 红线）；落库收敛到
     * {@link #purgeSession} 独立事务。</p>
     */
    public void deleteSession(String id) {
        SessionHandle rt = runtimes.remove(id);
        if (rt != null) {
            rt.unsubscribeAll();
            rt.kill();
        }
        workspaceSnapshots.remove(id); // CAP-54：旁路缓存随记录清除
        workspaceSubs.remove(id);
        SessionEntity ent = requireEntity(id);
        releaseFixedWorkspace(ent);
        if (ent.getWorktreePath() != null && !ent.getWorktreePath().isBlank()) {
            try {
                cleanupWorkspace(ent);
            } catch (Exception e) {
                log.warn("删除会话时清理 worktree 失败: {} err={}", id, e.getMessage());
            }
        }
        purgeSession(id);
    }

    /**
     * CAP-42：删除会话前释放 runner 侧固定工作区（不合并、不 push——丢弃语义）。
     *
     * <p>为什么必须做：固定工作区按 (项目, 用户) 唯一占用，runner 的占用判定只看磁盘上
     * worktree 的检出分支。不释放就删记录 → 目录成孤儿：新会话 launch 必失败，而报错引导的
     * 「收口合并到基线」入口又随会话记录一起消失，该 (项目,用户) 永久锁死（2026-09-21
     * admq-manager/admin 实事故）。故失败<b>阻断删除</b>（fail-visible），不做静默跳过。</p>
     *
     * <p>无需释放的情形：workspace_state 为 null（旧布局/非代码会话）、已 FINALIZED
     * （目录随收口已删）、无执行节点（历史本机会话）、无仓库快照（非代码会话）。</p>
     *
     * <p><b>CAP-51 例外</b>：需求粒度会话（{@code workspace_key = req-<需求id>}）的工作区归<b>需求</b>所有
     * （同需求多会话共用一棵工作树），删除单个会话不得回收——否则删掉一个会话就把需求里别人的改动
     * 一起丢了。释放触发点改为需求删除（{@link #onRequirementDeleted}）与 runner 侧 GC。</p>
     */
    private void releaseFixedWorkspace(SessionEntity ent) {
        if (!SessionEntity.WORKSPACE_OPEN.equals(ent.getWorkspaceState())) {
            return;
        }
        if (isRequirementScoped(ent)) {
            log.info("需求粒度会话删除不释放共享工作树（归需求所有，见 CAP-51 FR-06）: session={} key={}",
                    ent.getId(), ent.getWorkspaceKey());
            return;
        }
        String nodeId = ent.getAgentNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            return; // agent 模块未装配（裁剪部署/单测）：无节点可释放，沿用旧行为
        }
        List<AgentLaunchCommand.RepoSpec> specs = buildRepoSpecs(resolveProject(ent.getProjectId()),
                sessionRepoRepo.findBySessionIdOrderBySortOrderAscIdAsc(ent.getId()), ent.getBaseBranch(),
                worktreeManager.branchFor(ent.getRequirementId(), ent.getId()), ent.getCreatedBy());
        if (specs.isEmpty()) {
            return;
        }
        String wsOwner = requireWorkspaceOwner(ent.getWorkspaceOwner() != null
                && !ent.getWorkspaceOwner().isBlank() ? ent.getWorkspaceOwner() : ent.getCreatedBy());
        WorkspaceReleaseResult result = connector.releaseWorkspace(nodeId, ent.getId(),
                ent.getProjectId(), wsOwner, specs, ent.getWorkspaceKey());
        if (!result.ok()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "释放节点固定工作区失败，会话未删除（可重试；或到节点手工删除该工作区后重试）: "
                            + result.error());
        }
        log.info("删除会话前已释放固定工作区: session={} detail={}", ent.getId(), result.detail());
    }

    /** CAP-51：会话是否绑定「需求级」工作区（req- 键；sid- 键仍按会话用完即弃）。 */
    private static boolean isRequirementScoped(SessionEntity ent) {
        String key = ent.getWorkspaceKey();
        return key != null && key.startsWith("req-");
    }

    // ---------------- CAP-51 FR-06：需求删除 → 释放需求工作树 ----------------

    /**
     * 释放专用单线程执行器：agent WS 每连接消息串行派发——需求删除事件是在
     * {@code RequirementService.delete} 的事务里同步发布的，而释放要阻塞等 runner ack（秒级），
     * 若同步跑在发起请求的线程/事件链上，ack 帧进不来必然 15s 超时（同
     * {@code RequirementFlowService.flowExecutor} 的同款事故，见 ws-event-chain-sync-launch-deadlock）。
     * 单线程保序且离开事件链。
     */
    private final java.util.concurrent.ExecutorService releaseExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "requirement-workspace-release");
                t.setDaemon(true);
                return t;
            });

    /**
     * CAP-51 FR-06：需求删除 → 释放该需求工作树（丢弃语义：不合并不 push）。异步执行，
     * <b>释放失败只告警不阻断删除</b>——需求已删，不能让用户卡在删除上；残留目录由 runner 侧
     * GC（无存活 pid + 超龄 + 无未提交改动 + 分支已推远端）兜底。
     */
    @org.springframework.context.event.EventListener
    public void onRequirementDeleted(RequirementDeletedEvent event) {
        releaseExecutor.submit(() -> releaseRequirementWorkspace(
                event.projectId(), event.requirementId(), event.workspaceOwner()));
    }

    /**
     * 需求工作树释放本体（包可见便于单测同步驱动；生产路径恒走 {@link #releaseExecutor}）：
     * 取该需求带 {@code req-<id>} 键的最近会话（键 + 仓库快照 + 归属用户/节点都从它读，
     * 需求行已删查不回来）→ 下发 {@code workspace_release}（带 key，runner 整块回收
     * {@code worktrees/<key>} 与本地需求分支）。任何失败只记日志 + 通知，绝不向外抛。
     */
    void releaseRequirementWorkspace(String projectId, String requirementId, String workspaceOwner) {
        try {
            String key = worktreeManager.workspaceKeyFor(requirementId, null);
            SessionEntity ref = sessionRepo.findByRequirementIdOrderByCreatedAtDesc(requirementId).stream()
                    .filter(s -> key.equals(s.getWorkspaceKey()))
                    .filter(s -> s.getAgentNodeId() != null && !s.getAgentNodeId().isBlank())
                    .findFirst()
                    .orElse(null);
            if (ref == null) {
                return; // 该需求从未开过工作区（或只有存量旧布局会话）：无 key 可释放
            }
            AgentNodeConnector connector = connectorProvider.getIfAvailable();
            if (connector == null) {
                return; // agent 模块未装配（裁剪部署/单测）：无节点可释放
            }
            List<AgentLaunchCommand.RepoSpec> specs = buildRepoSpecs(resolveProject(projectId),
                    sessionRepoRepo.findBySessionIdOrderBySortOrderAscIdAsc(ref.getId()),
                    ref.getBaseBranch(), worktreeManager.branchFor(requirementId, ref.getId()),
                    ref.getCreatedBy());
            if (specs.isEmpty()) {
                return;
            }
            String owner = workspaceOwner != null && !workspaceOwner.isBlank()
                    ? workspaceOwner
                    : (ref.getWorkspaceOwner() != null && !ref.getWorkspaceOwner().isBlank()
                    ? ref.getWorkspaceOwner() : ref.getCreatedBy());
            WorkspaceReleaseResult result = connector.releaseWorkspace(ref.getAgentNodeId(), ref.getId(),
                    projectId, requireWorkspaceOwner(owner), specs, ref.getWorkspaceKey());
            if (!result.ok()) {
                // 节点在线但释放失败（如目录被占用）：需求已删，只告警
                warnReleaseFailure(requirementId, result.error());
            } else {
                log.info("需求删除后已释放工作树: req={} key={} detail={}",
                        requirementId, ref.getWorkspaceKey(), result.detail());
            }
        } catch (Exception e) {
            // 节点离线（connector 抛 CONFLICT）/无仓库快照/归属名非法：一律只告警，目录由 GC 兜底
            warnReleaseFailure(requirementId, e.getMessage());
        }
    }

    /** 需求删除后的释放失败告警（日志 + 通知；FR-06 要求失败可见但不阻断删除）。 */
    private void warnReleaseFailure(String requirementId, String reason) {
        log.warn("需求删除后释放工作树失败(不阻断删除，目录由节点 GC 兜底): req={} err={}",
                requirementId, reason);
        try {
            notificationPublisher.publish(NotificationEvent.of("WORKSPACE_RELEASE_FAILED", null,
                    "需求工作区释放失败",
                    "需求 " + requirementId + " 已删除，但其节点工作树回收失败（" + reason
                            + "）。目录会在节点上闲置，超期由 GC 回收；如需立即清理请到节点手工删除。"));
        } catch (Exception e) {
            log.debug("释放失败通知发布失败(忽略): {}", e.getMessage());
        }
    }

    /**
     * 删除落库（独立事务，由 {@link #deleteSession} 在 WS 往返之后调用）——批删需要事务，
     * 而 self-invocation 走不到代理上的 {@code @Transactional}，故显式 TransactionTemplate
     * （同 {@link #restoreOnStartup}）。
     */
    private void purgeSession(String id) {
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            eventRepo.deleteBySessionId(id);
            sessionRepoRepo.deleteBySessionId(id);
            sessionRepo.findById(id).ifPresent(sessionRepo::delete);
        });
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
        releaseExecutor.shutdownNow();
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
                                                        String requirementId, String sessionId,
                                                        String baseBranch) {
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
        String branch = worktreeManager.branchFor(requirementId, sessionId);
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
     *
     * <p><b>CAP-51 FR-11 分支快照优先</b>：逐库分支取 {@code session_repos.branch} 落库快照，
     * 快照为空才用入参 {@code branch}（按需求维度推导的现算值）。命名规则变更后存量 CAP-42 会话的
     * 快照是 {@code feature/<sid>}，与现算值不符——收口/释放拿现算值去比对会被 runner 的
     * 「检出分支与会话分支不一致」挡下，故快照必优先。包可见便于单测。</p>
     */
    List<AgentLaunchCommand.RepoSpec> buildRepoSpecs(Project project, List<SessionRepoEntity> rows,
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
                String rowBranch = row.getBranch() != null && !row.getBranch().isBlank()
                        ? row.getBranch() : branch;
                out.add(new AgentLaunchCommand.RepoSpec(url, row.getBaseBranch(), rowBranch,
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
        // CAP-51：分支按需求维度推导（与 prepare/launch 同口径）
        if (rows.size() > 1) {
            workspaceService.cleanupSessionWorkspace(toWorkspaceSpecs(rows), ent.getRequirementId(),
                    ent.getId(), Path.of(ent.getWorktreePath()));
        } else {
            workspaceService.cleanupSessionWorkspace(project, ent.getRequirementId(), ent.getId(),
                    Path.of(ent.getWorktreePath()));
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

    // ---------------- CAP-42/CAP-51：固定工作区（归属用户 / 需求级互斥 / 释放） ----------------

    /** runner 工作区目录名白名单（与 RunnerWorkspace.SAFE_ID 同口径） */
    private static final java.util.regex.Pattern WS_OWNER_ID =
            java.util.regex.Pattern.compile("[a-zA-Z0-9._-]+");
    /** 工作区保留目录名（GC/对账扫描桶 + 固定目录名），owner 禁用防撞名；CAP-51 起加 worktrees 桶 */
    private static final java.util.Set<String> WS_RESERVED_DIRS =
            java.util.Set.of("main", "sessions", "builds", "_chat", "work", "worktrees");

    /**
     * CAP-51 FR-09 协议门控（创建/resume/收口/释放共用）：<b>带 workspaceKey</b> 时要求 v10+
     * （老 runner 忽略该字段会落回 {@code work/} 旧布局，把不同需求写进同一目录，故属「必须认识」）；
     * <b>缺 key</b> 时退回 CAP-42 的 v7 门控（存量会话旧布局，字段缺席 = 旧语义，见 FR-11）。
     */
    private static void requireWorkspaceProtocol(AgentNodeConnector connector, String nodeId,
                                                 String workspaceKey) {
        boolean keyed = workspaceKey != null && !workspaceKey.isBlank();
        if (keyed && !connector.supports(nodeId, AgentProtocol.REQUIREMENT_WORKSPACE)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 runner 版本过低，不支持需求粒度工作区（需协议 v10+），请升级该节点 runner");
        }
        if (!keyed && !connector.supports(nodeId, AgentProtocol.PER_USER_WORKSPACE)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 runner 版本过低，不支持每用户固定工作区（需协议 v7+），请升级该节点 runner");
        }
    }

    /**
     * CAP-51 FR-03 需求级互斥预检（服务端视角友好报错；runner 侧 {@code ensureUserWorktree} 的
     * 检出分支比对仍是磁盘残留的最终防线）：同需求存在<b>进行中</b>会话（RUNNING/WAITING_INPUT/
     * WAITING_AUTH/SUSPENDED）→ 409「该需求已有进行中的会话 X」。
     *
     * <p>判定用会话<b>状态</b>而非 workspace_state=OPEN：OPEN 是「工作区未收口」而非「有进程在跑」，
     * 需求内多会话共用一棵工作树，拿 OPEN 当互斥会把「分析会话结束后直接起开发会话」也挡掉
     * （CAP-51 验收 1 明确要求可直接复用，且 FR-09 已声明新逻辑不读会话行的 workspace_state）。</p>
     *
     * <p>selfSessionId = 被 resume 的会话自身，必须排除（它正是该需求的占用方，不排除必自撞）；
     * 无需求会话（sid- 键）不预检——键天生独占，见 FR-07。</p>
     */
    void precheckRequirementOccupancy(String requirementId, String selfSessionId) {
        if (requirementId == null || requirementId.isBlank()) {
            return;
        }
        for (SessionEntity s : sessionRepo.findByRequirementIdOrderByCreatedAtDesc(requirementId)) {
            if (selfSessionId != null && selfSessionId.equals(s.getId())) {
                continue;
            }
            SessionState st;
            try {
                st = SessionState.valueOf(s.getStatus());
            } catch (Exception e) {
                continue; // 状态值异常的历史行不参与互斥
            }
            if (st.isActive() || st == SessionState.SUSPENDED) {
                throw new DevMindException(ErrorCode.CONFLICT,
                        "该需求已有进行中的会话 " + s.getId() + "，请先结束它或等待完成后再开新会话");
            }
        }
    }

    /**
     * CAP-42 FR-06：repo 会话固定工作区归属用户名。
     * 有登录态 = 当前操作者；无登录态（CAP-15/17 编排派发等异步链路，actor 回退 local）按
     * WI.ownerId → 需求.ownerId → WI.createdBy → 需求.createdBy 回退链解析真实用户名，
     * 解析不到 409 fail-visible（不落 local——多 WI 并发会全撞 &lt;proj&gt;/local/work）。
     */
    private String resolveWorkspaceOwner(WorkItemEntity workItem, RequirementEntity requirement) {
        String actor = identityService.currentActor();
        if (!com.devmind.auth.IdentityService.LOCAL_USER.equals(actor)) {
            return actor;
        }
        for (String candidate : new String[]{
                workItem != null ? workItem.getOwnerId() : null,
                requirement != null ? requirement.getOwnerId() : null,
                workItem != null ? workItem.getCreatedBy() : null,
                requirement != null ? requirement.getCreatedBy() : null}) {
            if (candidate != null && !candidate.isBlank()
                    && !com.devmind.auth.IdentityService.LOCAL_USER.equals(candidate)) {
                return candidate;
            }
        }
        throw new DevMindException(ErrorCode.CONFLICT,
                "无法确定工作区归属用户（无登录态且工作单元/需求均未指定负责人），"
                        + "请先为工作单元/需求指定负责人再派发会话");
    }

    /** CAP-42 FR-07：归属用户名白名单 + 保留名校验（存量非法用户名在此 fail-visible） */
    private static String requireWorkspaceOwner(String owner) {
        if (owner == null || !WS_OWNER_ID.matcher(owner).matches() || WS_RESERVED_DIRS.contains(owner)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "用户名「" + owner + "」不能作为 runner 工作区目录名"
                            + "（需匹配 [a-zA-Z0-9._-] 且非保留名 main/sessions/builds/_chat/work），"
                            + "请联系管理员调整用户名");
        }
        return owner;
    }

    /**
     * CAP-33 FR-02：装配上下文包（三层合并）。场景绑定的资产失效（DevMindException 404）
     * 向上传播 fail-visible；其它装配异常降级为 null = 无上下文启动（沿用注入不阻塞语义）。
     *
     * @param lean CAP-52 FR-05 瘦上下文开关，取 {@link SessionContextService#isExecutionSession}
     */
    private SessionContextService.Prepared prepareContext(String sessionId, Project project,
                                                          SessionScenarioEntity scenario,
                                                          String renderedTaskSpec,
                                                          List<String> extraSkillIds,
                                                          List<Long> extraDocIds,
                                                          List<String> extraKnowledgeTags,
                                                          String requirementId,
                                                          boolean lean) {
        try {
            return sessionContextService.prepare(sessionId, project, scenario, renderedTaskSpec,
                    extraSkillIds, extraDocIds, extraKnowledgeTags, requirementId, lean);
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

    // ---------------- CAP-39 会话产出读取/按需回传 ----------------

    /** 列出已回传的产出文件（session_outputs 落库内容，按文件名排序）。 */
    public List<OutputFileView> listOutputs(String id) {
        requireEntity(id);
        return outputService.list(id).stream()
                .map(e -> new OutputFileView(e.getFileName(),
                        e.getContent() == null ? 0
                                : e.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                        e.getCreatedAt()))
                .sorted(java.util.Comparator.comparing(OutputFileView::fileName))
                .toList();
    }

    /** 读产出内容；会话/产出不存在 404。 */
    public OutputContentView getOutputContent(String id, String fileName) {
        requireEntity(id);
        String content = outputService.findContent(id, fileName)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "产出不存在: " + fileName + "（可尝试「从节点同步」后再查看）"));
        return new OutputContentView(fileName, content);
    }

    /**
     * 触发 runner 即时回传产出（CAP-39 FR-01/02 collect_output 帧）。恒返回当前已存列表：
     * 历史本机会话/节点离线/老 runner/ runner 侧失败都降级为 message 提示（前端一次调用拿到
     * 最新列表与提示），仅会话不存在抛 404。
     */
    public CollectResultView collectOutputs(String id) {
        SessionEntity ent = requireEntity(id);
        boolean collected = false;
        String message = null;
        if (ent.getAgentNodeId() == null || ent.getAgentNodeId().isBlank()) {
            message = "历史本机会话无执行节点可同步，仅展示已回传产出";
        } else {
            AgentNodeConnector connector = connectorProvider.getIfAvailable();
            if (connector == null || !connector.isOnline(ent.getAgentNodeId())) {
                message = "执行节点不在线，仅展示已回传产出";
            } else {
                try {
                    AgentCollectResult ack = connector.collectOutput(ent.getAgentNodeId(), id);
                    collected = ack.ok();
                    if (!ack.ok()) {
                        message = "节点未回传新产出：" + (ack.error() == null ? "未知原因" : ack.error());
                    }
                } catch (DevMindException e) {
                    message = e.getMessage() + "；仅展示已回传产出";
                }
            }
        }
        return new CollectResultView(collected, message, listOutputs(id));
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
                ent.getCreatedBy(), ent.getWorkspaceState(),
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
