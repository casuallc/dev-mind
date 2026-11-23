package com.devmind.session.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.TerminalWsPrepareResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.project.RequirementService;
import com.devmind.project.WorkItemService;
import com.devmind.project.WorktreeManager;
import com.devmind.project.dto.RepoView;
import com.devmind.project.model.Project;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.session.dto.TerminalWorkspaceAttachRequest;
import com.devmind.session.dto.TerminalWorkspaceCreateRequest;
import com.devmind.session.dto.TerminalWorkspaceView;
import com.devmind.session.model.SessionRepoEntity;
import com.devmind.session.model.TerminalWorkspaceEntity;
import com.devmind.session.repo.TerminalWorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * CAP-115 终端工作区生命周期（M1 登记/挂接/锁；M2 终端会话通道；M3 收口/释放治理解耦后接入）。
 *
 * <p>新建：{@code term-<8位小写短id>} 键 + {@code terminal/<短id>} 分支，经
 * {@code terminal_ws_prepare} 帧（协议 v26 门控）让 runner 幂等物化键控工作区后才落库——
 * 物化失败不落脏行。挂接：既有键（req-/sid-）登记为终端工作区，与编码会话双向互斥
 * （本类正向预检 + {@link SessionManagerService#create} 反向预检）。</p>
 */
@Service
public class TerminalWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(TerminalWorkspaceService.class);

    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_FINALIZED = "FINALIZED";
    public static final String STATE_RELEASED = "RELEASED";

    /** 锁新鲜度窗口：与 M2 心跳节奏（30s 一跳）对齐，90s 无续期视为可抢占 */
    static final Duration LOCK_FRESH = Duration.ofSeconds(90);

    /** 工作区键白名单（与 RunnerWorkspace.SAFE_ID / SessionManagerService.WS_OWNER_ID 同口径） */
    private static final java.util.regex.Pattern WS_KEY_ID =
            java.util.regex.Pattern.compile("[a-zA-Z0-9._-]+");

    private final IdentityService identityService;
    private final ProjectService projectService;
    private final RequirementService requirementService;
    private final WorkItemService workItemService;
    private final WorktreeManager worktreeManager;
    private final SessionManagerService sessionManagerService;
    private final TerminalWorkspaceRepository terminalWorkspaceRepo;
    private final ObjectProvider<AgentNodeConnector> connectorProvider;
    private final ObjectMapper mapper;

    public TerminalWorkspaceService(IdentityService identityService,
                                    ProjectService projectService,
                                    RequirementService requirementService,
                                    WorkItemService workItemService,
                                    WorktreeManager worktreeManager,
                                    SessionManagerService sessionManagerService,
                                    TerminalWorkspaceRepository terminalWorkspaceRepo,
                                    ObjectProvider<AgentNodeConnector> connectorProvider,
                                    ObjectMapper mapper) {
        this.identityService = identityService;
        this.projectService = projectService;
        this.requirementService = requirementService;
        this.workItemService = workItemService;
        this.worktreeManager = worktreeManager;
        this.sessionManagerService = sessionManagerService;
        this.terminalWorkspaceRepo = terminalWorkspaceRepo;
        this.connectorProvider = connectorProvider;
        this.mapper = mapper;
    }

    // ---------------- 查询 ----------------

    public List<TerminalWorkspaceView> list(String projectId) {
        return terminalWorkspaceRepo.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(this::toView).toList();
    }

    public TerminalWorkspaceView get(String id) {
        return toView(require(id));
    }

    /** package-private 供 M2/M3 服务复用 */
    TerminalWorkspaceEntity require(String id) {
        return terminalWorkspaceRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "终端工作区不存在: " + id));
    }

    /** package-private：可空查找（M2 巡检按会话回查工作区，不存在 = 跳过不抛） */
    TerminalWorkspaceEntity find(String id) {
        return id == null ? null : terminalWorkspaceRepo.findById(id).orElse(null);
    }

    /** package-private：M2/M3 会话通道直接落工作区行（锁/活跃时间等字段级更新） */
    TerminalWorkspaceEntity save(TerminalWorkspaceEntity ent) {
        return terminalWorkspaceRepo.save(ent);
    }

    // ---------------- 新建 / 挂接 ----------------

    /**
     * 新建终端工作区：term-<短id> 键 + terminal/<短id> 分支，仓库快照 = 项目全量仓库
     * （主库在前），下发 runner 幂等物化成功后落库。
     */
    public TerminalWorkspaceView create(TerminalWorkspaceCreateRequest req) {
        // CAP-13 关联约定：workItemId/requirementId 与 projectId 一致性校验 + projectId 反推
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
        }
        Project project = projectService.requireProject(projectId);
        String actor = currentLoginUser();
        String id = UUID.randomUUID().toString();
        String shortId = id.replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
        String workspaceKey = worktreeManager.workspaceKeyForTerminal(shortId);
        String branch = worktreeManager.branchForTerminal(workspaceKey);
        String baseBranch = req.baseBranch() != null && !req.baseBranch().isBlank()
                ? req.baseBranch() : project.baseBranch();
        return provision(null, project, requirement, workItem, workspaceKey, branch, baseBranch,
                req.agentNodeId(), actor, id);
    }

    /**
     * 挂接既有工作区键为终端工作区：req-<需求id>（与编码会话互斥——同需求有进行中/挂起
     * 编码会话 409）/sid-<会话id>/term-<短id>（释放后再挂）。同键已有 OPEN 行 → 409。
     */
    public TerminalWorkspaceView attach(TerminalWorkspaceAttachRequest req) {
        String key = req.workspaceKey() == null ? "" : req.workspaceKey().trim();
        if (!WS_KEY_ID.matcher(key).matches()
                || !(key.startsWith("req-") || key.startsWith("sid-") || key.startsWith("term-"))) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "非法工作区键（需 req-/sid-/term- 前缀且落在 [a-zA-Z0-9._-]）: " + key);
        }
        String requirementId = key.startsWith("req-") ? key.substring("req-".length()) : null;
        RequirementEntity requirement = requirementId != null
                ? requirementService.requireById(requirementId) : null;
        if (requirementId != null) {
            // 与编码会话正向互斥（反向预检在 SessionManagerService.create）
            sessionManagerService.precheckRequirementOccupancy(requirementId, null);
        }
        String projectId = requirement != null ? requirement.getProjectId() : req.projectId();
        Project project = projectService.requireProject(projectId);
        String branch = key.startsWith("req-")
                ? worktreeManager.branchFor(requirementId, null)
                : key.startsWith("term-")
                ? worktreeManager.branchForTerminal(key)
                : "feature/" + key.substring("sid-".length());
        String baseBranch = req.baseBranch() != null && !req.baseBranch().isBlank()
                ? req.baseBranch() : project.baseBranch();
        String actor = currentLoginUser();
        return provision(key, project, requirement, null, key, branch, baseBranch,
                req.agentNodeId(), actor, UUID.randomUUID().toString());
    }

    /**
     * 物化 + 落库共用主流程：同键 OPEN 预检 → 节点路由（显式 > 项目默认 > 平台默认）→
     * 仓库快照 → 带凭据 specs → terminal_ws_prepare（v26 门控在 registry，409 话术引导升级）
     * → 物化成功才落库（attach=true 时复传入 key）。
     */
    private TerminalWorkspaceView provision(String attachKey, Project project,
                                            RequirementEntity requirement, WorkItemEntity workItem,
                                            String workspaceKey, String branch, String baseBranch,
                                            String explicitNodeId, String actor, String id) {
        // 同键 OPEN 行预检（attach 键释放后可再挂，故只挡 OPEN）
        for (TerminalWorkspaceEntity tw : terminalWorkspaceRepo.findByWorkspaceKey(workspaceKey)) {
            if (STATE_OPEN.equals(tw.getState())) {
                throw new DevMindException(ErrorCode.CONFLICT,
                        "工作区键 " + workspaceKey + " 已有打开中的终端工作区（创建人 "
                                + tw.getCreatedBy() + "），请先收口或释放");
            }
        }
        String owner = SessionManagerService.requireWorkspaceOwner(actor);
        AgentNodeConnector connector = requireConnector();
        String projectDefault = project.agentNodeId() != null && !project.agentNodeId().isBlank()
                ? project.agentNodeId() : null;
        String agentNodeId = SessionManagerService.routeAgentNode(explicitNodeId, null,
                projectDefault, connector.defaultNodeId(), List.of(), connector, null);

        List<RepoView> repos = new ArrayList<>(projectService.listRepos(project.id()));
        if (repos.isEmpty()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "项目 " + project.id() + " 未登记仓库，无法创建终端工作区");
        }
        repos.sort(Comparator.comparing((RepoView r) -> !r.primary())
                .thenComparingInt(RepoView::sortOrder));
        // 快照行（瞬态实体，仅喂 buildRepoSpecs 与 repos_json 序列化，不落 session_repos）
        List<SessionRepoEntity> rows = new ArrayList<>();
        int order = 0;
        for (RepoView r : repos) {
            SessionRepoEntity e = new SessionRepoEntity();
            e.setProjectRepoId(r.id());
            e.setName(r.name());
            e.setRemoteUrl(r.remoteUrl());
            e.setLocalPath(r.path());
            e.setBaseBranch(r.primary()
                    ? baseBranch
                    : (r.defaultBranch() != null && !r.defaultBranch().isBlank()
                            ? r.defaultBranch() : baseBranch));
            e.setBranch(branch);
            e.setIsPrimary(r.primary());
            e.setSortOrder(order++);
            rows.add(e);
        }
        List<AgentLaunchCommand.RepoSpec> specs = sessionManagerService.buildRepoSpecs(
                project, rows, baseBranch, branch, actor);
        if (specs.isEmpty()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "无可用远程仓库（未登记 remoteUrl 或凭据解析失败），无法物化终端工作区");
        }
        TerminalWsPrepareResult prepared = connector.terminalWorkspacePrepare(
                agentNodeId, workspaceKey, project.id(), owner, specs);
        if (!prepared.ok()) {
            throw new DevMindException(ErrorCode.CONFLICT, "终端工作区物化失败: " + prepared.error());
        }

        Instant now = Instant.now();
        TerminalWorkspaceEntity ent = new TerminalWorkspaceEntity();
        ent.setId(id);
        ent.setProjectId(project.id());
        ent.setRequirementId(requirement != null ? requirement.getId() : null);
        ent.setWorkItemId(workItem != null ? workItem.getId() : null);
        ent.setWorkspaceKey(workspaceKey);
        ent.setBranch(branch);
        ent.setOwner(owner);
        ent.setAgentNodeId(agentNodeId);
        ent.setBaseBranch(baseBranch);
        ent.setReposJson(serializeSnapshot(rows));
        ent.setState(STATE_OPEN);
        ent.setLastActiveAt(now);
        ent.setCreatedBy(actor);
        ent.setCreatedAt(now);
        terminalWorkspaceRepo.save(ent);
        log.info("终端工作区{}: key={} project={} node={} path={}",
                attachKey != null ? "挂接" : "新建", workspaceKey, project.id(), agentNodeId,
                prepared.path());
        return toView(ent);
    }

    // ---------------- 锁（M2 终端会话 open/heartbeat 复用） ----------------

    /**
     * 加锁：他人持锁且锁新鲜（90s 内有续期）→ 409 带占用方；锁过期 → 抢占（旧持锁人
     * 失联兜底）。自己重复加锁 = 续期。
     */
    public TerminalWorkspaceView lock(String id) {
        return lockInternal(require(id), null);
    }

    /** package-private：M2 open 用 terminalSessionId 落 lock_session_id */
    TerminalWorkspaceView lockInternal(TerminalWorkspaceEntity ent, String terminalSessionId) {
        requireOpen(ent);
        String actor = currentLoginUser();
        Instant now = Instant.now();
        if (ent.getLockOwner() != null && !ent.getLockOwner().equals(actor)
                && ent.getLockAt() != null
                && ent.getLockAt().isAfter(now.minus(LOCK_FRESH))) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "终端工作区正被 " + ent.getLockOwner() + " 占用（90s 无心跳才允许抢占）");
        }
        ent.setLockSessionId(terminalSessionId);
        ent.setLockOwner(actor);
        ent.setLockAt(now);
        ent.setLastActiveAt(now);
        return toView(terminalWorkspaceRepo.save(ent));
    }

    /** 解锁：本人或 admin；未持锁 = 幂等 no-op。 */
    public TerminalWorkspaceView unlock(String id) {
        TerminalWorkspaceEntity ent = require(id);
        String actor = currentLoginUser();
        if (ent.getLockOwner() != null && !ent.getLockOwner().equals(actor) && !isAdmin()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "终端工作区正被 " + ent.getLockOwner() + " 占用，仅本人或管理员可解锁");
        }
        ent.setLockSessionId(null);
        ent.setLockOwner(null);
        ent.setLockAt(null);
        return toView(terminalWorkspaceRepo.save(ent));
    }

    /** package-private 供 M2/M3 复用 */
    void requireOpen(TerminalWorkspaceEntity ent) {
        if (!STATE_OPEN.equals(ent.getState())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "终端工作区已" + (STATE_FINALIZED.equals(ent.getState()) ? "收口" : "释放")
                            + "（" + ent.getState() + "），不可再操作");
        }
    }

    /** package-private：当前登录用户（无登录态 409——终端工作区归属必须真实用户） */
    String currentLoginUser() {
        String actor = identityService.currentActor();
        if (IdentityService.LOCAL_USER.equals(actor)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "无登录态，无法确定终端工作区归属用户");
        }
        return actor;
    }

    /** 当前操作者是否 admin（解锁越权判定用；异常按非 admin 兜底） */
    boolean isAdmin() {
        try {
            return identityService.currentUser()
                    .map(u -> com.devmind.auth.model.UserEntity.ROLE_ADMIN.equals(u.getRole()))
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- 内部 ----------------

    private AgentNodeConnector requireConnector() {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "远程 agent 模块未装配，无法使用终端工作区");
        }
        return connector;
    }

    /** repos_json 快照序列化（字段对齐 SessionRepoEntity 列名口径的 camelCase） */
    private String serializeSnapshot(List<SessionRepoEntity> rows) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (SessionRepoEntity r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectRepoId", r.getProjectRepoId());
            m.put("name", r.getName());
            m.put("remoteUrl", r.getRemoteUrl());
            m.put("localPath", r.getLocalPath());
            m.put("baseBranch", r.getBaseBranch());
            m.put("branch", r.getBranch());
            m.put("isPrimary", r.getIsPrimary());
            m.put("sortOrder", r.getSortOrder());
            data.add(m);
        }
        return mapper.writeValueAsString(data);
    }

    @SuppressWarnings("unchecked")
    TerminalWorkspaceView toView(TerminalWorkspaceEntity ent) {
        List<Map<String, Object>> repos = List.of();
        if (ent.getReposJson() != null && !ent.getReposJson().isBlank()) {
            try {
                repos = mapper.readValue(ent.getReposJson(), List.class);
            } catch (Exception e) {
                log.warn("终端工作区仓库快照解析失败: id={} err={}", ent.getId(), e.getMessage());
            }
        }
        return new TerminalWorkspaceView(ent.getId(), ent.getProjectId(), ent.getRequirementId(),
                ent.getWorkItemId(), ent.getWorkspaceKey(), ent.getBranch(), ent.getOwner(),
                ent.getAgentNodeId(), ent.getBaseBranch(), ent.getState(),
                ent.getLockSessionId(), ent.getLockOwner(), ent.getLockAt(),
                ent.getLastActiveAt(), ent.getCreatedBy(), ent.getCreatedAt(),
                ent.getFinalizedAt(), repos);
    }
}
