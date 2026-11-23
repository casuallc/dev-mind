package com.devmind.session.service;

import com.devmind.common.agent.AgentAuditBridge;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.TerminalCompleteResult;
import com.devmind.common.agent.TerminalExecResult;
import com.devmind.common.agent.TerminalWsPrepareResult;
import com.devmind.common.agent.WorkspaceFileWriteResult;
import com.devmind.common.agent.WorkspaceQueryResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.session.dto.TerminalOpenView;
import com.devmind.session.dto.TerminalSessionView;
import com.devmind.session.model.TerminalSessionEntity;
import com.devmind.session.model.TerminalWorkspaceEntity;
import com.devmind.session.repo.TerminalSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-115 M2 终端会话通道服务端单测：open（锁 → 重发 prepare → RUNNING/SUSPENDED 复活）、
 * exec 危险命令 428/确认放行审计、心跳续期、close 幂等释放、巡检挂起（心跳超时/节点离线）、
 * 文件读写透传与 sha 冲突 409、命令历史（依赖全内存 fake/代理，不起服务）。
 */
class TerminalSessionServiceTest {

    private static final String NODE = "node-1";

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    /** 内存 terminal_sessions 仓 */
    private static TerminalSessionRepository tsRepo(Map<String, TerminalSessionEntity> store) {
        return proxy(TerminalSessionRepository.class, (p, m, args) -> switch (m.getName()) {
            case "save" -> {
                TerminalSessionEntity e = (TerminalSessionEntity) args[0];
                store.put(e.getId(), e);
                yield e;
            }
            case "findById" -> Optional.ofNullable(store.get((String) args[0]));
            case "findByWorkspaceIdOrderByStartedAtDesc" -> store.values().stream()
                    .filter(e -> args[0].equals(e.getWorkspaceId()))
                    .sorted(Comparator.comparing(TerminalSessionEntity::getStartedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                    .toList();
            case "findByState" -> store.values().stream()
                    .filter(e -> args[0].equals(e.getState())).toList();
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    /** 记录型 AgentAuditBridge */
    static class FakeAuditBridge implements AgentAuditBridge {
        final List<String> confirmed = new ArrayList<>();

        @Override
        public void recordCommand(String event, String nodeId, String target, boolean ok,
                                  String actor, String detail) {
            confirmed.add(event + "|" + actor + "|" + target + "|" + detail);
        }
    }

    static class Harness {
        final TerminalWorkspaceServiceTest.FakeIdentity identity =
                new TerminalWorkspaceServiceTest.FakeIdentity();
        final TerminalWorkspaceServiceTest.FakeProjectService projectService =
                new TerminalWorkspaceServiceTest.FakeProjectService();
        final TerminalWorkspaceServiceTest.FakeSessionManager sessionManager =
                new TerminalWorkspaceServiceTest.FakeSessionManager();
        final Map<String, TerminalWorkspaceEntity> twStore = new HashMap<>();
        final Map<String, TerminalSessionEntity> tsStore = new HashMap<>();
        final FakeAuditBridge auditBridge = new FakeAuditBridge();
        final TerminalWorkspaceService workspaceService;
        final TerminalSessionService service;

        boolean nodeOnline = true;
        String prepareError;
        String execSessionId;
        String execCommand;
        TerminalExecResult execResult = TerminalExecResult.of(0, "ok-out", "", "", false);
        WorkspaceFileWriteResult writeResult = WorkspaceFileWriteResult.ok("a.txt", "sha-new");
        final List<Map<String, Object>> queryPayload =
                new ArrayList<>(List.of(Map.of("entries", List.of())));
        int prepareCalls = 0;

        Harness() {
            AgentNodeConnector connector = proxy(AgentNodeConnector.class, (p, m, args) ->
                    switch (m.getName()) {
                        case "defaultNodeId" -> NODE;
                        case "nodeMatches", "supports" -> true;
                        case "isOnline" -> nodeOnline;
                        case "terminalWorkspacePrepare" -> {
                            prepareCalls++;
                            yield prepareError != null
                                    ? TerminalWsPrepareResult.failed(prepareError)
                                    : TerminalWsPrepareResult.ok("/ws/" + args[1], "terminal/x",
                                            List.of());
                        }
                        case "terminalExec" -> {
                            execSessionId = (String) args[1];
                            execCommand = (String) args[2];
                            yield execResult;
                        }
                        case "terminalComplete" -> TerminalCompleteResult.of("ap", List.of("app/"));
                        case "terminalCancel" -> null;
                        case "workspaceQuery" -> WorkspaceQueryResult.ok(queryPayload.get(0));
                        case "workspaceFileWrite" -> writeResult;
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
            ObjectProvider<AgentNodeConnector> connectorProvider = proxy(ObjectProvider.class,
                    (p, m, args) -> "getIfAvailable".equals(m.getName()) ? connector : null);
            ObjectProvider<AgentAuditBridge> bridgeProvider = proxy(ObjectProvider.class,
                    (p, m, args) -> "getIfAvailable".equals(m.getName()) ? auditBridge : null);
            workspaceService = new TerminalWorkspaceService(identity, projectService,
                    new TerminalWorkspaceServiceTest.FakeRequirementService(), null, null,
                    sessionManager, TerminalWorkspaceServiceTest.twRepo(twStore),
                    connectorProvider, JsonMapper.builder().build());
            service = new TerminalSessionService(workspaceService, tsRepo(tsStore), projectService,
                    sessionManager, connectorProvider, bridgeProvider, JsonMapper.builder().build());
        }

        /** 落一行带仓库快照的 OPEN 工作区（open 重发 prepare 依赖 repos_json）。 */
        TerminalWorkspaceEntity openRow(String key, String createdBy) {
            TerminalWorkspaceEntity e = new TerminalWorkspaceEntity();
            e.setId(java.util.UUID.randomUUID().toString());
            e.setProjectId("p1");
            e.setWorkspaceKey(key);
            e.setBranch("terminal/x");
            e.setOwner(createdBy);
            e.setAgentNodeId(NODE);
            e.setBaseBranch("main");
            e.setState(TerminalWorkspaceService.STATE_OPEN);
            e.setCreatedBy(createdBy);
            e.setCreatedAt(Instant.now());
            e.setLastActiveAt(Instant.now());
            e.setReposJson("[{\"name\":\"app\",\"localPath\":\"app\",\"baseBranch\":\"main\","
                    + "\"branch\":\"terminal/x\",\"isPrimary\":true,\"sortOrder\":0}]");
            twStore.put(e.getId(), e);
            return e;
        }

        /** open 到 RUNNING（断言前置用）。 */
        TerminalOpenView opened(TerminalWorkspaceEntity e) {
            return service.open(e.getId());
        }
    }

    // ---------------- open ----------------

    @Test
    void openCreatesRunningSessionAndRebinds() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        TerminalOpenView v = h.opened(e);
        assertEquals("term-abc12345", v.workspaceKey());
        assertEquals("/ws/term-abc12345", v.path());
        assertEquals("terminal/x", v.branch());
        assertEquals("RUNNING", v.session().state());
        assertEquals("u1", v.session().userId());
        assertNotNull(v.session().lastHeartbeatAt());
        assertEquals(1, h.prepareCalls, "open 必须重发 terminal_ws_prepare 幂等重绑");
        TerminalWorkspaceEntity saved = h.twStore.get(e.getId());
        assertEquals("u1", saved.getLockOwner(), "open 应落占用锁");
        assertEquals(v.session().id(), saved.getLockSessionId(), "锁应挂会话 id");
    }

    @Test
    void openConflictWhenLockedByOther() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        e.setLockOwner("u2");
        e.setLockAt(Instant.now());
        DevMindException ex = assertThrows(DevMindException.class, () -> h.service.open(e.getId()));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("u2"), "409 应带占用方: " + ex.getMessage());
        assertTrue(h.tsStore.isEmpty(), "加锁冲突不应落会话行");
        assertEquals(0, h.prepareCalls, "加锁冲突不应发物化帧");
    }

    @Test
    void openPrepareFailureRollsBackLock() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        h.prepareError = "clone 失败";
        DevMindException ex = assertThrows(DevMindException.class, () -> h.service.open(e.getId()));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        TerminalWorkspaceEntity saved = h.twStore.get(e.getId());
        assertNull(saved.getLockOwner(), "物化失败应回滚锁");
        assertNull(saved.getLockSessionId());
        assertTrue(h.tsStore.values().stream().noneMatch(s -> "RUNNING".equals(s.getState())),
                "物化失败不应有 RUNNING 会话");
    }

    @Test
    void openReusesOwnSuspendedSession() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        TerminalOpenView first = h.opened(e);
        h.service.close(e.getId());
        // 手工把会话置 SUSPENDED（模拟巡检挂起）
        TerminalSessionEntity s = h.tsStore.get(first.session().id());
        s.setState("SUSPENDED");
        TerminalOpenView again = h.opened(e);
        assertEquals(first.session().id(), again.session().id(), "本人的挂起会话应复活复用");
        assertEquals("RUNNING", again.session().state());
        assertEquals(2, h.prepareCalls, "复活也要重发 prepare（runner 可能重启丢绑定）");
    }

    @Test
    void openOnFinalizedRejected() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        e.setState(TerminalWorkspaceService.STATE_FINALIZED);
        DevMindException ex = assertThrows(DevMindException.class, () -> h.service.open(e.getId()));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    // ---------------- exec：危险命令护栏 ----------------

    @Test
    void execSafeCommandPassesThrough() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        h.opened(e);
        TerminalExecResult r = h.service.exec(e.getId(), "git status", "", null);
        assertTrue(r.ok());
        assertEquals("term-abc12345", h.execSessionId, "伪会话 id 应为 workspaceKey");
        assertEquals("git status", h.execCommand);
        assertTrue(h.auditBridge.confirmed.isEmpty(), "安全命令不应有确认审计");
    }

    @Test
    void execDangerousNeedsConfirm() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        h.opened(e);
        DevMindException ex = assertThrows(DevMindException.class,
                () -> h.service.exec(e.getId(), "rm -rf build", "", null));
        assertEquals(ErrorCode.NEED_CONFIRM, ex.getErrorCode());
        assertEquals(428, ex.getErrorCode().getStatus());
        assertTrue(ex.getMessage().contains("rm -rf build"), "428 应带完整命令供前端展示");
        assertNull(h.execCommand, "未确认的危险命令不应下发 runner");
    }

    @Test
    void execDangerousConfirmedPassesAndAudits() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        h.opened(e);
        TerminalExecResult r = h.service.exec(e.getId(), "rm -rf build", "", true);
        assertTrue(r.ok());
        assertEquals("rm -rf build", h.execCommand);
        assertEquals(1, h.auditBridge.confirmed.size(), "确认放行必须补记带确认人的审计");
        String audit = h.auditBridge.confirmed.get(0);
        assertTrue(audit.contains("u1"), "审计应带确认人: " + audit);
        assertTrue(audit.contains("rm -rf build"), "审计应带完整命令: " + audit);
    }

    @Test
    void execWithoutLockRejected() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        DevMindException ex = assertThrows(DevMindException.class,
                () -> h.service.exec(e.getId(), "ls", "", null));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("open"), "话术应引导先 open: " + ex.getMessage());
    }

    @Test
    void execByNonLockHolderRejected() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        h.opened(e);
        h.identity.actor = "u2";
        DevMindException ex = assertThrows(DevMindException.class,
                () -> h.service.exec(e.getId(), "ls", "", null));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("u1"), "409 应带占用方: " + ex.getMessage());
    }

    // ---------------- heartbeat / close ----------------

    @Test
    void heartbeatRenewsSessionAndLock() throws Exception {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        TerminalOpenView opened = h.opened(e);
        Instant before = e.getLockAt();
        Thread.sleep(5);
        TerminalSessionView v = h.service.heartbeat(e.getId());
        assertEquals("RUNNING", v.state());
        assertTrue(v.lastHeartbeatAt().isAfter(opened.session().lastHeartbeatAt())
                        || v.lastHeartbeatAt().equals(opened.session().lastHeartbeatAt()),
                "心跳应续期");
        assertTrue(h.twStore.get(e.getId()).getLockAt().isAfter(before)
                || h.twStore.get(e.getId()).getLockAt().equals(before), "锁应续期");
    }

    @Test
    void closeReleasesLockAndIsIdempotent() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        TerminalOpenView opened = h.opened(e);
        TerminalSessionView v = h.service.close(e.getId());
        assertEquals("CLOSED", v.state());
        assertNotNull(v.endedAt());
        TerminalWorkspaceEntity saved = h.twStore.get(e.getId());
        assertNull(saved.getLockOwner(), "close 应释放占用锁");
        assertNull(saved.getLockSessionId());
        // 幂等：再 close 返回同一会话（CLOSED），不抛
        TerminalSessionView again = h.service.close(e.getId());
        assertEquals(opened.session().id(), again.id());
        assertEquals("CLOSED", again.state());
        // close 后 exec 409
        DevMindException ex = assertThrows(DevMindException.class,
                () -> h.service.exec(e.getId(), "ls", "", null));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    // ---------------- 巡检挂起 ----------------

    @Test
    void watchdogSuspendsStaleHeartbeatAndReleasesLock() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        TerminalOpenView opened = h.opened(e);
        TerminalSessionEntity s = h.tsStore.get(opened.session().id());
        s.setLastHeartbeatAt(Instant.now().minusSeconds(120));
        int n = h.service.suspendStale();
        assertEquals(1, n);
        assertEquals("SUSPENDED", s.getState());
        assertNull(h.twStore.get(e.getId()).getLockOwner(), "挂起应释放锁");
        // open 复活
        TerminalOpenView revived = h.opened(e);
        assertEquals(opened.session().id(), revived.session().id());
    }

    @Test
    void watchdogSuspendsWhenNodeOffline() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        TerminalOpenView opened = h.opened(e);
        h.nodeOnline = false;
        int n = h.service.suspendStale();
        assertEquals(1, n, "节点离线应立即挂起（不等心跳窗口）");
        assertEquals("SUSPENDED", h.tsStore.get(opened.session().id()).getState());
    }

    @Test
    void watchdogKeepsFreshSession() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        h.opened(e);
        assertEquals(0, h.service.suspendStale(), "心跳新鲜且节点在线不应挂起");
    }

    // ---------------- 文件读写 / 历史 ----------------

    @Test
    void fileTreeAndReadPassThrough() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        // 只读浏览不要求持锁
        Map<String, Object> tree = h.service.fileTree(e.getId(), "");
        assertTrue(tree.containsKey("entries"));
        Map<String, Object> file = h.service.fileRead(e.getId(), "a.txt");
        assertTrue(file.containsKey("entries"));
        // file 附算 content 的 sha（乐观锁 expectedSha 回传用）；无 content 不附
        h.queryPayload.set(0, Map.of("content", "foo"));
        assertEquals("2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
                h.service.fileRead(e.getId(), "b.txt").get("sha"), "content=foo 的 sha256");
    }

    @Test
    void fileWritePassesAndConflict409() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        h.opened(e);
        Map<String, Object> ok = h.service.fileWrite(e.getId(), "a.txt", "aGk=", null);
        assertEquals("sha-new", ok.get("sha"));
        h.writeResult = WorkspaceFileWriteResult.conflict("sha-other");
        DevMindException ex = assertThrows(DevMindException.class,
                () -> h.service.fileWrite(e.getId(), "a.txt", "aGk=", "sha-new"));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("sha-other"), "409 应带当前 sha 供前端刷新: "
                + ex.getMessage());
        h.writeResult = WorkspaceFileWriteResult.failed("路径越界");
        DevMindException ex2 = assertThrows(DevMindException.class,
                () -> h.service.fileWrite(e.getId(), "../x", "aGk=", null));
        assertTrue(ex2.getMessage().contains("路径越界"));
    }

    @Test
    void fileWriteRequiresLock() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        DevMindException ex = assertThrows(DevMindException.class,
                () -> h.service.fileWrite(e.getId(), "a.txt", "aGk=", null));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    void historyQueriesByWorkspaceKey() {
        Harness h = new Harness();
        TerminalWorkspaceEntity e = h.openRow("term-abc12345", "u1");
        List<AgentAuditBridge.Entry> history = h.service.history(e.getId(), 10);
        assertNotNull(history, "bridge 默认实现应返回空表而非 null");
    }
}
