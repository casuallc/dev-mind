package com.devmind.session.service;

import com.devmind.common.agent.AgentAuditBridge;
import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.TerminalCompleteResult;
import com.devmind.common.agent.TerminalExecResult;
import com.devmind.common.agent.TerminalWsPrepareResult;
import com.devmind.common.agent.WorkspaceFileWriteResult;
import com.devmind.common.agent.WorkspaceQueryResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.project.model.Project;
import com.devmind.session.dto.TerminalOpenView;
import com.devmind.session.dto.TerminalSessionView;
import com.devmind.session.model.SessionRepoEntity;
import com.devmind.session.model.TerminalSessionEntity;
import com.devmind.session.model.TerminalWorkspaceEntity;
import com.devmind.session.repo.TerminalSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CAP-115 M2 终端会话通道：一次「打开终端工作区」= 一条 terminal_sessions 记录
 * （RUNNING → SUSPENDED 90s 无心跳/节点离线 / CLOSED 主动关闭）。
 *
 * <p>open 语义：加占用锁（他人新鲜锁 409）→ 按 repos_json 快照重发 terminal_ws_prepare
 * （幂等——runner 重启后绑定丢失靠这步重绑）→ 会话落 RUNNING。exec/complete/cancel
 * 经既有 CAP-58/59 帧透传，伪会话 id = workspaceKey（runner 侧按终端绑定定位聚合根并
 * 切写档白名单）。exec 命中 {@link DangerousCommandGuard} 且未 confirmed → 428 让前端
 * 二次确认；confirmed 放行时补记带确认人的审计（AgentAuditBridge，agent 未装配静默跳过）。</p>
 *
 * <p>文件读写：tree/file 复用 CAP-54 workspace_query 只读帧；写入走 workspace_file_write
 * 帧（runner 侧 resolveConfined 双约束 + expectedSha 乐观锁，sha 不符 → 409）。</p>
 *
 * <p>巡检（{@link #watchdog}，30s 一跳）：RUNNING 会话 90s 无心跳或节点离线 → SUSPENDED
 * + 释放占用锁（工作区保留，reopen 即恢复）。</p>
 */
@Service
public class TerminalSessionService {

    private static final Logger log = LoggerFactory.getLogger(TerminalSessionService.class);

    /** 心跳新鲜度窗口：与前端 30s 一跳对齐，90s 无心跳视为失联 */
    static final long HEARTBEAT_FRESH_SEC = 90;

    private final TerminalWorkspaceService workspaceService;
    private final TerminalSessionRepository terminalSessionRepo;
    private final ProjectService projectService;
    private final SessionManagerService sessionManagerService;
    private final ObjectProvider<AgentNodeConnector> connectorProvider;
    private final ObjectProvider<AgentAuditBridge> auditBridgeProvider;
    private final ObjectMapper mapper;

    public TerminalSessionService(TerminalWorkspaceService workspaceService,
                                  TerminalSessionRepository terminalSessionRepo,
                                  ProjectService projectService,
                                  SessionManagerService sessionManagerService,
                                  ObjectProvider<AgentNodeConnector> connectorProvider,
                                  ObjectProvider<AgentAuditBridge> auditBridgeProvider,
                                  ObjectMapper mapper) {
        this.workspaceService = workspaceService;
        this.terminalSessionRepo = terminalSessionRepo;
        this.projectService = projectService;
        this.sessionManagerService = sessionManagerService;
        this.connectorProvider = connectorProvider;
        this.auditBridgeProvider = auditBridgeProvider;
        this.mapper = mapper;
    }

    // ---------------- 打开 / 心跳 / 关闭 ----------------

    /**
     * 打开终端：占用锁（他人 90s 新鲜锁 → 409 带占用方）→ 重发 terminal_ws_prepare 幂等
     * 物化/重绑 → 会话 RUNNING（本人有 SUSPENDED 记录则复活复用）。物化失败回滚锁与会话行。
     */
    public TerminalOpenView open(String workspaceId) {
        TerminalWorkspaceEntity ent = workspaceService.require(workspaceId);
        workspaceService.requireOpen(ent);
        String actor = workspaceService.currentLoginUser();

        TerminalSessionEntity session = terminalSessionRepo
                .findByWorkspaceIdOrderByStartedAtDesc(ent.getId()).stream()
                .filter(s -> actor.equals(s.getUserId())
                        && TerminalSessionState.SUSPENDED.equals(s.getState()))
                .findFirst()
                .orElseGet(() -> {
                    TerminalSessionEntity s = new TerminalSessionEntity();
                    s.setId(UUID.randomUUID().toString());
                    s.setWorkspaceId(ent.getId());
                    s.setUserId(actor);
                    return s;
                });
        // 先加锁（409 占用冲突在这步抛，不落任何会话行变更）
        workspaceService.lockInternal(ent, session.getId());
        try {
            TerminalWsPrepareResult prepared = requireConnector().terminalWorkspacePrepare(
                    ent.getAgentNodeId(), ent.getWorkspaceKey(), ent.getProjectId(), ent.getOwner(),
                    rebuildSpecs(ent));
            if (!prepared.ok()) {
                throw new DevMindException(ErrorCode.CONFLICT, "终端工作区重绑失败: " + prepared.error());
            }
            Instant now = Instant.now();
            session.setState(TerminalSessionState.RUNNING);
            if (session.getStartedAt() == null) {
                session.setStartedAt(now);
            }
            session.setLastHeartbeatAt(now);
            session.setEndedAt(null);
            terminalSessionRepo.save(session);
            ent.setLastActiveAt(now);
            log.info("终端会话打开: ws={} key={} session={} user={}", ent.getId(),
                    ent.getWorkspaceKey(), session.getId(), actor);
            return new TerminalOpenView(toView(session), ent.getWorkspaceKey(),
                    prepared.path(), prepared.branch());
        } catch (RuntimeException e) {
            // 物化/重绑失败：回滚刚拿的锁（会话行尚未落库或仍 SUSPENDED，无需回收）
            ent.setLockSessionId(null);
            ent.setLockOwner(null);
            ent.setLockAt(null);
            workspaceService.save(ent);
            throw e;
        }
    }

    /** 心跳：续期会话 last_heartbeat_at + 工作区锁（仅持锁本人）。 */
    public TerminalSessionView heartbeat(String workspaceId) {
        TerminalWorkspaceEntity ent = workspaceService.require(workspaceId);
        String actor = workspaceService.currentLoginUser();
        TerminalSessionEntity session = requireRunningLockHolder(ent, actor);
        Instant now = Instant.now();
        session.setLastHeartbeatAt(now);
        ent.setLockAt(now);
        ent.setLastActiveAt(now);
        terminalSessionRepo.save(session);
        return toView(session);
    }

    /** 关闭：会话 CLOSED + 释放本人占用锁（幂等：已 CLOSED 直接返回）。 */
    public TerminalSessionView close(String workspaceId) {
        TerminalWorkspaceEntity ent = workspaceService.require(workspaceId);
        String actor = workspaceService.currentLoginUser();
        TerminalSessionEntity session = terminalSessionRepo
                .findByWorkspaceIdOrderByStartedAtDesc(ent.getId()).stream()
                .filter(s -> actor.equals(s.getUserId())
                        && TerminalSessionState.RUNNING.equals(s.getState()))
                .findFirst().orElse(null);
        if (session == null) {
            // 幂等：返回本人最近一条会话（可能已 CLOSED/SUSPENDED）；完全没有则 404
            session = terminalSessionRepo.findByWorkspaceIdOrderByStartedAtDesc(ent.getId()).stream()
                    .filter(s -> actor.equals(s.getUserId()))
                    .findFirst()
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                            "当前用户在该终端工作区无会话记录"));
            return toView(session);
        }
        Instant now = Instant.now();
        session.setState(TerminalSessionState.CLOSED);
        session.setEndedAt(now);
        terminalSessionRepo.save(session);
        if (actor.equals(ent.getLockOwner())) {
            ent.setLockSessionId(null);
            ent.setLockOwner(null);
            ent.setLockAt(null);
        }
        ent.setLastActiveAt(now);
        workspaceService.save(ent);
        log.info("终端会话关闭: ws={} session={} user={}", ent.getId(), session.getId(), actor);
        return toView(session);
    }

    // ---------------- 命令透传（CAP-58/59 帧复用） ----------------

    /**
     * 执行终端命令：危险命令护栏（未 confirmed → 428；confirmed → 放行并补记确认审计）
     * → terminal_exec 帧透传（伪会话 id = workspaceKey）。命令非零退出不抛，看 exitCode。
     */
    public TerminalExecResult exec(String workspaceId, String command, String cwd, Boolean confirmed) {
        if (command == null || command.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "command 不能为空");
        }
        TerminalWorkspaceEntity ent = workspaceService.require(workspaceId);
        String actor = workspaceService.currentLoginUser();
        requireRunningLockHolder(ent, actor);
        String danger = DangerousCommandGuard.check(command);
        if (danger != null && !Boolean.TRUE.equals(confirmed)) {
            throw new DevMindException(ErrorCode.NEED_CONFIRM,
                    "危险命令需确认（" + danger + "）：" + command);
        }
        if (danger != null) {
            AgentAuditBridge bridge = auditBridgeProvider.getIfAvailable();
            if (bridge != null) {
                bridge.recordCommand("TERMINAL_DANGER_CONFIRM", ent.getAgentNodeId(),
                        ent.getWorkspaceKey(), true, actor,
                        "危险命令经确认放行（" + danger + "）: " + command);
            }
        }
        touch(ent);
        return requireConnector().terminalExec(ent.getAgentNodeId(), ent.getWorkspaceKey(),
                command, cwd);
    }

    /** Tab 补全透传。 */
    public TerminalCompleteResult complete(String workspaceId, String input, String cwd) {
        TerminalWorkspaceEntity ent = workspaceService.require(workspaceId);
        String actor = workspaceService.currentLoginUser();
        requireRunningLockHolder(ent, actor);
        return requireConnector().terminalComplete(ent.getAgentNodeId(), ent.getWorkspaceKey(),
                input, cwd);
    }

    /** 取消执行中命令（fire-and-forget）。 */
    public void cancel(String workspaceId) {
        TerminalWorkspaceEntity ent = workspaceService.require(workspaceId);
        String actor = workspaceService.currentLoginUser();
        requireRunningLockHolder(ent, actor);
        requireConnector().terminalCancel(ent.getAgentNodeId(), ent.getWorkspaceKey());
    }

    // ---------------- 文件读写 ----------------

    /** 文件树（复用 CAP-54 workspace_query tree 只读帧；无需持锁——浏览是只读的）。 */
    public Map<String, Object> fileTree(String workspaceId, String path) {
        TerminalWorkspaceEntity ent = workspaceService.require(workspaceId);
        return queryPayload(ent, "tree", path);
    }

    /**
     * 文件内容（复用 CAP-54 workspace_query file 只读帧；无需持锁）。
     * 附算 sha（UTF-8 内容的 sha256，与 runner 侧字节级口径一致——合法 UTF-8 文件完全相等）：
     * 前端编辑器据此回传 expectedSha 做乐观锁。
     */
    public Map<String, Object> fileRead(String workspaceId, String path) {
        TerminalWorkspaceEntity ent = workspaceService.require(workspaceId);
        Map<String, Object> payload = new HashMap<>(queryPayload(ent, "file", path));
        Object content = payload.get("content");
        if (content instanceof String s) {
            payload.put("sha", sha256Hex(s.getBytes(StandardCharsets.UTF_8)));
        }
        return payload;
    }

    /**
     * 文件直写（workspace_file_write 帧）：持锁本人 + expectedSha 乐观锁（sha 不符 → 409
     * 带当前 sha，前端提示刷新）。成功返回 {path, sha} 供下一轮续用。
     */
    public Map<String, Object> fileWrite(String workspaceId, String path, String contentBase64,
                                         String expectedSha) {
        if (path == null || path.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "path 不能为空");
        }
        if (contentBase64 == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "contentBase64 必填（空文件传空串）");
        }
        TerminalWorkspaceEntity ent = workspaceService.require(workspaceId);
        String actor = workspaceService.currentLoginUser();
        requireRunningLockHolder(ent, actor);
        WorkspaceFileWriteResult r = requireConnector().workspaceFileWrite(ent.getAgentNodeId(),
                ent.getWorkspaceKey(), ent.getProjectId(), ent.getOwner(), path, contentBase64,
                expectedSha);
        if (r.conflict()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    (r.error() != null ? r.error() : "文件已被修改，请刷新后重试") + "（当前 sha=" + r.sha() + "）");
        }
        if (!r.ok()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    r.error() == null || r.error().isBlank() ? "文件写入失败" : r.error());
        }
        touch(ent);
        return Map.of("path", r.path(), "sha", r.sha());
    }

    /** 命令历史：COMMAND 类审计按 workspaceKey 倒序（agent 未装配 = 空表）。 */
    public List<AgentAuditBridge.Entry> history(String workspaceId, int limit) {
        TerminalWorkspaceEntity ent = workspaceService.require(workspaceId);
        AgentAuditBridge bridge = auditBridgeProvider.getIfAvailable();
        if (bridge == null) {
            return List.of();
        }
        return bridge.recentCommandsByTarget(ent.getWorkspaceKey(), limit);
    }

    // ---------------- 巡检 ----------------

    /**
     * 30s 一跳：RUNNING 会话 90s 无心跳或节点离线 → SUSPENDED + 释放占用锁。
     * 工作区保留，reopen 即恢复（open 重发 prepare 重绑 runner）。
     */
    @Scheduled(fixedDelayString = "${devmind.terminal.session-watchdog-ms:30000}",
            initialDelayString = "${devmind.terminal.session-watchdog-ms:30000}")
    public void watchdog() {
        try {
            suspendStale();
        } catch (Exception e) {
            log.warn("终端会话巡检异常: {}", e.getMessage());
        }
    }

    /** 巡检本体（包可见便于单测同步驱动）。返回挂起数。 */
    int suspendStale() {
        List<TerminalSessionEntity> running =
                terminalSessionRepo.findByState(TerminalSessionState.RUNNING);
        if (running.isEmpty()) {
            return 0;
        }
        Instant cutoff = Instant.now().minusSeconds(HEARTBEAT_FRESH_SEC);
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        int suspended = 0;
        for (TerminalSessionEntity session : running) {
            TerminalWorkspaceEntity ent = workspaceService.find(session.getWorkspaceId());
            if (ent == null || !TerminalWorkspaceService.STATE_OPEN.equals(ent.getState())) {
                continue; // 工作区已收口/释放：会话行留着不挂起（M3 收口链路自会处理锁）
            }
            boolean staleHeartbeat = session.getLastHeartbeatAt() == null
                    || session.getLastHeartbeatAt().isBefore(cutoff);
            boolean nodeOffline = connector == null || !connector.isOnline(ent.getAgentNodeId());
            if (!staleHeartbeat && !nodeOffline) {
                continue;
            }
            session.setState(TerminalSessionState.SUSPENDED);
            session.setEndedAt(Instant.now());
            terminalSessionRepo.save(session);
            if (session.getId().equals(ent.getLockSessionId())) {
                ent.setLockSessionId(null);
                ent.setLockOwner(null);
                ent.setLockAt(null);
                workspaceService.save(ent);
            }
            suspended++;
            log.info("终端会话挂起: ws={} session={} 原因={}", ent.getId(), session.getId(),
                    staleHeartbeat ? "心跳超时" : "节点离线");
        }
        return suspended;
    }

    // ---------------- 内部 ----------------

    /** 持锁且会话 RUNNING 校验（exec/heartbeat/cancel/complete/fileWrite 共用），返回会话行。 */
    private TerminalSessionEntity requireRunningLockHolder(TerminalWorkspaceEntity ent, String actor) {
        workspaceService.requireOpen(ent);
        if (ent.getLockOwner() == null || !ent.getLockOwner().equals(actor)) {
            throw new DevMindException(ErrorCode.CONFLICT, ent.getLockOwner() != null
                    ? "终端工作区正被 " + ent.getLockOwner() + " 占用"
                    : "终端工作区未打开终端（请先 open）");
        }
        TerminalSessionEntity session = ent.getLockSessionId() != null
                ? terminalSessionRepo.findById(ent.getLockSessionId()).orElse(null) : null;
        if (session == null || !TerminalSessionState.RUNNING.equals(session.getState())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "终端会话已挂起/关闭，请重新打开（open 可恢复挂起会话）");
        }
        return session;
    }

    /** exec/fileWrite 计活跃：刷新 last_active（锁续期交给心跳，避免与心跳语义混叠）。 */
    private void touch(TerminalWorkspaceEntity ent) {
        ent.setLastActiveAt(Instant.now());
        workspaceService.save(ent);
    }

    private Map<String, Object> queryPayload(TerminalWorkspaceEntity ent, String action, String path) {
        WorkspaceQueryResult r = requireConnector().workspaceQuery(ent.getAgentNodeId(),
                ent.getWorkspaceKey(), action, "", path);
        if (!r.ok()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    r.error() == null || r.error().isBlank() ? "工作区查询失败" : r.error());
        }
        return r.payload();
    }

    /** 按 repos_json 快照重建带凭据的 specs（open 重发 prepare 用；与创建时同口径）。 */
    @SuppressWarnings("unchecked")
    private List<AgentLaunchCommand.RepoSpec> rebuildSpecs(TerminalWorkspaceEntity ent) {
        List<Map<String, Object>> rows;
        try {
            rows = mapper.readValue(ent.getReposJson(), List.class);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "终端工作区仓库快照损坏，无法重绑: " + e.getMessage());
        }
        List<SessionRepoEntity> specs = new ArrayList<>();
        for (Map<String, Object> m : rows) {
            SessionRepoEntity e = new SessionRepoEntity();
            e.setProjectRepoId(asLong(m.get("projectRepoId")));
            e.setName(asStr(m.get("name")));
            e.setRemoteUrl(asStr(m.get("remoteUrl")));
            e.setLocalPath(asStr(m.get("localPath")));
            e.setBaseBranch(asStr(m.get("baseBranch")));
            e.setBranch(asStr(m.get("branch")));
            e.setIsPrimary(Boolean.TRUE.equals(m.get("isPrimary")));
            Object so = m.get("sortOrder");
            e.setSortOrder(so instanceof Number n ? n.intValue() : 0);
            specs.add(e);
        }
        Project project = projectService.requireProject(ent.getProjectId());
        return sessionManagerService.buildRepoSpecs(project, specs, ent.getBaseBranch(),
                ent.getBranch(), ent.getCreatedBy());
    }

    private static String asStr(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    /** 与 runner 侧 AgentRunnerMain.sha256Hex 同口径（小写 hex） */
    private static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private AgentNodeConnector requireConnector() {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
        }
        return connector;
    }

    private TerminalSessionView toView(TerminalSessionEntity s) {
        return new TerminalSessionView(s.getId(), s.getWorkspaceId(), s.getUserId(), s.getState(),
                s.getStartedAt(), s.getLastHeartbeatAt(), s.getEndedAt());
    }

    /** 会话状态常量（魔法串集中，避免散写）。 */
    static final class TerminalSessionState {
        static final String RUNNING = "RUNNING";
        static final String SUSPENDED = "SUSPENDED";
        static final String CLOSED = "CLOSED";

        private TerminalSessionState() {
        }
    }
}
