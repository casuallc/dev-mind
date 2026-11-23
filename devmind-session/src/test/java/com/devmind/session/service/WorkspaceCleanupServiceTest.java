package com.devmind.session.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.WorkspaceReleaseResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.notification.NotificationEvent;
import com.devmind.notification.NotificationPublisher;
import com.devmind.session.dto.WorkspaceCleanupConfigView;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.WorkspaceCleanupConfigEntity;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.repo.WorkspaceCleanupConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-114 工作区定时清理：候选筛选（超龄/终态/有节点/范围）、需求级去重、
 * 单候选失败不阻断、配置默认值与校验、tick 幂等（关/小时不符/今日已跑 不触发）。
 * 同 {@link SessionRequirementWorkspaceTest} 风格：内存假仓库 + 桩服务，不起 Spring。
 */
class WorkspaceCleanupServiceTest {

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    /** 单行配置仓库（SINGLETON_ID upsert）。 */
    static class FakeConfigRepo {
        final Map<Long, WorkspaceCleanupConfigEntity> store = new HashMap<>();

        WorkspaceCleanupConfigRepository repo() {
            return proxy(WorkspaceCleanupConfigRepository.class, (p, m, args) -> switch (m.getName()) {
                case "findById" -> Optional.ofNullable(store.get((Long) args[0]));
                case "save" -> {
                    WorkspaceCleanupConfigEntity e = (WorkspaceCleanupConfigEntity) args[0];
                    store.put(e.getId(), e);
                    yield e;
                }
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }

        WorkspaceCleanupConfigEntity row() {
            return store.get(WorkspaceCleanupConfigEntity.SINGLETON_ID);
        }

        void seed(boolean enabled, int retentionDays, int runHour,
                  boolean scopeRequirement, boolean scopeSession) {
            WorkspaceCleanupConfigEntity e = new WorkspaceCleanupConfigEntity();
            e.setEnabled(enabled);
            e.setRetentionDays(retentionDays);
            e.setRunHour(runHour);
            e.setScopeRequirement(scopeRequirement);
            e.setScopeSession(scopeSession);
            store.put(e.getId(), e);
        }
    }

    /** 会话仓库：只覆盖清理链路用到的查询。 */
    static class FakeSessionRepo {
        final Map<String, SessionEntity> store = new HashMap<>();

        SessionRepository repo() {
            return proxy(SessionRepository.class, (p, m, args) -> switch (m.getName()) {
                case "findByWorkspaceStateOrderByUpdatedAtAsc" -> store.values().stream()
                        .filter(e -> args[0].equals(e.getWorkspaceState()))
                        .sorted(Comparator.comparing(SessionEntity::getUpdatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList();
                case "save" -> {
                    SessionEntity e = (SessionEntity) args[0];
                    store.put(e.getId(), e);
                    yield e;
                }
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    /** 释放链路桩：记录调用，failOn 中的会话 id 抛错（模拟节点离线等单候选失败）。 */
    static class StubSessionManager extends SessionManagerService {
        final List<String> releasedIds = new ArrayList<>();
        final Set<String> failOn = new HashSet<>();

        StubSessionManager() {
            super(null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public WorkspaceReleaseResult releaseWorkspaceInternal(SessionEntity ent) {
            releasedIds.add(ent.getId());
            if (failOn.contains(ent.getId())) {
                throw new DevMindException(ErrorCode.CONFLICT, "节点不在线: node-1");
            }
            ent.setWorkspaceState(SessionEntity.WORKSPACE_RELEASED);
            return WorkspaceReleaseResult.ok("ok");
        }
    }

    static class FakeIdentity extends IdentityService {
        FakeIdentity() {
            super(null, null, null);
        }

        @Override
        public String currentActor() {
            return "admin";
        }
    }

    private final FakeConfigRepo configStore = new FakeConfigRepo();
    private final FakeSessionRepo sessionStore = new FakeSessionRepo();
    private final StubSessionManager sessionManager = new StubSessionManager();
    private final List<NotificationEvent> notifications = new ArrayList<>();
    private WorkspaceCleanupService service;

    @BeforeEach
    void setUp() {
        NotificationPublisher publisher = notifications::add;
        service = new WorkspaceCleanupService(configStore.repo(), sessionStore.repo(), sessionManager,
                new FakeIdentity(), publisher, JsonMapper.builder().build());
    }

    /** 造一个 OPEN 工作区会话：age 天前更新、指定状态/节点/工作区键。 */
    private SessionEntity session(String id, String status, int ageDays, String nodeId,
                                  String workspaceKey, String requirementId) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setProjectId("p1");
        s.setRequirementId(requirementId);
        s.setStatus(status);
        s.setAgentNodeId(nodeId);
        s.setWorkspaceKey(workspaceKey);
        s.setWorkspaceState(SessionEntity.WORKSPACE_OPEN);
        s.setWorkspaceOwner("u1");
        s.setCreatedBy("u1");
        s.setUpdatedAt(Instant.now().minus(ageDays, ChronoUnit.DAYS));
        sessionStore.store.put(id, s);
        return s;
    }

    // ---------------- 配置读写 ----------------

    @Test
    void getConfigReturnsDefaultsWhenNoRow() {
        WorkspaceCleanupConfigView v = service.getConfig();
        assertFalse(v.enabled());
        assertEquals(30, v.retentionDays());
        assertEquals(3, v.runHour());
        assertTrue(v.scopeRequirement());
        assertTrue(v.scopeSession());
        assertNull(v.lastRunAt());
        assertNull(configStore.row()); // 读不落库
    }

    @Test
    void saveConfigUpsertsAndValidates() {
        // 保留天数越界
        assertThrows(DevMindException.class, () -> service.saveConfig(
                new WorkspaceCleanupConfigView(true, 0, 3, true, true, null, null)));
        assertThrows(DevMindException.class, () -> service.saveConfig(
                new WorkspaceCleanupConfigView(true, 366, 3, true, true, null, null)));
        // 执行小时越界
        assertThrows(DevMindException.class, () -> service.saveConfig(
                new WorkspaceCleanupConfigView(true, 30, 24, true, true, null, null)));
        // 范围全不选
        assertThrows(DevMindException.class, () -> service.saveConfig(
                new WorkspaceCleanupConfigView(true, 30, 3, false, false, null, null)));
        assertNull(configStore.row()); // 校验失败不落库

        WorkspaceCleanupConfigView saved = service.saveConfig(
                new WorkspaceCleanupConfigView(true, 7, 4, true, false, null, null));
        assertTrue(saved.enabled());
        assertEquals(7, saved.retentionDays());
        assertEquals(4, saved.runHour());
        assertTrue(saved.scopeRequirement());
        assertFalse(saved.scopeSession());
        assertEquals("admin", configStore.row().getUpdatedBy());

        // null 字段取默认/宽松口径
        WorkspaceCleanupConfigView relaxed = service.saveConfig(
                new WorkspaceCleanupConfigView(null, null, null, null, null, null, null));
        assertFalse(relaxed.enabled());
        assertEquals(30, relaxed.retentionDays());
        assertEquals(3, relaxed.runHour());
        assertTrue(relaxed.scopeRequirement());
        assertTrue(relaxed.scopeSession());
    }

    // ---------------- 候选筛选 ----------------

    @Test
    void runOnceCleansAgedTerminalAndSkipsOthers() {
        configStore.seed(true, 30, 3, true, true);
        SessionEntity agedDone = session("s-aged", "DONE", 40, "node-1", null, null);
        session("s-young", "DONE", 5, "node-1", null, null);          // 未超龄
        session("s-no-node", "DONE", 40, null, null, null);           // 历史本机会话
        session("s-no-node2", "DONE", 40, "  ", null, null);          // 空白节点同上
        session("s-running", "RUNNING", 40, "node-1", null, null);    // 进行中
        session("s-suspended", "SUSPENDED", 40, "node-1", null, null);// 挂起（可 resume）
        session("s-bad-status", "ARCHIVED_X", 40, "node-1", null, null); // 状态异常按进行中兜底
        SessionEntity released = session("s-released", "DONE", 40, "node-1", null, null);
        released.setWorkspaceState(SessionEntity.WORKSPACE_RELEASED); // 已释放不入候选

        service.runOnce("manual");

        assertEquals(List.of("s-aged"), sessionManager.releasedIds);
        assertEquals(SessionEntity.WORKSPACE_RELEASED, agedDone.getWorkspaceState());
        // 只清 1 个也发通知
        assertEquals(1, notifications.size());
        assertEquals("WORKSPACE_CLEANUP_DONE", notifications.get(0).kind());
        // 报告落库：清理 1、跳过进行中 3（RUNNING/SUSPENDED/状态异常）
        String report = configStore.row().getLastReport();
        assertTrue(report.contains("\"cleaned\":1"), report);
        assertTrue(report.contains("\"skippedActive\":3"), report);
        assertTrue(report.contains("\"failed\":0"), report);
    }

    @Test
    void runOnceRespectsScopeSwitches() {
        configStore.seed(true, 30, 3, true, false); // 只清需求级
        session("s-req", "DONE", 40, "node-1", "req-r1", "r1");
        session("s-sid", "DONE", 40, "node-1", null, null);

        service.runOnce("manual");
        assertEquals(List.of("s-req"), sessionManager.releasedIds);

        sessionManager.releasedIds.clear();
        configStore.seed(true, 30, 3, false, true); // 只清会话级
        service.runOnce("manual");
        assertEquals(List.of("s-sid"), sessionManager.releasedIds);
    }

    @Test
    void runOnceDedupesRequirementScopedWorkspaces() {
        configStore.seed(true, 30, 3, true, true);
        // 同需求两个会话共用 req-r1 工作树：只下一次释放帧
        session("s1", "DONE", 50, "node-1", "req-r1", "r1");
        session("s2", "DONE", 40, "node-1", "req-r1", "r1");

        service.runOnce("manual");
        assertEquals(1, sessionManager.releasedIds.size());
        assertEquals("s1", sessionManager.releasedIds.get(0)); // 取最旧的代表
    }

    @Test
    void runOnceFailureDoesNotBlockOthers() {
        configStore.seed(true, 30, 3, true, true);
        session("s-fail", "FAILED", 60, "node-1", null, null);
        session("s-ok", "DONE", 40, "node-1", null, null);
        sessionManager.failOn.add("s-fail");

        service.runOnce("manual");

        assertEquals(List.of("s-fail", "s-ok"), sessionManager.releasedIds); // 失败不阻断后续
        String report = configStore.row().getLastReport();
        assertTrue(report.contains("\"cleaned\":1"), report);
        assertTrue(report.contains("\"failed\":1"), report);
        assertTrue(report.contains("节点不在线"), report);
        assertEquals(1, notifications.size());
        assertTrue(notifications.get(0).content().contains("失败 1 个"));
    }

    @Test
    void runOnceZeroHitDoesNotNotify() {
        configStore.seed(true, 30, 3, true, true);
        session("s-young", "DONE", 1, "node-1", null, null);

        service.runOnce("manual");

        assertTrue(sessionManager.releasedIds.isEmpty());
        assertTrue(notifications.isEmpty()); // 0 命中不打扰
        assertTrue(configStore.row().getLastReport().contains("\"cleaned\":0")); // 报告仍落库
    }

    // ---------------- tick 幂等 ----------------

    @Test
    void tickSkipsWhenDisabledOrNoRow() {
        assertFalse(service.tick()); // 无配置行
        configStore.seed(false, 30, ZonedDateTime.now().getHour(), true, true);
        assertFalse(service.tick()); // 显式关闭
        assertTrue(sessionManager.releasedIds.isEmpty());
    }

    @Test
    void tickSkipsWhenHourMismatch() {
        int otherHour = (ZonedDateTime.now().getHour() + 12) % 24;
        configStore.seed(true, 30, otherHour, true, true);
        assertFalse(service.tick());
    }

    @Test
    void tickTriggersOnceAndSameDayRerunBlocked() throws Exception {
        configStore.seed(true, 30, ZonedDateTime.now().getHour(), true, true);
        session("s-aged", "DONE", 40, "node-1", null, null);

        assertTrue(service.tick());
        awaitNotification(); // 异步轮跑完（通知在报告落库后发布，等它即等全链路）

        assertEquals(List.of("s-aged"), sessionManager.releasedIds);
        assertEquals(1, notifications.size());

        // 今日已跑：同小时再次 tick 不再触发（即便新来了个超龄会话）
        session("s-aged-2", "DONE", 40, "node-1", null, null);
        assertFalse(service.tick());
        awaitIdle();
        assertEquals(List.of("s-aged"), sessionManager.releasedIds);
        assertEquals(1, notifications.size());
    }

    @Test
    void manualTriggerIgnoresSwitchAndHour() throws Exception {
        configStore.seed(false, 30, (ZonedDateTime.now().getHour() + 12) % 24, true, true);
        session("s-aged", "DONE", 40, "node-1", null, null);

        assertTrue(service.triggerManual()); // 手动不看 enabled/runHour
        awaitIdle();
        assertEquals(List.of("s-aged"), sessionManager.releasedIds);
    }

    /** 等异步清理轮结束（lastRunAt 落库即完；通知在其后发布，断言通知用 {@link #awaitNotification}）。 */
    private void awaitIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            WorkspaceCleanupConfigEntity row = configStore.row();
            if (row != null && row.getLastRunAt() != null
                    && row.getLastRunAt().isAfter(Instant.now().minus(30, ChronoUnit.SECONDS))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("异步清理轮 10s 内未完成");
    }

    /** 等异步轮跑到通知发布（runOnce 尾部，比 lastRunAt 更靠后）。 */
    private void awaitNotification() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (!notifications.isEmpty()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("异步清理轮 10s 内未发布通知");
    }
}
