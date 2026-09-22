package com.devmind.session.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.FinalizeResult;
import com.devmind.common.agent.WorkspaceReleaseResult;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.project.ProjectService;
import com.devmind.project.RequirementService;
import com.devmind.project.WorktreeManager;
import com.devmind.project.config.WorktreeProperties;
import com.devmind.project.model.Project;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.model.RequirementEntity;
import com.devmind.session.config.SessionProperties;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionRepoEntity;
import com.devmind.session.repo.SessionEventRepository;
import com.devmind.session.repo.SessionRepoRepository;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.runtime.SessionEventSaver;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-51 M1 服务端：需求级工作区键/分支解析（快照优先）、需求级互斥预检、需求级收口门禁与落库、
 * 需求删除后的工作树释放。依赖全用内存 fake/代理（同 ReconcileTest 手法），不起服务。
 */
class SessionRequirementWorkspaceTest {

    private static final String NODE = "node-1";
    private static final String PROJECT = "p1";
    private static final String REQ = "r1";
    private static final String REQ_BRANCH = "feature/req-" + REQ;
    private static final String REQ_KEY = "req-" + REQ;

    // ---------------- harness ----------------

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    /** deleteSession 走 purgeSession 的显式 TransactionTemplate（self-invocation 到不了代理），需真事务管理器。 */
    private static final PlatformTransactionManager TX = proxy(PlatformTransactionManager.class,
            (p, m, args) -> switch (m.getName()) {
                case "getTransaction" -> new SimpleTransactionStatus();
                case "commit", "rollback" -> null;
                default -> throw new UnsupportedOperationException(m.getName());
            });

    private static SessionRepository sessionRepo(Map<String, SessionEntity> store) {
        return proxy(SessionRepository.class, (p, m, args) -> switch (m.getName()) {
            case "save" -> {
                SessionEntity e = (SessionEntity) args[0];
                store.put(e.getId(), e);
                yield e;
            }
            case "findById" -> Optional.ofNullable(store.get((String) args[0]));
            case "delete" -> {
                store.remove(((SessionEntity) args[0]).getId());
                yield null;
            }
            case "findByRequirementIdOrderByCreatedAtDesc" -> store.values().stream()
                    .filter(e -> args[0].equals(e.getRequirementId()))
                    .sorted(Comparator.comparing(SessionEntity::getCreatedAt).reversed())
                    .toList();
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    /** 仓库快照行：http 远端（可解析 token）+ 指定分支（null = 走现算分支）。 */
    private static SessionRepoEntity repoRow(String sessionId, String branch) {
        SessionRepoEntity r = new SessionRepoEntity();
        r.setSessionId(sessionId);
        r.setName("app");
        r.setRemoteUrl("https://git.example.com/p/app.git");
        r.setBranch(branch);
        r.setBaseBranch("main");
        r.setIsPrimary(true);
        r.setSortOrder(0);
        r.setCreatedAt(Instant.now());
        return r;
    }

    /** 只覆盖工作区路径用得到的依赖，其余传 null（构造全链路成本过高）。 */
    static class FakeProjectService extends ProjectService {
        FakeProjectService() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public Project requireProject(String projectId) {
            return new Project(projectId, "app", ".", "main", List.of(), null,
                    ProjectEntity.KIND_NORMAL, null);
        }
    }

    static class FakeRequirementService extends RequirementService {
        final Map<String, RequirementEntity> store = new HashMap<>();
        final List<String> finalized = new ArrayList<>();

        FakeRequirementService() {
            super(null, null, null, null, null, null, null, null, null);
        }

        void add(RequirementEntity e) {
            store.put(e.getId(), e);
        }

        @Override
        public RequirementEntity requireEntity(String projectId, String requirementId) {
            RequirementEntity e = store.get(requirementId);
            if (e == null) {
                throw new DevMindException(ErrorCode.NOT_FOUND, "需求不存在: " + requirementId);
            }
            return e;
        }

        @Override
        public void markWorkspaceOpen(String requirementId, String workspaceOwner) {
            RequirementEntity e = store.get(requirementId);
            if (e != null) {
                if (e.getWorkspaceOwner() == null) {
                    e.setWorkspaceOwner(workspaceOwner);
                }
                e.setWorkspaceState(RequirementEntity.WORKSPACE_OPEN);
            }
        }

        @Override
        public void markWorkspaceFinalized(String requirementId) {
            finalized.add(requirementId);
            RequirementEntity e = store.get(requirementId);
            if (e != null) {
                e.setWorkspaceState(RequirementEntity.WORKSPACE_FINALIZED);
            }
        }
    }

    static class FakeIdentity extends IdentityService {
        String actor = "u1";

        FakeIdentity() {
            super(null, null, null);
        }

        @Override
        public String currentActor() {
            return actor;
        }

        @Override
        public Optional<com.devmind.auth.model.UserEntity> currentUser() {
            return Optional.empty();
        }
    }

    /** 可控节点连接：supports 恒 true（v10），finalize/release 可按需失败或记录。 */
    static class FakeConnector implements AgentNodeConnector {
        String finalizeError;
        String releaseError;
        boolean releaseThrowsOffline;
        final List<AgentLaunchCommand.RepoSpec> finalizeSpecs = new ArrayList<>();
        String finalizeKey;
        String finalizeOwner;
        GitIdentityProvider.GitAuthor finalizeOperator;
        String launchKey;
        final List<String> releasedKeys = new ArrayList<>();
        int releaseCalls;
        /** 最近一次 releaseWorkspace 的 deleteRemoteBranch 标志（FR-06 终态清理断言用） */
        boolean lastReleaseDeleteRemote;

        @Override
        public boolean isOnline(String nodeId) {
            return !releaseThrowsOffline;
        }

        @Override
        public String defaultNodeId() {
            return NODE;
        }

        @Override
        public boolean supports(String nodeId, int minVersion) {
            return true;
        }

        @Override
        public void launch(String nodeId, AgentLaunchCommand cmd) {
            launchKey = cmd.workspaceKey();
        }

        @Override
        public void sendInput(String nodeId, String sessionId, String text) {
        }

        @Override
        public void sendAuthorize(String nodeId, String sessionId, String requestId, boolean accepted, String scope) {
        }

        @Override
        public void sendFinish(String nodeId, String sessionId) {
        }

        @Override
        public void sendKill(String nodeId, String sessionId) {
        }

        @Override
        public void sendSuspend(String nodeId, String sessionId) {
        }

        @Override
        public FinalizeResult finalizeWorkspace(String nodeId, String sessionId, String projectId,
                                                String workspaceOwner,
                                                List<AgentLaunchCommand.RepoSpec> specs,
                                                boolean discardChanges, String workspaceKey,
                                                GitIdentityProvider.GitAuthor operator) {
            finalizeOwner = workspaceOwner;
            finalizeKey = workspaceKey;
            finalizeOperator = operator;
            finalizeSpecs.clear();
            finalizeSpecs.addAll(specs);
            return finalizeError != null ? FinalizeResult.failed(finalizeError) : FinalizeResult.ok("ok");
        }

        @Override
        public WorkspaceReleaseResult releaseWorkspace(String nodeId, String sessionId, String projectId,
                                                       String workspaceOwner,
                                                       List<AgentLaunchCommand.RepoSpec> specs,
                                                       String workspaceKey) {
            return releaseWorkspace(nodeId, sessionId, projectId, workspaceOwner, specs,
                    workspaceKey, false);
        }

        @Override
        public WorkspaceReleaseResult releaseWorkspace(String nodeId, String sessionId, String projectId,
                                                       String workspaceOwner,
                                                       List<AgentLaunchCommand.RepoSpec> specs,
                                                       String workspaceKey, boolean deleteRemoteBranch) {
            releaseCalls++;
            lastReleaseDeleteRemote = deleteRemoteBranch;
            if (releaseThrowsOffline) {
                throw new DevMindException(ErrorCode.CONFLICT, "节点不在线: " + nodeId);
            }
            releasedKeys.add(workspaceKey);
            return releaseError != null
                    ? WorkspaceReleaseResult.failed(releaseError) : WorkspaceReleaseResult.ok("ok");
        }
    }

    private final FakeRequirementService requirementService = new FakeRequirementService();
    private final FakeIdentity identity = new FakeIdentity();
    private final Map<String, SessionEntity> store = new HashMap<>();
    private SessionManagerService service;
    private List<SessionRepoEntity> repoRows = List.of();

    private SessionManagerService build(FakeConnector connector) {
        SessionRepository repo = sessionRepo(store);
        SessionEventRepository eventRepo = proxy(SessionEventRepository.class, (p, m, args) ->
                "saveAll".equals(m.getName()) ? args[0] : null);
        SessionProperties props = new SessionProperties();
        SessionEventSaver saver = new SessionEventSaver(eventRepo, props, JsonMapper.builder().build());
        var connectorProvider = proxy(org.springframework.beans.factory.ObjectProvider.class,
                (p, m, args) -> "getIfAvailable".equals(m.getName()) ? connector : null);
        var gatewayProvider = proxy(org.springframework.beans.factory.ObjectProvider.class,
                (p, m, args) -> "getIfAvailable".equals(m.getName())
                        ? proxy(com.devmind.common.integration.RepoGitGateway.class,
                        (pp, mm, aa) -> "resolveToken".equals(mm.getName())
                                ? Optional.of("tok") : Boolean.TRUE)
                        : null);
        var repoRowRepo = proxy(SessionRepoRepository.class, (p, m, args) -> switch (m.getName()) {
            case "findBySessionIdOrderBySortOrderAscIdAsc" -> repoRows;
            case "saveAll" -> args[0];
            case "deleteBySessionId" -> null;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        service = new SessionManagerService(identity, new FakeProjectService(), null, requirementService,
                new WorktreeManager(new WorktreeProperties(), null), null, null,
                e -> { }, new DomainEventPublisher(ev -> { }),
                repo, eventRepo, null, repoRowRepo, saver, props, JsonMapper.builder().build(),
                connectorProvider, null, gatewayProvider, null, TX, null);
        return service;
    }

    private RequirementEntity requirement(String createdBy, String ownerId) {
        RequirementEntity r = new RequirementEntity();
        r.setId(REQ);
        r.setProjectId(PROJECT);
        r.setSeq(1L);
        r.setTitle("登录优化");
        r.setStatus(RequirementEntity.STATUS_IN_PROGRESS);
        r.setCreatedBy(createdBy);
        r.setOwnerId(ownerId);
        r.setWorkspaceOwner("u1");
        r.setWorkspaceState(RequirementEntity.WORKSPACE_OPEN);
        requirementService.add(r);
        return r;
    }

    private SessionEntity session(String id, String status, String requirementId, String workspaceKey) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setProjectId(PROJECT);
        e.setRequirementId(requirementId);
        e.setStatus(status);
        e.setAgentNodeId(NODE);
        e.setWorkspaceOwner("u1");
        e.setWorkspaceKey(workspaceKey);
        e.setWorkspaceState(SessionEntity.WORKSPACE_OPEN);
        e.setCreatedBy("u1");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        store.put(id, e);
        return e;
    }

    // ---------------- ① 分支/键解析与快照优先 ----------------

    /** 快照优先：session_repos.branch 非空时用它（存量 CAP-42 会话是 feature/<sid>，现算值会不一致）。 */
    @Test
    void 分支快照优先于现算值() {
        build(new FakeConnector());

        var specs = service.buildRepoSpecs(null, List.of(repoRow("s1", "feature/s1")),
                "main", REQ_BRANCH, "u1");

        assertEquals(1, specs.size());
        assertEquals("feature/s1", specs.get(0).branch(), "快照分支必须优先于现算值（FR-11）");
    }

    /** 快照为空才按需求维度现算（新会话/手工造的旧行）。 */
    @Test
    void 快照缺失才用现算分支() {
        build(new FakeConnector());

        var specs = service.buildRepoSpecs(null, List.of(repoRow("s1", null)),
                "main", REQ_BRANCH, "u1");

        assertEquals(1, specs.size());
        assertEquals(REQ_BRANCH, specs.get(0).branch());
    }

    // ---------------- ①.5 异步链路会话归属回退（resolveSessionActor） ----------------

    /** 有登录态（请求线程）：归属当前操作者，不看 WI/需求字段。 */
    @Test
    void 有登录态时归属当前操作者() {
        build(new FakeConnector());
        identity.actor = "u9";

        assertEquals("u9", service.resolveSessionActor(null, requirement("u1", "u2")));
    }

    /** 异步链路（流程分流线程，actor=local）：回退需求创建者，createdBy/提交身份不再落 local。 */
    @Test
    void actor为local时回退需求创建者() {
        build(new FakeConnector());
        identity.actor = IdentityService.LOCAL_USER;

        assertEquals("u1", service.resolveSessionActor(null, requirement("u1", null)));
    }

    /** 回退链优先级：WI.ownerId > 需求.ownerId > WI.createdBy > 需求.createdBy。 */
    @Test
    void actor为local时优先工作单元负责人() {
        build(new FakeConnector());
        identity.actor = IdentityService.LOCAL_USER;
        com.devmind.project.model.WorkItemEntity wi = new com.devmind.project.model.WorkItemEntity();
        wi.setOwnerId("wi-owner");
        wi.setCreatedBy("wi-creator");

        assertEquals("wi-owner", service.resolveSessionActor(wi, requirement("req-creator", "req-owner")));
    }

    /** 全链路都解析不到：保持 local 不抛错（repo 会话的 409 由 resolveWorkspaceOwner 负责）。 */
    @Test
    void 归属全空时保持local不抛错() {
        build(new FakeConnector());
        identity.actor = IdentityService.LOCAL_USER;

        assertEquals(IdentityService.LOCAL_USER, service.resolveSessionActor(null, requirement(null, null)));
        assertEquals(IdentityService.LOCAL_USER, service.resolveSessionActor(null, null));
    }

    // ---------------- ② 需求级互斥预检 ----------------

    @Test
    void 同需求有进行中会话则409() {
        build(new FakeConnector());
        session("s-run", "RUNNING", REQ, REQ_KEY);

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.precheckRequirementOccupancy(REQ, null));
        assertTrue(e.getMessage().contains("该需求已有进行中的会话"), e.getMessage());
        assertTrue(e.getMessage().contains("s-run"), e.getMessage());
    }

    /** resume 自身必须排除（被 resume 的会话正是该需求的占用方，不排除必自撞）。 */
    @Test
    void resume自身不判占用() {
        build(new FakeConnector());
        session("s-susp", "SUSPENDED", REQ, REQ_KEY);

        assertDoesNotThrow(() -> service.precheckRequirementOccupancy(REQ, "s-susp"));
    }

    /** resume 时同需求另有进行中会话仍然互斥（需求内串行）。 */
    @Test
    void resume时同需求他人在跑仍409() {
        build(new FakeConnector());
        session("s-a", "SUSPENDED", REQ, REQ_KEY);
        session("s-b", "RUNNING", REQ, REQ_KEY);

        assertThrows(DevMindException.class, () -> service.precheckRequirementOccupancy(REQ, "s-a"));
    }

    /** 不同需求互不感知（跨需求可并行，验收 1）。 */
    @Test
    void 不同需求互斥放行() {
        build(new FakeConnector());
        session("s-run", "RUNNING", "r2", "req-r2");

        assertDoesNotThrow(() -> service.precheckRequirementOccupancy(REQ, null));
    }

    /** 无需求会话不预检（sid- 键天生独占，FR-07）。 */
    @Test
    void 无需求会话不预检() {
        build(new FakeConnector());
        session("s-run", "RUNNING", null, "sid-s-run");

        assertDoesNotThrow(() -> service.precheckRequirementOccupancy(null, null));
    }

    /**
     * 关键：需求内多会话串行复用——前一个会话已结束（DONE，工作区仍 OPEN 未收口）时
     * <b>不</b>互斥，否则 CAP-51 验收 1「A 结束后直接起第二个会话复用工作树」无法成立
     * （正是 CAP-42 被取代的痛点；FR-03 判定用会话状态，FR-09 声明新逻辑不读会话行 workspace_state）。
     */
    @Test
    void 已结束会话不占位可复用工作树() {
        build(new FakeConnector());
        session("s-done", "DONE", REQ, REQ_KEY);

        assertDoesNotThrow(() -> service.precheckRequirementOccupancy(REQ, null));
    }

    // ---------------- ④ 需求级收口 ----------------

    @Test
    void 需求无OPEN会话时收口409() {
        build(new FakeConnector());
        requirement("u1", null);
        store.put("s-done", session("s-done", "DONE", REQ, REQ_KEY));
        store.get("s-done").setWorkspaceState(SessionEntity.WORKSPACE_FINALIZED); // 已收口

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.finalizeRequirementWorkspace(PROJECT, REQ, false));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
        assertTrue(e.getMessage().contains("该需求工作区未开启或已收口"), e.getMessage());
    }

    /** 需求收口：按快照收口 + 带 workspaceKey 的 finalize 重载 + 需求与同需求会话行置 FINALIZED。 */
    @Test
    void 需求级收口带key并置FINALIZED() {
        requirement("u1", null);
        session("s1", "DONE", REQ, REQ_KEY);
        repoRows = List.of(repoRow("s1", REQ_BRANCH));
        FakeConnector connector = new FakeConnector();
        build(connector);

        FinalizeResult result = service.finalizeRequirementWorkspace(PROJECT, REQ, false);

        assertTrue(result.ok());
        assertEquals(REQ_KEY, connector.finalizeKey, "需求级收口必须带 workspaceKey（定位 worktrees/<key>）");
        assertEquals("u1", connector.finalizeOwner);
        assertEquals(REQ_BRANCH, connector.finalizeSpecs.get(0).branch());
        assertEquals(RequirementEntity.WORKSPACE_FINALIZED,
                requirementService.store.get(REQ).getWorkspaceState());
        assertEquals(SessionEntity.WORKSPACE_FINALIZED, store.get("s1").getWorkspaceState());
        // 重复收口 → 无 OPEN 会话 → 409（验收 3）
        assertThrows(DevMindException.class,
                () -> service.finalizeRequirementWorkspace(PROJECT, REQ, false));
    }

    /** runner 报错（脏工作区/冲突/push 失败）：透传不静默成功，需求状态保持 OPEN。 */
    @Test
    void 需求级收口失败透传不静默成功() {
        requirement("u1", null);
        session("s1", "DONE", REQ, REQ_KEY);
        repoRows = List.of(repoRow("s1", REQ_BRANCH));
        FakeConnector connector = new FakeConnector();
        connector.finalizeError = "工作区有未提交改动（可用丢弃未提交改动重试）";
        build(connector);

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.finalizeRequirementWorkspace(PROJECT, REQ, false));
        assertTrue(e.getMessage().contains("未提交改动"), e.getMessage());
        assertEquals(RequirementEntity.WORKSPACE_OPEN,
                requirementService.store.get(REQ).getWorkspaceState(), "收口失败不得置 FINALIZED");
    }

    /** 非需求创建者/负责人且非 admin → 403（FR-04 鉴权）。 */
    @Test
    void 需求收口鉴权_非归属人403() {
        requirement("someone-else", "another");
        session("s1", "DONE", REQ, REQ_KEY);
        build(new FakeConnector());
        identity.actor = "u1";

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.finalizeRequirementWorkspace(PROJECT, REQ, false));
        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
    }

    /** 会话仍在运行中不得收口（避免合并正在写的工作树）。 */
    @Test
    void 需求级收口要求会话已结束() {
        requirement("u1", null);
        session("s1", "RUNNING", REQ, REQ_KEY);
        repoRows = List.of(repoRow("s1", REQ_BRANCH));
        build(new FakeConnector());

        DevMindException e = assertThrows(DevMindException.class,
                () -> service.finalizeRequirementWorkspace(PROJECT, REQ, false));
        assertTrue(e.getMessage().contains("会话仍在运行中"), e.getMessage());
    }

    // ---------------- ③ 需求删除 → 释放工作树 ----------------

    /** 节点离线：releaseWorkspace 抛 CONFLICT，释放路径只告警不外抛（不阻断需求删除）。 */
    @Test
    void 需求删除时节点离线只告警() {
        session("s1", "DONE", REQ, REQ_KEY);
        repoRows = List.of(repoRow("s1", REQ_BRANCH));
        FakeConnector connector = new FakeConnector();
        connector.releaseThrowsOffline = true;
        build(connector);

        assertDoesNotThrow(() -> service.releaseRequirementWorkspace(PROJECT, REQ, "u1"));
        assertEquals(1, connector.releaseCalls, "离线也应尝试过一次释放");
        assertEquals(SessionEntity.WORKSPACE_OPEN, store.get("s1").getWorkspaceState(),
                "释放失败不得改动会话行（需求已删，目录由 GC 兜底）");
    }

    /** runner 在线但释放失败（如目录被占用）：同样只告警。 */
    @Test
    void 需求删除时释放失败不抛() {
        session("s1", "DONE", REQ, REQ_KEY);
        repoRows = List.of(repoRow("s1", REQ_BRANCH));
        FakeConnector connector = new FakeConnector();
        connector.releaseError = "目录被占用";
        build(connector);

        assertDoesNotThrow(() -> service.releaseRequirementWorkspace(PROJECT, REQ, "u1"));
        assertEquals(1, connector.releaseCalls);
    }

    /** 正常释放：带 req- 键下发（runner 整块回收 worktrees/<key> 与本地需求分支）。 */
    @Test
    void 需求删除释放带key() {
        session("s1", "DONE", REQ, REQ_KEY);
        repoRows = List.of(repoRow("s1", REQ_BRANCH));
        FakeConnector connector = new FakeConnector();
        build(connector);

        service.releaseRequirementWorkspace(PROJECT, REQ, "u1");

        assertEquals(List.of(REQ_KEY), connector.releasedKeys);
    }

    /** 该需求从未开过工作区（无 req- 键会话）→ 无操作，不抛。 */
    @Test
    void 无工作区需求删除无操作() {
        FakeConnector connector = new FakeConnector();
        build(connector);

        assertDoesNotThrow(() -> service.releaseRequirementWorkspace(PROJECT, REQ, null));
        assertEquals(0, connector.releaseCalls);
    }

    /** 需求删除事件经专用执行器异步释放（监听器本身不阻塞、不抛）。 */
    @Test
    void 需求删除事件监听不阻塞调用方() {
        session("s1", "DONE", REQ, REQ_KEY);
        repoRows = List.of(repoRow("s1", REQ_BRANCH));
        FakeConnector connector = new FakeConnector();
        build(connector);

        assertDoesNotThrow(() -> service.onRequirementDeleted(
                new com.devmind.project.event.RequirementDeletedEvent(REQ, PROJECT, "u1", "u1")));
        service.shutdown();
    }

    // ---------------- ③b 需求终态 → 释放工作树 + 删远端分支（FR-06 修订） ----------------

    /** 终态释放带 req- 键且 deleteRemoteBranch=true（runner 本地释放后追加删远端需求分支）。 */
    @Test
    void 需求终态释放带key且删远端分支() {
        session("s1", "DONE", REQ, REQ_KEY);
        repoRows = List.of(repoRow("s1", REQ_BRANCH));
        FakeConnector connector = new FakeConnector();
        build(connector);

        service.releaseRequirementWorkspace(PROJECT, REQ, "u1", true);

        assertEquals(List.of(REQ_KEY), connector.releasedKeys);
        assertTrue(connector.lastReleaseDeleteRemote, "终态清理必须置位 deleteRemoteBranch");
    }

    /** 对照：需求删除释放不动远端（删除是丢弃语义，远端分支不在删除时改动——FR-06 删除路径不变）。 */
    @Test
    void 需求删除释放不删远端分支() {
        session("s1", "DONE", REQ, REQ_KEY);
        repoRows = List.of(repoRow("s1", REQ_BRANCH));
        FakeConnector connector = new FakeConnector();
        build(connector);

        service.releaseRequirementWorkspace(PROJECT, REQ, "u1");

        assertEquals(List.of(REQ_KEY), connector.releasedKeys);
        assertFalse(connector.lastReleaseDeleteRemote, "需求删除不得改动远端");
    }

    /** 终态事件经同一执行器异步释放（监听器本身不阻塞、不抛）。 */
    @Test
    void 需求终态事件监听不阻塞调用方() {
        session("s1", "DONE", REQ, REQ_KEY);
        repoRows = List.of(repoRow("s1", REQ_BRANCH));
        FakeConnector connector = new FakeConnector();
        build(connector);

        assertDoesNotThrow(() -> service.onRequirementTerminal(
                new com.devmind.project.event.RequirementTerminalEvent(REQ, PROJECT, "u1", "DONE", "u1")));
        service.shutdown();
    }

    /** 该需求从未开过工作区（无 req- 键会话）→ 终态清理无操作，不抛。 */
    @Test
    void 无工作区需求终态无操作() {
        FakeConnector connector = new FakeConnector();
        build(connector);

        assertDoesNotThrow(() -> service.releaseRequirementWorkspace(PROJECT, REQ, null, true));
        assertEquals(0, connector.releaseCalls);
    }

    // ---------------- 需求删除时不得回收共享工作树 ----------------

    /**
     * CAP-51 FR-06 关键回归：需求粒度会话（req- 键）的工作树归需求所有，删除单个会话
     * 不得下发 workspace_release——否则删掉一个会话就把同需求别的会话的改动一起丢了。
     */
    @Test
    void 删除需求粒度会话不释放共享工作树() {
        session("s1", "DONE", REQ, REQ_KEY);
        repoRows = List.of(repoRow("s1", REQ_BRANCH));
        FakeConnector connector = new FakeConnector();
        build(connector);

        service.deleteSession("s1");

        assertEquals(0, connector.releaseCalls, "需求级工作区由需求删除/GC 释放，会话删除不动它");
        assertTrue(store.isEmpty(), "会话记录仍应删除");
    }

    /** 无需求会话（sid- 键）删除仍按 CAP-42 语义释放（用完即弃）。 */
    @Test
    void 删除无需求会话仍释放工作树() {
        session("s9", "DONE", null, "sid-s9");
        repoRows = List.of(repoRow("s9", "feature/s9"));
        FakeConnector connector = new FakeConnector();
        build(connector);

        service.deleteSession("s9");

        assertEquals(List.of("sid-s9"), connector.releasedKeys);
    }
}
