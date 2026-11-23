package com.devmind.session.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.TerminalWsPrepareResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.project.RequirementService;
import com.devmind.project.WorktreeManager;
import com.devmind.project.config.WorktreeProperties;
import com.devmind.project.dto.RepoView;
import com.devmind.project.model.Project;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.model.RequirementEntity;
import com.devmind.session.dto.TerminalWorkspaceAttachRequest;
import com.devmind.session.dto.TerminalWorkspaceCreateRequest;
import com.devmind.session.dto.TerminalWorkspaceView;
import com.devmind.session.model.SessionRepoEntity;
import com.devmind.session.model.TerminalWorkspaceEntity;
import com.devmind.session.repo.TerminalWorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-115 M1 终端工作区服务端单测：锁抢占/释放、同键 OPEN 冲突、与编码会话双向占用互斥、
 * 无登录态拒绝、新建物化主流程（依赖全内存 fake/代理，不起服务）。
 */
class TerminalWorkspaceServiceTest {

    private static final String NODE = "node-1";
    private static final String PROJECT = "p1";

    // ---------------- harness ----------------

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    static class FakeIdentity extends IdentityService {
        String actor = "u1";
        boolean admin;

        FakeIdentity() {
            super(null, null, null);
        }

        @Override
        public String currentActor() {
            return actor;
        }

        @Override
        public Optional<UserEntity> currentUser() {
            if (!admin) {
                return Optional.empty();
            }
            UserEntity u = new UserEntity();
            u.setUsername(actor);
            u.setRole(UserEntity.ROLE_ADMIN);
            return Optional.of(u);
        }
    }

    /** 内存 terminal_workspaces 仓 */
    private static TerminalWorkspaceRepository twRepo(Map<String, TerminalWorkspaceEntity> store) {
        return proxy(TerminalWorkspaceRepository.class, (p, m, args) -> switch (m.getName()) {
            case "save" -> {
                TerminalWorkspaceEntity e = (TerminalWorkspaceEntity) args[0];
                store.put(e.getId(), e);
                yield e;
            }
            case "findById" -> Optional.ofNullable(store.get((String) args[0]));
            case "findByWorkspaceKey" -> store.values().stream()
                    .filter(e -> args[0].equals(e.getWorkspaceKey())).toList();
            case "findByProjectIdOrderByCreatedAtDesc" -> store.values().stream()
                    .filter(e -> args[0].equals(e.getProjectId()))
                    .sorted(Comparator.comparing(TerminalWorkspaceEntity::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                    .toList();
            case "findByState" -> store.values().stream()
                    .filter(e -> args[0].equals(e.getState())).toList();
            case "findByRequirementIdAndState" -> store.values().stream()
                    .filter(e -> args[0].equals(e.getRequirementId()) && args[1].equals(e.getState()))
                    .toList();
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    static class FakeProjectService extends ProjectService {
        List<RepoView> repos = List.of();

        FakeProjectService() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null);
        }

        @Override
        public Project requireProject(String projectId) {
            return new Project(projectId, "app", ".", "main", List.of(), null,
                    ProjectEntity.KIND_NORMAL, null);
        }

        @Override
        public List<RepoView> listRepos(String projectId) {
            return repos;
        }
    }

    static class FakeRequirementService extends RequirementService {
        final Map<String, RequirementEntity> store = new HashMap<>();

        FakeRequirementService() {
            super(null, null, null, null, null, null, null, null, null, null, null);
        }

        void add(RequirementEntity e) {
            store.put(e.getId(), e);
        }

        @Override
        public RequirementEntity requireById(String requirementId) {
            RequirementEntity e = store.get(requirementId);
            if (e == null) {
                throw new DevMindException(ErrorCode.NOT_FOUND, "需求不存在: " + requirementId);
            }
            return e;
        }
    }

    /** 可控 SMS：buildRepoSpecs 返回固定 spec；precheckRequirementOccupancy 可配置抛 409 */
    static class FakeSessionManager extends SessionManagerService {
        List<AgentLaunchCommand.RepoSpec> specs = List.of();
        String occupancyConflict;

        FakeSessionManager() {
            super(null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        List<AgentLaunchCommand.RepoSpec> buildRepoSpecs(Project project, List<SessionRepoEntity> rows,
                                                         String baseBranch, String branch, String actor) {
            return specs;
        }

        @Override
        void precheckRequirementOccupancy(String requirementId, String selfSessionId) {
            if (occupancyConflict != null) {
                throw new DevMindException(ErrorCode.CONFLICT, occupancyConflict);
            }
        }
    }

    /** 可控 connector：terminalWorkspacePrepare 记录入参并可配置失败 */
    static class Harness {
        final FakeIdentity identity = new FakeIdentity();
        final FakeProjectService projectService = new FakeProjectService();
        final FakeRequirementService requirementService = new FakeRequirementService();
        final FakeSessionManager sessionManager = new FakeSessionManager();
        final Map<String, TerminalWorkspaceEntity> twStore = new HashMap<>();
        final TerminalWorkspaceService service;

        String prepareKey;
        String prepareError;

        Harness() {
            AgentNodeConnector connector = proxy(AgentNodeConnector.class, (p, m, args) ->
                    switch (m.getName()) {
                        case "defaultNodeId" -> NODE;
                        case "nodeMatches", "supports", "isOnline" -> true;
                        case "terminalWorkspacePrepare" -> {
                            prepareKey = (String) args[1];
                            yield prepareError != null
                                    ? TerminalWsPrepareResult.failed(prepareError)
                                    : TerminalWsPrepareResult.ok("/ws/" + args[1],
                                            ((List<?>) args[4]).isEmpty() ? null
                                                    : ((AgentLaunchCommand.RepoSpec) ((List<?>) args[4]).get(0)).branch(),
                                            List.of());
                        }
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
            ObjectProvider<AgentNodeConnector> provider = proxy(ObjectProvider.class,
                    (p, m, args) -> "getIfAvailable".equals(m.getName()) ? connector : null);
            service = new TerminalWorkspaceService(identity, projectService, requirementService, null,
                    new WorktreeManager(new WorktreeProperties(), null), sessionManager,
                    twRepo(twStore), provider, JsonMapper.builder().build());
        }

        TerminalWorkspaceEntity openRow(String key, String createdBy) {
            TerminalWorkspaceEntity e = new TerminalWorkspaceEntity();
            e.setId(java.util.UUID.randomUUID().toString());
            e.setProjectId(PROJECT);
            e.setWorkspaceKey(key);
            e.setBranch("terminal/x");
            e.setOwner(createdBy);
            e.setAgentNodeId(NODE);
            e.setBaseBranch("main");
            e.setState(TerminalWorkspaceService.STATE_OPEN);
            e.setCreatedBy(createdBy);
            e.setCreatedAt(Instant.now());
            e.setLastActiveAt(Instant.now());
            twStore.put(e.getId(), e);
            return e;
        }
    }

    // ---------------- 锁：抢占与释放 ----------------

    @Test
    void lockFreshOtherUserConflict() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        e.setLockOwner("u2");
        e.setLockAt(Instant.now());
        DevMindException ex = assertThrows(DevMindException.class, () -> h.service.lock(e.getId()));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("u2"), "409 话术应带占用方: " + ex.getMessage());
    }

    @Test
    void lockStalePreempted() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        e.setLockOwner("u2");
        e.setLockAt(Instant.now().minusSeconds(120));
        TerminalWorkspaceView v = h.service.lock(e.getId());
        assertEquals("u1", v.lockOwner(), "过期锁应被抢占");
    }

    @Test
    void lockSelfRenews() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        e.setLockOwner("u1");
        e.setLockAt(Instant.now().minusSeconds(10));
        TerminalWorkspaceView v = h.service.lock(e.getId());
        assertEquals("u1", v.lockOwner());
        assertTrue(v.lockAt().isAfter(Instant.now().minusSeconds(5)), "自己重锁应续期 lockAt");
    }

    @Test
    void unlockByOwnerClearsAndOtherConflict() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        e.setLockOwner("u1");
        e.setLockAt(Instant.now());
        h.identity.actor = "u2";
        DevMindException ex = assertThrows(DevMindException.class, () -> h.service.unlock(e.getId()));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        h.identity.actor = "u1";
        TerminalWorkspaceView v = h.service.unlock(e.getId());
        assertNull(v.lockOwner());
        assertNull(v.lockAt());
    }

    @Test
    void unlockByAdminClearsAndUnlockedIsNoop() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        TerminalWorkspaceView noop = h.service.unlock(e.getId());
        assertNull(noop.lockOwner(), "未持锁时解锁应幂等");
        e.setLockOwner("u2");
        e.setLockAt(Instant.now());
        h.identity.actor = "admin1";
        h.identity.admin = true;
        TerminalWorkspaceView v = h.service.unlock(e.getId());
        assertNull(v.lockOwner(), "管理员可解他人锁");
    }

    @Test
    void lockOnFinalizedRejected() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        e.setState(TerminalWorkspaceService.STATE_FINALIZED);
        DevMindException ex = assertThrows(DevMindException.class, () -> h.service.lock(e.getId()));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    // ---------------- 新建 / 挂接 ----------------

    @Test
    void createHappyPath() {
        Harness h = new Harness();
        h.projectService.repos = List.of(new RepoView(1L, PROJECT, "app", "app", "GIT",
                "https://git.example.com/p/app.git", null, "main", null, true, 0,
                null, null, null, null, null));
        h.sessionManager.specs = List.of(new AgentLaunchCommand.RepoSpec(
                "https://git.example.com/p/app.git", "main", "terminal/x", "tok", "app"));
        TerminalWorkspaceView v = h.service.create(
                new TerminalWorkspaceCreateRequest(PROJECT, null, null, null, null));
        assertEquals(TerminalWorkspaceService.STATE_OPEN, v.state());
        assertTrue(v.workspaceKey().matches("term-[a-z0-9]{8}"), "键应为 term-<8位短id>: " + v.workspaceKey());
        assertEquals("terminal/" + v.workspaceKey().substring("term-".length()), v.branch());
        assertEquals(v.workspaceKey(), h.prepareKey, "物化帧键应与落库键一致");
        assertEquals("u1", v.createdBy());
        assertEquals(1, v.repos().size(), "repos_json 快照应含主库");
        assertEquals("app", v.repos().get(0).get("name"));
    }

    @Test
    void createPrepareFailureLeavesNoRow() {
        Harness h = new Harness();
        h.projectService.repos = List.of(new RepoView(1L, PROJECT, "app", "app", "GIT",
                "https://git.example.com/p/app.git", null, "main", null, true, 0,
                null, null, null, null, null));
        h.sessionManager.specs = List.of(new AgentLaunchCommand.RepoSpec(
                "https://git.example.com/p/app.git", "main", "terminal/x", "tok", "app"));
        h.prepareError = "clone 失败";
        DevMindException ex = assertThrows(DevMindException.class, () -> h.service.create(
                new TerminalWorkspaceCreateRequest(PROJECT, null, null, null, null)));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(h.twStore.isEmpty(), "物化失败不应落脏行");
    }

    @Test
    void createRejectsLocalUser() {
        Harness h = new Harness();
        h.identity.actor = IdentityService.LOCAL_USER;
        DevMindException ex = assertThrows(DevMindException.class, () -> h.service.create(
                new TerminalWorkspaceCreateRequest(PROJECT, null, null, null, null)));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    void attachSameKeyOpenConflict() {
        Harness h = new Harness();
        h.openRow("term-abc12345", "u2");
        DevMindException ex = assertThrows(DevMindException.class, () -> h.service.attach(
                new TerminalWorkspaceAttachRequest("term-abc12345", PROJECT, null, null)));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("u2"), "同键 OPEN 409 应带占用方: " + ex.getMessage());
    }

    @Test
    void attachRequirementKeyBlockedByActiveSession() {
        Harness h = new Harness();
        RequirementEntity r = new RequirementEntity();
        r.setId("r1");
        r.setProjectId(PROJECT);
        h.requirementService.add(r);
        h.sessionManager.occupancyConflict = "该需求已有进行中的会话 s1";
        DevMindException ex = assertThrows(DevMindException.class, () -> h.service.attach(
                new TerminalWorkspaceAttachRequest("req-r1", null, null, null)));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("进行中的会话"));
    }

    @Test
    void attachRejectsBadKey() {
        Harness h = new Harness();
        DevMindException ex = assertThrows(DevMindException.class, () -> h.service.attach(
                new TerminalWorkspaceAttachRequest("../etc", PROJECT, null, null)));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    // ---------------- 反向占用预检（会话 create 侧） ----------------

    @Test
    void sessionCreatePrecheckBlockedByOpenTerminalWorkspace() {
        Map<String, TerminalWorkspaceEntity> store = new HashMap<>();
        TerminalWorkspaceRepository repo = twRepo(store);
        TerminalWorkspaceEntity open = new TerminalWorkspaceEntity();
        open.setId("tw1");
        open.setWorkspaceKey("req-r1");
        open.setState(TerminalWorkspaceService.STATE_OPEN);
        open.setCreatedBy("u2");
        store.put(open.getId(), open);
        TerminalWorkspaceEntity done = new TerminalWorkspaceEntity();
        done.setId("tw2");
        done.setWorkspaceKey("req-r2");
        done.setState(TerminalWorkspaceService.STATE_RELEASED);
        store.put(done.getId(), done);

        SessionManagerService sms = new SessionManagerService(null, null, null, null, null, null,
                null, null, null, null, null, null, null, repo, null, null, null, null, null,
                null, null, null, null, null, null);
        DevMindException ex = assertThrows(DevMindException.class,
                () -> sms.precheckTerminalWorkspaceOccupancy("req-r1"));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("u2"), "反向预检 409 应带占用方: " + ex.getMessage());
        sms.precheckTerminalWorkspaceOccupancy("req-r2"); // RELEASED 行不挡
        sms.precheckTerminalWorkspaceOccupancy(null);     // 空键不挡
    }
}
