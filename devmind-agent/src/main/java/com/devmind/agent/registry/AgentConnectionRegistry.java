package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentAuditLogEntity;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentAuditLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentCollectResult;
import com.devmind.common.agent.AgentEventFrame;
import com.devmind.common.agent.AgentEventListener;
import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.agent.AgentExecResult;
import com.devmind.common.agent.AgentFileRequest;
import com.devmind.common.agent.AgentFileResult;
import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.AgentPkgCommand;
import com.devmind.common.agent.AgentPkgResult;
import com.devmind.common.agent.AgentProcCommand;
import com.devmind.common.agent.AgentProcResult;
import com.devmind.common.agent.AgentProtocol;
import com.devmind.common.agent.AgentVerifyCommand;
import com.devmind.common.agent.AgentVerifyResult;
import com.devmind.common.agent.FinalizeResult;
import com.devmind.common.agent.InputImage;
import com.devmind.common.agent.TerminalCompleteResult;
import com.devmind.common.agent.TerminalExecResult;
import com.devmind.common.agent.WorkspaceQueryResult;
import com.devmind.common.agent.WorkspaceReleaseResult;
import com.devmind.common.agent.WorkspaceStatusListener;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.GitIdentityProvider.GitAuthor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * CAP-21 节点连接注册表：持有 nodeId → runner WS 连接，实现 {@link AgentNodeConnector}
 * （指令下发，含 launch 同步 ack）并被 AgentNodeWsHandler 回调（上行帧路由 + 断线处理）。
 *
 * <p>launch 采用"发帧 + CompletableFuture 等 ack"：runner 拉起子进程后回
 * {@code {type:"launched",sessionId,ok,error}}，超时/失败即创建失败，不产生挂死会话。</p>
 */
@Component
public class AgentConnectionRegistry implements AgentNodeConnector {

    private static final Logger log = LoggerFactory.getLogger(AgentConnectionRegistry.class);

    private record LaunchAck(boolean ok, String error) {
    }

    /** upgrade 指令 ack（CAP-21 FR-09）：reason=busy 时 activeSessions 为活跃会话数。 */
    public record UpgradeAck(boolean ok, String reason, int activeSessions) {
    }

    /** CAP-36 exec 等待者：日志帧实时推 sink，exec_exit 完成 future。 */
    private record ExecWaiter(String nodeId, java.util.function.Consumer<String> sink,
                              CompletableFuture<AgentExecResult> done) {
    }

    /** CAP-39 collect_output 等待者：带 nodeId 供断连时批量失败。 */
    private record CollectWaiter(String nodeId, CompletableFuture<AgentCollectResult> done) {
    }

    /** CAP-42 workspace_finalize 等待者：带 nodeId 供断连时批量失败。 */
    private record FinalizeWaiter(String nodeId, CompletableFuture<FinalizeResult> done) {
    }

    /** CAP-42 workspace_release 等待者：带 nodeId 供断连时批量失败。 */
    private record ReleaseWaiter(String nodeId, CompletableFuture<WorkspaceReleaseResult> done) {
    }

    /** CAP-54 workspace_query 等待者：带 nodeId 供断连时批量失败。 */
    private record WorkspaceQueryWaiter(String nodeId, CompletableFuture<WorkspaceQueryResult> done) {
    }

    /** CAP-57：proc 等待者（受管进程起停/状态，阻塞等 ack） */
    private record ProcWaiter(String nodeId, CompletableFuture<AgentProcResult> done) {
    }

    /** CAP-57：pkg 等待者（安装包分发，GB 下载以分钟计，future 异步收口） */
    private record PkgWaiter(String nodeId, CompletableFuture<AgentPkgResult> done) {
    }

    /** CAP-58 terminal_exec 等待者。 */
    private record TerminalExecWaiter(String nodeId, CompletableFuture<TerminalExecResult> done) {
    }

    /** CAP-59 terminal_complete 等待者。 */
    private record TerminalCompleteWaiter(String nodeId, CompletableFuture<TerminalCompleteResult> done) {
    }

    /** CAP-65 file 等待者（节点文件浏览 op，阻塞等 file_ack）。 */
    private record FileWaiter(String nodeId, CompletableFuture<AgentFileResult> done) {
    }

    /** CAP-73 verify 等待者：verify_log 帧实时推 sink，verify_ack 完成 future。 */
    private record VerifyWaiter(String nodeId, java.util.function.Consumer<String> sink,
                                CompletableFuture<AgentVerifyResult> done) {
    }

    private final AgentNodeService nodeService;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final ObjectProvider<AgentEventListener> listenerProvider;
    /** CAP-54：workspace_status 上行帧广播（session/chat 各自认领，独立于会话事件流） */
    private final ObjectProvider<WorkspaceStatusListener> workspaceListenerProvider;
    private final AgentAuditLogService auditLogService;

    /** nodeId(字符串) → runner WS 连接 */
    private final Map<String, WebSocketSession> connections = new ConcurrentHashMap<>();
    /** nodeId → 最近一次收到任何帧的时间（心跳超时判 OFFLINE） */
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    /** sessionId → launch ack 等待者 */
    private final Map<String, CompletableFuture<LaunchAck>> pendingLaunches = new ConcurrentHashMap<>();
    /** nodeId → upgrade ack 等待者（同节点同时只允许一个升级） */
    private final Map<String, CompletableFuture<UpgradeAck>> pendingUpgrades = new ConcurrentHashMap<>();
    /** nodeId → hello 上报的协议版本（CAP-34 FR-08，断连清除；无记录按 v1 对待） */
    private final Map<String, Integer> protocolVersions = new ConcurrentHashMap<>();
    /** execId → exec 等待者（CAP-36） */
    private final Map<String, ExecWaiter> pendingExecs = new ConcurrentHashMap<>();
    /** sessionId → collect_output 等待者（CAP-39） */
    private final Map<String, CollectWaiter> pendingCollects = new ConcurrentHashMap<>();
    /** requestId → workspace_finalize 等待者（CAP-42） */
    private final Map<String, FinalizeWaiter> pendingFinalizes = new ConcurrentHashMap<>();
    /** requestId → workspace_release 等待者（CAP-42 删除会话释放固定工作区） */
    private final Map<String, ReleaseWaiter> pendingReleases = new ConcurrentHashMap<>();
    /** requestId → workspace_query 等待者（CAP-54 工作区只读查询） */
    private final Map<String, WorkspaceQueryWaiter> pendingWorkspaceQueries = new ConcurrentHashMap<>();
    /** CAP-58：requestId → 终端命令执行等待者 */
    private final Map<String, TerminalExecWaiter> pendingTerminalExecs = new ConcurrentHashMap<>();
    /** CAP-59：requestId → 终端补全等待者 */
    private final Map<String, TerminalCompleteWaiter> pendingTerminalCompletes = new ConcurrentHashMap<>();    /** requestId → proc 等待者（CAP-57 服务实例进程管控） */
    private final Map<String, ProcWaiter> pendingProcs = new ConcurrentHashMap<>();
    /** requestId → pkg 等待者（CAP-57 安装包分发） */
    private final Map<String, PkgWaiter> pendingPkgs = new ConcurrentHashMap<>();
    /** requestId → file 等待者（CAP-65 节点文件浏览） */
    private final Map<String, FileWaiter> pendingFiles = new ConcurrentHashMap<>();
    /** requestId → verify 等待者（CAP-73 验证链三阶段指令） */
    private final Map<String, VerifyWaiter> pendingVerifies = new ConcurrentHashMap<>();

    public AgentConnectionRegistry(AgentNodeService nodeService, AgentProperties props,
                                   ObjectMapper mapper, ObjectProvider<AgentEventListener> listenerProvider,
                                   ObjectProvider<WorkspaceStatusListener> workspaceListenerProvider,
                                   AgentAuditLogService auditLogService) {
        this.nodeService = nodeService;
        this.props = props;
        this.mapper = mapper;
        this.listenerProvider = listenerProvider;
        this.workspaceListenerProvider = workspaceListenerProvider;
        this.auditLogService = auditLogService;
    }

    // ---------------- 连接生命周期（AgentNodeWsHandler 回调） ----------------

    public void onConnect(AgentNodeEntity node, WebSocketSession ws) {
        String nodeId = String.valueOf(node.getId());
        WebSocketSession old = connections.put(nodeId, ws);
        if (old != null && old.isOpen()) {
            kickDuplicate(old); // 同节点重复接入：踢掉旧连接（专用关闭码 4000，runner 侧走长退避防互踢风暴）
            auditLogService.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_CONNECTION, "KICK_DUPLICATE")
                    .node(node).remoteAddr(AgentAuditLogService.formatRemoteAddr(old.getRemoteAddress()))
                    .result(AgentAuditLogEntity.RESULT_OK).detail("同节点重复接入，踢掉旧连接"));
        }
        lastSeen.put(nodeId, System.currentTimeMillis());
        String remoteAddr = AgentAuditLogService.formatRemoteAddr(ws.getRemoteAddress());
        nodeService.markOnline(node.getId(), remoteAddr);
        auditLogService.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_CONNECTION, "CONNECT")
                .node(node).remoteAddr(remoteAddr).result(AgentAuditLogEntity.RESULT_OK));
        log.info("agent 节点上线: id={} name={} remote={}", nodeId, node.getName(), remoteAddr);
    }

    public void onDisconnect(AgentNodeEntity node, WebSocketSession ws) {
        String nodeId = String.valueOf(node.getId());
        // 只清自己这条连接（防旧连接关闭事件误清新连接）
        if (!connections.remove(nodeId, ws)) {
            return;
        }
        lastSeen.remove(nodeId);
        protocolVersions.remove(nodeId);
        nodeService.markOffline(node.getId());
        auditLogService.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_CONNECTION, "DISCONNECT")
                .node(node).remoteAddr(AgentAuditLogService.formatRemoteAddr(ws.getRemoteAddress()))
                .result(AgentAuditLogEntity.RESULT_OK));
        log.info("agent 节点离线: id={} name={}", nodeId, node.getName());
        // 断线即失败进行中的升级等待（升级中的 runner 断连属预期：换包重启）
        CompletableFuture<UpgradeAck> pending = pendingUpgrades.remove(nodeId);
        if (pending != null) {
            pending.complete(new UpgradeAck(false, "disconnect", 0));
        }
        // CAP-36：断线即失败该节点进行中的 exec（runner 已死，exec_exit 不会再来）
        for (Map.Entry<String, ExecWaiter> e : pendingExecs.entrySet()) {
            if (e.getValue().nodeId().equals(nodeId) && pendingExecs.remove(e.getKey(), e.getValue())) {
                e.getValue().done().complete(AgentExecResult.failed("节点断连，exec 中断"));
            }
        }
        // CAP-39：断线即失败该节点进行中的产出收集（ack 不会再来）
        for (Map.Entry<String, CollectWaiter> e : pendingCollects.entrySet()) {
            if (e.getValue().nodeId().equals(nodeId) && pendingCollects.remove(e.getKey(), e.getValue())) {
                e.getValue().done().complete(new AgentCollectResult(false, "节点断连，产出回传中断"));
            }
        }
        // CAP-42：断线即失败该节点进行中的工作区收口（ack 不会再来）
        for (Map.Entry<String, FinalizeWaiter> e : pendingFinalizes.entrySet()) {
            if (e.getValue().nodeId().equals(nodeId) && pendingFinalizes.remove(e.getKey(), e.getValue())) {
                e.getValue().done().complete(FinalizeResult.failed("节点断连，工作区收口中断"));
            }
        }
        // CAP-42：断线即失败该节点进行中的工作区释放（ack 不会再来）
        for (Map.Entry<String, ReleaseWaiter> e : pendingReleases.entrySet()) {
            if (e.getValue().nodeId().equals(nodeId) && pendingReleases.remove(e.getKey(), e.getValue())) {
                e.getValue().done().complete(WorkspaceReleaseResult.failed("节点断连，工作区释放中断"));
            }
        }
        // CAP-54：断线即失败该节点进行中的工作区查询（ack 不会再来）
        for (Map.Entry<String, WorkspaceQueryWaiter> e : pendingWorkspaceQueries.entrySet()) {
            if (e.getValue().nodeId().equals(nodeId) && pendingWorkspaceQueries.remove(e.getKey(), e.getValue())) {
                e.getValue().done().complete(WorkspaceQueryResult.failed("节点断连，工作区查询中断"));
            }
        }
        // CAP-58：断线即失败该节点进行中的终端命令（ack 不会再来）
        for (Map.Entry<String, TerminalExecWaiter> e : pendingTerminalExecs.entrySet()) {
            if (e.getValue().nodeId().equals(nodeId) && pendingTerminalExecs.remove(e.getKey(), e.getValue())) {
                e.getValue().done().complete(TerminalExecResult.failed("节点断连，终端命令中断"));
            }
        }
        // CAP-59：断线即失败该节点进行中的终端补全（ack 不会再来）
        for (Map.Entry<String, TerminalCompleteWaiter> e : pendingTerminalCompletes.entrySet()) {
            if (e.getValue().nodeId().equals(nodeId) && pendingTerminalCompletes.remove(e.getKey(), e.getValue())) {
                e.getValue().done().complete(TerminalCompleteResult.failed("节点断连，终端补全中断"));
            }
        }
        // CAP-57：断线即失败该节点进行中的进程管控/包分发（ack 不会再来）
        for (Map.Entry<String, ProcWaiter> e : pendingProcs.entrySet()) {
            if (e.getValue().nodeId().equals(nodeId) && pendingProcs.remove(e.getKey(), e.getValue())) {
                e.getValue().done().complete(AgentProcResult.fail("", AgentProcResult.UNKNOWN,
                        "节点断连，进程管控操作中断"));
            }
        }
        for (Map.Entry<String, PkgWaiter> e : pendingPkgs.entrySet()) {
            if (e.getValue().nodeId().equals(nodeId) && pendingPkgs.remove(e.getKey(), e.getValue())) {
                e.getValue().done().complete(AgentPkgResult.fail("节点断连，安装包分发中断"));
            }
        }
        // CAP-65：断线即失败该节点进行中的文件操作（ack 不会再来）
        for (Map.Entry<String, FileWaiter> e : pendingFiles.entrySet()) {
            if (e.getValue().nodeId().equals(nodeId) && pendingFiles.remove(e.getKey(), e.getValue())) {
                e.getValue().done().complete(AgentFileResult.failed("节点断连，文件操作中断"));
            }
        }
        // CAP-73：断线即失败该节点进行中的验证指令（verify_ack 不会再来；
        // runner 侧已落盘的补丁/已上传的包由编排端 FAILED 收口语义覆盖）
        for (Map.Entry<String, VerifyWaiter> e : pendingVerifies.entrySet()) {
            if (e.getValue().nodeId().equals(nodeId) && pendingVerifies.remove(e.getKey(), e.getValue())) {
                e.getValue().done().complete(AgentVerifyResult.failed("节点断连，验证指令中断"));
            }
        }
        // CAP-30：事件广播（原 getIfAvailable 单实现，chat 加入后有多实现）——各 bridge
        // 按「自己是否持有该 sessionId 的运行时」自行忽略未命中帧
        listenerProvider.forEach(l -> l.onAgentDisconnected(nodeId));
    }

    // ---------------- 上行帧处理 ----------------

    public void onHello(AgentNodeEntity node, AgentHelloMeta meta, List<String> activeSessionIds) {
        String nodeId = String.valueOf(node.getId());
        touch(nodeId);
        nodeService.updateMeta(node.getId(), meta);
        // FR-08：登记协议版本（未上报的老 runner 移除记录 → supports 按 v1 兜底）
        if (meta.protocolVersion() != null) {
            protocolVersions.put(nodeId, meta.protocolVersion());
        } else {
            protocolVersions.remove(nodeId);
        }
        listenerProvider.forEach(l -> l.onAgentHello(nodeId, activeSessionIds));
    }

    public void onEvent(AgentNodeEntity node, AgentEventFrame frame) {
        touch(String.valueOf(node.getId()));
        listenerProvider.forEach(l -> l.onAgentEvent(String.valueOf(node.getId()), frame));
    }

    public void onExit(AgentNodeEntity node, String sessionId, int exitCode) {
        touch(String.valueOf(node.getId()));
        listenerProvider.forEach(l -> l.onAgentExit(String.valueOf(node.getId()), sessionId, exitCode));
    }

    public void onLaunchAck(String sessionId, boolean ok, String error) {
        CompletableFuture<LaunchAck> future = pendingLaunches.remove(sessionId);
        if (future != null) {
            future.complete(new LaunchAck(ok, error));
        }
    }

    public void onUpgradeAck(String nodeId, boolean ok, String reason, int activeSessions) {
        CompletableFuture<UpgradeAck> future = pendingUpgrades.remove(nodeId);
        if (future != null) {
            future.complete(new UpgradeAck(ok, reason, activeSessions));
        }
    }

    /** CAP-36：exec_log 上行帧 → 实时推给等待中的 sink（stderr 行前缀与 LocalStepRunner 一致）。 */
    public void onExecLog(String nodeId, String execId, String stream, String chunk) {
        touch(nodeId);
        ExecWaiter w = pendingExecs.get(execId);
        if (w == null || !w.nodeId().equals(nodeId)) {
            return;
        }
        try {
            w.sink().accept("stderr".equals(stream) ? "[stderr] " + chunk : chunk);
        } catch (Exception e) {
            log.debug("exec_log sink 异常: {}", e.getMessage());
        }
    }

    /** CAP-36：exec_exit 上行帧 → 收口完成等待 future。 */
    public void onExecExit(String nodeId, String execId, int code, boolean timedOut, String error) {
        touch(nodeId);
        ExecWaiter w = pendingExecs.get(execId);
        if (w != null && w.nodeId().equals(nodeId)) {
            w.done().complete(new AgentExecResult(code, timedOut, error));
        }
    }

    /** CAP-39：output_collected 上行帧 → 完成等待 future。 */
    public void onOutputCollectedAck(String sessionId, boolean ok, String error) {
        CollectWaiter w = pendingCollects.remove(sessionId);
        if (w != null) {
            w.done().complete(new AgentCollectResult(ok, error));
        }
    }

    /** CAP-42：workspace_finalize_ack 上行帧 → 完成等待 future。 */
    public void onWorkspaceFinalizeAck(String nodeId, String requestId, boolean ok, String detail, String error) {
        touch(nodeId);
        FinalizeWaiter w = pendingFinalizes.remove(requestId);
        if (w != null) {
            w.done().complete(ok ? FinalizeResult.ok(detail) : FinalizeResult.failed(error));
        }
    }

    /** CAP-42：workspace_release_ack 上行帧 → 完成等待 future。 */
    public void onWorkspaceReleaseAck(String nodeId, String requestId, boolean ok, String detail, String error) {
        touch(nodeId);
        ReleaseWaiter w = pendingReleases.remove(requestId);
        if (w != null) {
            w.done().complete(ok ? WorkspaceReleaseResult.ok(detail) : WorkspaceReleaseResult.failed(error));
        }
    }

    /**
     * CAP-54：workspace_status 上行帧 → 广播给各 WorkspaceStatusListener（session/chat bridge
     * 按自己是否持有该 sessionId 认领）。瞬态视图：agent 模块不缓存、不落库、不解析内容。
     */
    public void onWorkspaceStatus(AgentNodeEntity node, String sessionId, Map<String, Object> snapshot) {
        touch(String.valueOf(node.getId()));
        workspaceListenerProvider.forEach(
                l -> l.onWorkspaceStatus(String.valueOf(node.getId()), sessionId, snapshot));
    }

    /** CAP-54：workspace_query_ack 上行帧 → 完成等待 future。 */
    public void onWorkspaceQueryAck(String nodeId, String requestId, boolean ok,
                                    Map<String, Object> payload, String error) {
        touch(nodeId);
        WorkspaceQueryWaiter w = pendingWorkspaceQueries.remove(requestId);
        if (w != null) {
            w.done().complete(ok ? WorkspaceQueryResult.ok(payload) : WorkspaceQueryResult.failed(error));
        }
    }

    /** CAP-58：terminal_exec_ack 上行帧 → 完成等待 future（CAP-59 增 cancelled 可选字段）。 */
    public void onTerminalExecAck(String nodeId, String requestId, boolean ok, int exitCode,
                                  String stdout, String stderr, String cwd, boolean timedOut,
                                  boolean cancelled, String error) {
        touch(nodeId);
        TerminalExecWaiter w = pendingTerminalExecs.remove(requestId);
        if (w != null) {
            w.done().complete(ok
                    ? TerminalExecResult.of(exitCode, stdout, stderr, cwd, timedOut, cancelled)
                    : TerminalExecResult.failed(error));
        }
    }

    /** CAP-59：terminal_complete_ack 上行帧 → 完成等待 future。 */
    public void onTerminalCompleteAck(String nodeId, String requestId, boolean ok, String word,
                                      List<String> candidates, String error) {
        touch(nodeId);
        TerminalCompleteWaiter w = pendingTerminalCompletes.remove(requestId);
        if (w != null) {
            w.done().complete(ok
                    ? TerminalCompleteResult.of(word == null ? "" : word,
                            candidates == null ? List.of() : candidates)
                    : TerminalCompleteResult.failed(error));
        }
    }

    /** CAP-57：proc_ack 上行帧 → 完成等待 future（ok/status/pid 原样透传给 classify 模块判定）。 */
    public void onProcAck(String nodeId, String requestId, boolean ok, String action, String status,
                          Long pid, String error, String detail) {
        touch(nodeId);
        ProcWaiter w = pendingProcs.remove(requestId);
        if (w != null) {
            w.done().complete(new AgentProcResult(ok, action, status, pid,
                    error == null ? "" : error, detail == null ? "" : detail));
        }
    }

    /** CAP-57：pkg_ack 上行帧 → 完成等待 future（installDir 为节点侧绝对路径，落 installs 表）。 */
    public void onPkgAck(String nodeId, String requestId, boolean ok, String installDir, String error) {
        touch(nodeId);
        PkgWaiter w = pendingPkgs.remove(requestId);
        if (w != null) {
            w.done().complete(ok ? AgentPkgResult.ok(installDir) : AgentPkgResult.fail(error));
        }
    }

    /** CAP-65：file_ack 上行帧 → 完成等待 future。 */
    public void onFileAck(String nodeId, String requestId, boolean ok,
                          Map<String, Object> payload, String error) {
        touch(nodeId);
        FileWaiter w = pendingFiles.remove(requestId);
        if (w != null) {
            w.done().complete(ok ? AgentFileResult.ok(payload) : AgentFileResult.failed(error));
        }
    }

    /** CAP-73：verify_log 上行帧 → 实时推给等待中的 sink（进度行进 run.logs_text）。 */
    public void onVerifyLog(String nodeId, String requestId, String chunk) {
        touch(nodeId);
        VerifyWaiter w = pendingVerifies.get(requestId);
        if (w == null || !w.nodeId().equals(nodeId)) {
            return;
        }
        try {
            w.sink().accept(chunk);
        } catch (Exception e) {
            log.debug("verify_log sink 异常: {}", e.getMessage());
        }
    }

    /** CAP-73：verify_ack 上行帧 → 收口完成等待 future（fileCount/filesChanged 按指令类型填充）。 */
    public void onVerifyAck(String nodeId, String requestId, boolean ok, String error,
                            Integer fileCount, Integer filesChanged) {
        touch(nodeId);
        VerifyWaiter w = pendingVerifies.remove(requestId);
        if (w != null) {
            w.done().complete(new AgentVerifyResult(ok, error, fileCount, filesChanged));
        }
    }

    public void touch(String nodeId) {
        lastSeen.put(nodeId, System.currentTimeMillis());
    }

    // ---------------- AgentNodeConnector（指令下发） ----------------

    @Override
    public boolean isOnline(String nodeId) {
        WebSocketSession ws = connections.get(nodeId);
        return ws != null && ws.isOpen();
    }

    @Override
    public String defaultNodeId() {
        return nodeService.defaultNodeId();
    }

    /** FR-08：无 hello 版本记录（老 runner / 未装配）按 {@link AgentProtocol#DEFAULT_WHEN_ABSENT} 对待。 */
    @Override
    public boolean supports(String nodeId, int minVersion) {
        return protocolVersions.getOrDefault(nodeId, AgentProtocol.DEFAULT_WHEN_ABSENT) >= minVersion;
    }

    /** FR-07：标签匹配判定在 service（持有 DB），此处仅委托。 */
    @Override
    public boolean nodeMatches(String nodeId, List<String> requiredLabels) {
        return nodeService.nodeMatchesLabels(nodeId, requiredLabels);
    }

    /**
     * CAP-43：节点配了外网代理时随帧携带 {@code proxy:{url,scopes}}（runner 进程级 holder 生效）。
     * 协议 v8 门控：runner 过旧抛 CONFLICT 明示升级——不静默发送（老 runner 忽略该字段会让
     * 该走代理的网络操作直连失败，又是排查黑洞）。节点未配代理 = 帧不携带，老 runner 零感知。
     */
    private void putProxy(Map<String, Object> frame, String nodeId) {
        AgentNodeEntity node = nodeService.require(Long.parseLong(nodeId));
        boolean configured = node != null && node.getProxyUrl() != null && !node.getProxyUrl().isBlank();
        if (!supports(nodeId, AgentProtocol.NODE_PROXY)) {
            if (configured) {
                throw new DevMindException(ErrorCode.CONFLICT,
                        "节点 " + nodeId + " 已配置外网代理，但 runner 协议版本过低（节点代理需 v"
                                + AgentProtocol.NODE_PROXY + "+），请到节点页升级 runner（或先清空代理配置）");
            }
            return; // 老 runner 且未配置：不带字段（反正不认识）
        }
        // v8+ runner 恒带 proxy 字段：未配置时显式空 url——runner 侧「字段缺席 = 不动 holder」，
        // 若缺席则「先配后清」永远清不掉 runner 上已生效的代理（E2E 实测踩中：清空后 push 仍走旧代理）
        Map<String, Object> proxy = new LinkedHashMap<>();
        if (!configured) {
            proxy.put("url", "");
            proxy.put("scopes", List.of());
        } else {
            proxy.put("url", node.getProxyUrl());
            String scopes = node.getProxyScopes() == null || node.getProxyScopes().isBlank()
                    ? "git" : node.getProxyScopes();
            proxy.put("scopes", List.of(scopes.split(",")));
        }
        frame.put("proxy", proxy);
    }

    /**
     * CAP-51：workspaceKey 非空时要求 v10+。字段缺席（存量会话/未升级服务端）不门控——
     * 那是 FR-11 的存量契约（runner 落旧布局 work/，语义正确），不是降级。
     */
    private void requireKeyProtocol(String nodeId, String workspaceKey, String what) {
        if (workspaceKey == null || workspaceKey.isBlank()) {
            return;
        }
        if (!supports(nodeId, AgentProtocol.REQUIREMENT_WORKSPACE)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（" + what + "需 v"
                            + AgentProtocol.REQUIREMENT_WORKSPACE + "+，需求粒度工作区），请到节点页升级 runner");
        }
    }

    /** FR-07：DB 判 ONLINE 且标签匹配的候选中，挑当前确有活跃连接的第一个。 */
    @Override
    public String pickNodeByLabels(List<String> requiredLabels) {
        for (AgentNodeEntity e : nodeService.onlineMatching(requiredLabels)) {
            String nodeId = String.valueOf(e.getId());
            if (isOnline(nodeId)) {
                return nodeId;
            }
        }
        return null;
    }

    @Override
    public void launch(String nodeId, AgentLaunchCommand cmd) {
        WebSocketSession ws = requireConnection(nodeId);
        CompletableFuture<LaunchAck> future = new CompletableFuture<>();
        pendingLaunches.put(cmd.sessionId(), future);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "launch");
        frame.put("sessionId", cmd.sessionId());
        frame.put("projectId", cmd.projectId());
        frame.put("taskSpec", cmd.taskSpec());
        frame.put("model", cmd.model());
        frame.put("permissionMode", cmd.permissionMode());
        // CAP-30：会话种类（session 缺省 / chat=问答沙箱）；旧 runner 忽略该字段
        if (cmd.kind() != null && !cmd.kind().isBlank()) {
            frame.put("kind", cmd.kind());
        }
        // CAP-24 修复：env 此前漏发（runner 侧读取逻辑已就绪，远程会话提交身份静默失效）
        if (cmd.env() != null && !cmd.env().isEmpty()) {
            frame.put("env", cmd.env());
        }
        // CAP-25：远程工作区描述（token 仅随帧传输，严禁进日志）
        if (cmd.repo() != null) {
            Map<String, Object> repo = new LinkedHashMap<>();
            repo.put("remoteUrl", cmd.repo().remoteUrl());
            repo.put("baseBranch", cmd.repo().baseBranch());
            repo.put("branch", cmd.repo().branch());
            repo.put("token", cmd.repo().token());
            frame.put("repo", repo);
        }
        // CAP-31 修复：repos 多库数组此前漏序列化进帧（多库远程会话静默退化为只拉主库）
        if (cmd.repos() != null && cmd.repos().size() > 1) {
            List<Map<String, Object>> repos = new ArrayList<>();
            for (AgentLaunchCommand.RepoSpec spec : cmd.repos()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("remoteUrl", spec.remoteUrl());
                r.put("baseBranch", spec.baseBranch());
                r.put("branch", spec.branch());
                r.put("token", spec.token());
                r.put("name", spec.name());
                repos.add(r);
            }
            frame.put("repos", repos);
        }
        // CAP-34 FR-03：上下文包清单（runner 据此 HTTP 拉包物化；旧 runner 忽略该字段）
        if (cmd.contextManifest() != null) {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("entries", cmd.contextManifest().entries());
            manifest.put("totalBytes", cmd.contextManifest().totalBytes());
            manifest.put("sha256", cmd.contextManifest().sha256());
            frame.put("contextManifest", manifest);
        }
        // 续接对话（claude --resume 目标）；旧 runner 忽略该字段 → 降级为全新对话
        if (cmd.resumeSessionId() != null && !cmd.resumeSessionId().isBlank()) {
            frame.put("resumeSessionId", cmd.resumeSessionId());
        }
        // CAP-41：worklog 空间归属用户名（runner 定位 {user.home}/worklog/<owner>）；
        // 仅 kind="worklog" 下发，协议 v5 由调用方 supports() 门控
        if (cmd.worklogOwner() != null && !cmd.worklogOwner().isBlank()) {
            frame.put("worklogOwner", cmd.worklogOwner());
        }
        // CAP-42：repo 会话固定工作区归属用户名（runner 定位 <projectId>/<owner>/{main,work}）；
        // 协议 v7 由调用方 supports() 门控。红线：新字段必须在此 put + LaunchTest 断言
        if (cmd.workspaceOwner() != null && !cmd.workspaceOwner().isBlank()) {
            frame.put("workspaceOwner", cmd.workspaceOwner());
        }
        // CAP-51：需求粒度工作区键（runner 落 <owner>/worktrees/<key>）；字段缺席 = 旧布局 work/。
        // 协议 v10 由调用方 supports() 门控。红线：新字段必须在此 put + LaunchTest 断言
        if (cmd.workspaceKey() != null && !cmd.workspaceKey().isBlank()) {
            frame.put("workspaceKey", cmd.workspaceKey());
        }
        putProxy(frame, nodeId);
        long startMs = System.currentTimeMillis();
        try {
            send(ws, frame);
            LaunchAck ack = future.get(props.getLaunchAckTimeoutMs(), TimeUnit.MILLISECONDS);
            auditCommand("LAUNCH", nodeId, cmd.sessionId(), ack.ok(), startMs,
                    ack.ok() ? "kind=" + (cmd.kind() == null || cmd.kind().isBlank() ? "session" : cmd.kind())
                            : "拉起失败: " + ack.error());
            if (!ack.ok()) {
                throw new DevMindException(ErrorCode.INTERNAL,
                        "runner 拉起会话失败: " + (ack.error() == null ? "未知原因" : ack.error()));
            }
        } catch (DevMindException e) {
            pendingLaunches.remove(cmd.sessionId());
            throw e;
        } catch (Exception e) {
            pendingLaunches.remove(cmd.sessionId());
            auditCommand("LAUNCH", nodeId, cmd.sessionId(), false, startMs,
                    "等待 runner 确认超时/异常: " + e.getMessage());
            throw new DevMindException(ErrorCode.CONFLICT, "等待 runner 确认超时/异常: " + e.getMessage(), e);
        }
    }

    @Override
    public void sendInput(String nodeId, String sessionId, String text) {
        sendInput(nodeId, sessionId, text, List.of());
    }

    /** CAP-32：input 帧内嵌 base64 图片（images 字段）；旧 runner 忽略该字段优雅降级=丢图，远程用图需升级 runner。 */
    @Override
    public void sendInput(String nodeId, String sessionId, String text, List<InputImage> images) {
        sendInput(nodeId, sessionId, text, images, List.of());
    }

    /**
     * CAP-68：input 帧增 files 字段（[{id,name,mediaType,data}]，base64 内联），runner 落盘
     * {@code .devmind/incoming/} 并在消息尾部追加路径提示；可选字段不门控（协议 v20），
     * 旧 runner 忽略该字段优雅降级=丢文件不丢文本。
     */
    @Override
    public void sendInput(String nodeId, String sessionId, String text, List<InputImage> images,
                          List<com.devmind.common.agent.InputFile> files) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "input");
        frame.put("sessionId", sessionId);
        frame.put("text", text);
        if (images != null && !images.isEmpty()) {
            List<Map<String, Object>> imgs = new ArrayList<>();
            for (InputImage img : images) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("mediaType", img.mediaType());
                m.put("data", img.base64Data());
                imgs.add(m);
            }
            frame.put("images", imgs);
        }
        if (files != null && !files.isEmpty()) {
            List<Map<String, Object>> fs = new ArrayList<>();
            for (com.devmind.common.agent.InputFile f : files) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", f.attachmentId());
                if (f.name() != null) {
                    m.put("name", f.name());
                }
                if (f.mediaType() != null) {
                    m.put("mediaType", f.mediaType());
                }
                m.put("data", f.base64Data());
                fs.add(m);
            }
            frame.put("files", fs);
        }
        send(requireConnection(nodeId), frame);
        // CAP-71：INPUT 只记规模不记内容（用户输入隐私红线）
        auditCommand("INPUT", nodeId, sessionId, true, System.currentTimeMillis(),
                "len=" + (text == null ? 0 : text.length())
                        + ", images=" + (images == null ? 0 : images.size())
                        + ", files=" + (files == null ? 0 : files.size()));
    }

    @Override
    public void sendAuthorize(String nodeId, String sessionId, String requestId, boolean accepted, String scope) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "authorize");
        frame.put("sessionId", sessionId);
        frame.put("requestId", requestId);
        frame.put("accepted", accepted);
        frame.put("scope", scope);
        send(requireConnection(nodeId), frame);
        auditCommand("AUTHORIZE", nodeId, sessionId, true, System.currentTimeMillis(),
                "accepted=" + accepted + ", scope=" + scope);
    }

    @Override
    public void sendFinish(String nodeId, String sessionId) {
        sendCommand(nodeId, sessionId, "finish");
    }

    @Override
    public void sendKill(String nodeId, String sessionId) {
        sendCommand(nodeId, sessionId, "kill");
    }

    @Override
    public void sendSuspend(String nodeId, String sessionId) {
        sendCommand(nodeId, sessionId, "suspend");
    }

    /**
     * CAP-36：下发 exec 帧并阻塞至 exec_exit（镜像 launch 的「发帧 + 等 ack」模式）。
     * 协议门控：runner 低于 v3 直接 409 提示升级；等待上限 = 步骤超时 + 120s 余量
     * （runner 侧超时 kill 后仍会回 exec_exit，这里只兜 runner 无响应）。
     */
    @Override
    public AgentExecResult exec(String nodeId, AgentExecCommand cmd, java.util.function.Consumer<String> sink) {
        if (!supports(nodeId, AgentProtocol.EXEC_FRAMES)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（exec 需 v" + AgentProtocol.EXEC_FRAMES
                            + "+），请到节点页升级 runner");
        }
        requireBundleProtocol(nodeId, cmd.bundle());
        WebSocketSession ws = requireConnection(nodeId);
        CompletableFuture<AgentExecResult> done = new CompletableFuture<>();
        pendingExecs.put(cmd.execId(), new ExecWaiter(nodeId, sink, done));
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "exec");
        frame.put("execId", cmd.execId());
        frame.put("projectId", cmd.projectId());
        frame.put("workspaceId", cmd.workspaceId());
        frame.put("command", cmd.command());
        frame.put("workingDir", cmd.workingDir());
        frame.put("timeoutSec", cmd.timeoutSec());
        if (cmd.env() != null && !cmd.env().isEmpty()) {
            frame.put("env", cmd.env());
        }
        // token 仅随帧传输，严禁进日志（同 CAP-25 launch repo 块红线）
        if (cmd.repo() != null) {
            Map<String, Object> repo = new LinkedHashMap<>();
            repo.put("remoteUrl", cmd.repo().remoteUrl());
            repo.put("branch", cmd.repo().branch());
            repo.put("commit", cmd.repo().commit());
            repo.put("token", cmd.repo().token());
            frame.put("repo", repo);
        }
        // CAP-56：bundle 引用<b>必须在这里 put</b>——漏一行就是"帧看着发出去了、runner 却没拿到包"，
        // 这类新字段漏拼本仓库已经出过三次（AgentLaunchCommand 的 repos/manifest/workspaceKey），
        // 现象是任务跑起来但缺东西、日志里一点线索都没有。AgentConnectionRegistryExecTest 钉住它。
        if (cmd.bundle() != null) {
            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("kind", cmd.bundle().kind());
            bundle.put("id", cmd.bundle().id());
            frame.put("bundle", bundle);
        }
        putProxy(frame, nodeId);
        long startMs = System.currentTimeMillis();
        try {
            send(ws, frame);
            AgentExecResult r = done.get(cmd.timeoutSec() + 120, TimeUnit.SECONDS);
            auditCommand("EXEC", nodeId, cmd.execId(), r.ok(), startMs, execDetail(cmd.command(), r));
            return r;
        } catch (DevMindException e) {
            throw e;
        } catch (java.util.concurrent.TimeoutException e) {
            AgentExecResult r = new AgentExecResult(-1, true, "等待 runner exec_exit 超时（runner 无响应）");
            auditCommand("EXEC", nodeId, cmd.execId(), false, startMs, execDetail(cmd.command(), r));
            return r;
        } catch (Exception e) {
            auditCommand("EXEC", nodeId, cmd.execId(), false, startMs,
                    "cmd=" + cmd.command() + " 下发异常: " + e.getMessage());
            throw new DevMindException(ErrorCode.CONFLICT, "exec 下发异常: " + e.getMessage(), e);
        } finally {
            pendingExecs.remove(cmd.execId());
        }
    }

    // ---------------- CAP-73 验证链（verify_push/verify_pack/verify_patch 帧） ----------------

    /**
     * <b>红线</b>：{@link AgentVerifyCommand} 全部字段必须在此 put——漏一行 runner 侧就拿到
     * 空值，现象是"帧发出去了但 runner 缺东西"（本仓库 launch/exec 帧四次同类事故）。
     * AgentConnectionRegistryVerifyTest 钉住三类帧的完整组帧。
     */
    @Override
    public AgentVerifyResult verifyPush(String nodeId, AgentVerifyCommand.Push cmd,
                                        java.util.function.Consumer<String> sink) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "verify_push");
        frame.put("requestId", cmd.requestId());
        frame.put("runId", cmd.runId());
        frame.put("projectId", cmd.projectId());
        frame.put("workspaceOwner", cmd.workspaceOwner());
        frame.put("workspaceKey", cmd.workspaceKey());
        frame.put("remoteUrl", cmd.remoteUrl());
        frame.put("branch", cmd.branch());
        frame.put("token", cmd.token());
        frame.put("timeoutSec", cmd.timeoutSec());
        return verify(nodeId, frame, cmd.requestId(), cmd.timeoutSec(), sink,
                "VERIFY_PUSH", "branch=" + cmd.branch());
    }

    @Override
    public AgentVerifyResult verifyPack(String nodeId, AgentVerifyCommand.Pack cmd,
                                        java.util.function.Consumer<String> sink) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "verify_pack");
        frame.put("requestId", cmd.requestId());
        frame.put("runId", cmd.runId());
        frame.put("projectId", cmd.projectId());
        frame.put("workspaceKey", cmd.workspaceKey());
        frame.put("remoteUrl", cmd.remoteUrl());
        frame.put("branch", cmd.branch());
        frame.put("commitSha", cmd.commitSha());
        frame.put("token", cmd.token());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (AgentVerifyCommand.Pack.Step s : cmd.steps()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("name", s.name());
            step.put("command", s.command());
            step.put("workingDir", s.workingDir());
            steps.add(step);
        }
        frame.put("steps", steps);
        frame.put("outputDir", cmd.outputDir());
        if (cmd.env() != null && !cmd.env().isEmpty()) {
            frame.put("env", cmd.env());
        }
        frame.put("timeoutSec", cmd.timeoutSec());
        frame.put("bundleMaxBytes", cmd.bundleMaxBytes());
        return verify(nodeId, frame, cmd.requestId(), cmd.timeoutSec(), sink,
                "VERIFY_PACK", "commit=" + cmd.commitSha());
    }

    @Override
    public AgentVerifyResult verifyPatch(String nodeId, AgentVerifyCommand.Patch cmd,
                                         java.util.function.Consumer<String> sink) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "verify_patch");
        frame.put("requestId", cmd.requestId());
        frame.put("runId", cmd.runId());
        frame.put("patchDir", cmd.patchDir());
        frame.put("preHook", cmd.preHook());
        frame.put("postHook", cmd.postHook());
        frame.put("manifestKey", cmd.manifestKey());
        frame.put("timeoutSec", cmd.timeoutSec());
        return verify(nodeId, frame, cmd.requestId(), cmd.timeoutSec(), sink,
                "VERIFY_PATCH", "patchDir=" + cmd.patchDir());
    }

    /**
     * 验证指令公共骨架：协议门控（v22+，老 runner 静默忽略 verify_* 帧会空等超时）→
     * 发帧 → 阻塞等 verify_ack（上限 = 指令超时 + 120s 余量，只兜 runner 无响应；
     * runner 侧超时 kill 后仍回 verify_ack）。token 严禁进审计 detail（同 exec repo 块红线）。
     */
    private AgentVerifyResult verify(String nodeId, Map<String, Object> frame, String requestId,
                                     long timeoutSec, java.util.function.Consumer<String> sink,
                                     String auditEvent, String auditDetail) {
        if (!supports(nodeId, AgentProtocol.VERIFY_FRAMES)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "VERIFY_RUNNER_UPGRADE_REQUIRED: 节点 " + nodeId + " 的 runner 协议版本过低"
                            + "（验证链需 v" + AgentProtocol.VERIFY_FRAMES + "+），请到节点页升级 runner");
        }
        WebSocketSession ws = requireConnection(nodeId);
        CompletableFuture<AgentVerifyResult> done = new CompletableFuture<>();
        pendingVerifies.put(requestId, new VerifyWaiter(nodeId, sink, done));
        putProxy(frame, nodeId);
        long startMs = System.currentTimeMillis();
        try {
            send(ws, frame);
            AgentVerifyResult r = done.get(timeoutSec + 120, TimeUnit.SECONDS);
            auditCommand(auditEvent, nodeId, requestId, r.ok(), startMs,
                    auditDetail + (r.ok() ? "" : " | err=" + r.error()));
            return r;
        } catch (DevMindException e) {
            throw e;
        } catch (java.util.concurrent.TimeoutException e) {
            AgentVerifyResult r = AgentVerifyResult.failed("等待 runner verify_ack 超时（runner 无响应）");
            auditCommand(auditEvent, nodeId, requestId, false, startMs, auditDetail + " | ack 超时");
            return r;
        } catch (Exception e) {
            auditCommand(auditEvent, nodeId, requestId, false, startMs,
                    auditDetail + " | 下发异常: " + e.getMessage());
            throw new DevMindException(ErrorCode.CONFLICT, "验证指令下发异常: " + e.getMessage(), e);
        } finally {
            pendingVerifies.remove(requestId);
        }
    }

    /** exec 审计摘要：命令全文（service 截断兜底）+ 退出码/超时/error；命令本身在执行日志本就对操作者可见。 */
    private static String execDetail(String command, AgentExecResult r) {
        return "cmd=" + command + " | exit=" + r.exitCode()
                + (r.timedOut() ? " timedOut" : "")
                + (r.error() != null ? " err=" + r.error() : "");
    }

    /**
     * CAP-56：带执行包的 exec 需 v14+——老 runner 会忽略 bundle 块，然后"照常"去跑那条
     * 引用 {@code $DEVMIND_LAB_SCRIPT} 的命令：脚本与数据都没物化，失败信息指向脚本自身
     * （找不到文件），排查会从实验室一路查到节点上，而真正的原因只是版本不够。
     * 故这是「必须认识」的帧字段，在服务端直接拦下并指向升级。
     */
    private void requireBundleProtocol(String nodeId, AgentExecCommand.LabBundleRef bundle) {
        if (bundle == null) {
            return;
        }
        if (!supports(nodeId, AgentProtocol.EXEC_BUNDLE)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（决策实验室评测/微调需 v"
                            + AgentProtocol.EXEC_BUNDLE + "+，执行包要由 runner 拉取物化），"
                            + "请到节点页升级 runner");
        }
    }

    /**
     * CAP-57：下发 proc 帧并阻塞等 proc_ack（镜像 launch/exec 的「发帧 + 等 ack」模式）。
     * 协议门控：runner 低于 v15 会静默忽略 proc 帧，管控台空等超时，故直接 409 指向升级。
     * 等待上限 60s（start 含 800ms 观察窗、stop 等树杀死透 10s，60s 足够；包下载不走这里）。
     *
     * <p><b>红线</b>：{@link AgentProcCommand} 全部字段必须在此 put——漏一行 runner 侧就拿到
     * 空串/空表，现象是"实例起不来但帧看着发出去了"（本仓库 launch 帧三次同类事故）。
     * AgentConnectionRegistryProcTest 钉住完整组帧。</p>
     */
    @Override
    public AgentProcResult proc(String nodeId, AgentProcCommand cmd) {
        if (!supports(nodeId, AgentProtocol.PROC_FRAMES)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（分类服务进程管控需 v"
                            + AgentProtocol.PROC_FRAMES + "+），请到节点页升级 runner");
        }
        WebSocketSession ws = requireConnection(nodeId);
        CompletableFuture<AgentProcResult> done = new CompletableFuture<>();
        pendingProcs.put(cmd.requestId(), new ProcWaiter(nodeId, done));
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "proc");
        frame.put("requestId", cmd.requestId());
        frame.put("action", cmd.action());
        frame.put("instanceId", cmd.instanceId());
        frame.put("argv", cmd.argv() == null ? List.of() : cmd.argv());
        frame.put("command", cmd.command() == null ? "" : cmd.command());
        frame.put("env", cmd.env() == null ? Map.of() : cmd.env());
        frame.put("workdir", cmd.workdir() == null ? "" : cmd.workdir());
        frame.put("pidFile", cmd.pidFile() == null ? "" : cmd.pidFile());
        frame.put("logFile", cmd.logFile() == null ? "" : cmd.logFile());
        long startMs = System.currentTimeMillis();
        try {
            send(ws, frame);
            AgentProcResult r = done.get(60, TimeUnit.SECONDS);
            auditCommand("PROC", nodeId, cmd.instanceId(), r.ok(), startMs,
                    "action=" + cmd.action() + " status=" + r.status()
                            + (r.pid() != null ? " pid=" + r.pid() : "")
                            + (r.error() != null && !r.error().isEmpty() ? " err=" + r.error() : ""));
            return r;
        } catch (DevMindException e) {
            throw e;
        } catch (java.util.concurrent.TimeoutException e) {
            AgentProcResult r = AgentProcResult.fail(cmd.action(), AgentProcResult.UNKNOWN,
                    "等待 runner proc_ack 超时（runner 无响应）");
            auditCommand("PROC", nodeId, cmd.instanceId(), false, startMs,
                    "action=" + cmd.action() + " err=" + r.error());
            return r;
        } catch (Exception e) {
            auditCommand("PROC", nodeId, cmd.instanceId(), false, startMs,
                    "action=" + cmd.action() + " 下发异常: " + e.getMessage());
            throw new DevMindException(ErrorCode.CONFLICT, "proc 下发异常: " + e.getMessage(), e);
        } finally {
            pendingProcs.remove(cmd.requestId());
        }
    }

    /**
     * CAP-57：下发 pkg 帧并返回 future——安装包 GB 级下载以分钟计，调用方（classify 安装流）
     * 在虚拟线程上等 future，不阻塞 WS/REST 线程。协议门控同 proc：老 runner 静默忽略 pkg 帧。
     *
     * <p><b>红线</b>：{@link AgentPkgCommand} 全部字段必须在此 put，AgentConnectionRegistryPkgTest
     * 钉住完整组帧。无等待超时：断连清理解约，下载耗时由调用方自行兜底。</p>
     */
    @Override
    public CompletableFuture<AgentPkgResult> pkgInstallAsync(String nodeId, AgentPkgCommand cmd) {
        if (!supports(nodeId, AgentProtocol.PKG_FRAMES)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（分类服务安装包分发需 v"
                            + AgentProtocol.PKG_FRAMES + "+），请到节点页升级 runner");
        }
        WebSocketSession ws = requireConnection(nodeId);
        CompletableFuture<AgentPkgResult> done = new CompletableFuture<>();
        pendingPkgs.put(cmd.requestId(), new PkgWaiter(nodeId, done));
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "pkg");
        frame.put("requestId", cmd.requestId());
        frame.put("packageId", cmd.packageId());
        frame.put("sha256", cmd.sha256());
        frame.put("sizeBytes", cmd.sizeBytes());
        frame.put("fileName", cmd.fileName() == null ? "" : cmd.fileName());
        frame.put("installDir", cmd.installDir());
        long startMs = System.currentTimeMillis();
        // CAP-71：安装包分钟级下载，收口审计挂 future 完成回调（不阻塞下发线程）
        done.whenComplete((r, e) -> auditCommand("PKG", nodeId, cmd.fileName(),
                e == null && r != null && r.ok(), startMs,
                e != null ? "分发异常: " + e
                        : r.ok() ? "installDir=" + r.installDir() : "err=" + r.error()));
        try {
            send(ws, frame);
        } catch (Exception e) {
            pendingPkgs.remove(cmd.requestId());
            done.complete(AgentPkgResult.fail("pkg 下发异常: " + e.getMessage()));
        }
        return done;
    }

    /**
     * CAP-39：下发 collect_output 帧并阻塞等 output_collected ack（镜像 launch/exec 模式）。
     * 协议门控：runner 低于 v4 直接 409 提示升级；等待上限 75s（runner 上传 HTTP 超时 60s + 余量）。
     * runner 上传先于 ack 同步完成，调用方 ack 后回读 session_outputs 无竞态。
     */
    @Override
    public AgentCollectResult collectOutput(String nodeId, String sessionId) {
        if (!supports(nodeId, AgentProtocol.COLLECT_OUTPUT_FRAMES)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（产出即时回传需 v"
                            + AgentProtocol.COLLECT_OUTPUT_FRAMES + "+），请到节点页升级 runner");
        }
        WebSocketSession ws = requireConnection(nodeId);
        CompletableFuture<AgentCollectResult> done = new CompletableFuture<>();
        pendingCollects.put(sessionId, new CollectWaiter(nodeId, done));
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "collect_output");
        frame.put("sessionId", sessionId);
        long startMs = System.currentTimeMillis();
        try {
            send(ws, frame);
            AgentCollectResult r = done.get(75, TimeUnit.SECONDS);
            auditCommand("COLLECT", nodeId, sessionId, r.ok(), startMs, r.error());
            return r;
        } catch (DevMindException e) {
            throw e;
        } catch (Exception e) {
            auditCommand("COLLECT", nodeId, sessionId, false, startMs,
                    "等待产出回传确认超时/异常: " + e.getMessage());
            throw new DevMindException(ErrorCode.CONFLICT,
                    "等待产出回传确认超时/异常: " + e.getMessage(), e);
        } finally {
            pendingCollects.remove(sessionId);
        }
    }

    /**
     * CAP-42：下发 workspace_finalize 帧并阻塞等 workspace_finalize_ack（镜像 worklog_push 模式）。
     * 协议门控：runner 低于 v7 直接 409 提示升级；等待上限 60 + 310×库数 秒（逐库 fetch+merge+push
     * 各 300s 级 + 余量）。repos 数组含各库 token（仅随帧传输，严禁进日志，同 CAP-25 红线）。
     */
    @Override
    public FinalizeResult finalizeWorkspace(String nodeId, String sessionId, String projectId,
                                            String workspaceOwner,
                                            List<AgentLaunchCommand.RepoSpec> specs,
                                            boolean discardChanges, String workspaceKey) {
        return finalizeWorkspace(nodeId, sessionId, projectId, workspaceOwner, specs, discardChanges,
                workspaceKey, null);
    }

    /**
     * CAP-24 FR-06：带操作者 git 身份的收口——帧非空才带 gitAuthorName/gitAuthorEmail
     * （缺席 = runner 回退内置 devmind 署名；老 runner 忽略新字段，双向兼容不门控）。
     */
    @Override
    public FinalizeResult finalizeWorkspace(String nodeId, String sessionId, String projectId,
                                            String workspaceOwner,
                                            List<AgentLaunchCommand.RepoSpec> specs,
                                            boolean discardChanges, String workspaceKey,
                                            GitAuthor operator) {
        return finalizeWorkspace(nodeId, sessionId, projectId, workspaceOwner, specs, discardChanges,
                workspaceKey, operator, false);
    }

    /**
     * 收口删除远端会话分支（协议 v19）：deleteRemoteBranch=true 才 put 该字段
     * （缺席 = runner 维持 push 分支供 diff 的现状；老 runner 忽略字段，双向兼容不门控）。
     */
    @Override
    public FinalizeResult finalizeWorkspace(String nodeId, String sessionId, String projectId,
                                            String workspaceOwner,
                                            List<AgentLaunchCommand.RepoSpec> specs,
                                            boolean discardChanges, String workspaceKey,
                                            GitAuthor operator, boolean deleteRemoteBranch) {
        WebSocketSession ws = requireConnection(nodeId); // 先判在线再判版本，同 releaseWorkspace
        if (!supports(nodeId, AgentProtocol.PER_USER_WORKSPACE)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（工作区收口需 v"
                            + AgentProtocol.PER_USER_WORKSPACE + "+），请到节点页升级 runner");
        }
        // CAP-51：带 workspaceKey 时必须 v10+——老 runner 忽略该字段会去收口 work/ 旧布局
        requireKeyProtocol(nodeId, workspaceKey, "工作区收口");
        String requestId = "wf-" + System.currentTimeMillis() + "-" + sessionId;
        CompletableFuture<FinalizeResult> done = new CompletableFuture<>();
        pendingFinalizes.put(requestId, new FinalizeWaiter(nodeId, done));
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "workspace_finalize");
        frame.put("requestId", requestId);
        frame.put("sessionId", sessionId);
        frame.put("projectId", projectId);
        frame.put("workspaceOwner", workspaceOwner);
        frame.put("discardChanges", discardChanges);
        // CAP-51 红线：workspaceKey 必须在此 put（缺席 = runner 收口旧布局 work/）
        if (workspaceKey != null && !workspaceKey.isBlank()) {
            frame.put("workspaceKey", workspaceKey);
        }
        // CAP-24 FR-06 红线：操作者身份非空才 put（缺席 = runner 回退内置 devmind 署名）
        if (operator != null) {
            if (operator.name() != null && !operator.name().isBlank()) {
                frame.put("gitAuthorName", operator.name());
            }
            if (operator.email() != null && !operator.email().isBlank()) {
                frame.put("gitAuthorEmail", operator.email());
            }
        }
        // v19 红线：deleteRemoteBranch=true 才 put（缺席 = runner 维持 push 分支供 diff 的现状）
        if (deleteRemoteBranch) {
            frame.put("deleteRemoteBranch", true);
        }
        List<Map<String, Object>> repos = new ArrayList<>();
        for (AgentLaunchCommand.RepoSpec spec : specs) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("remoteUrl", spec.remoteUrl());
            r.put("baseBranch", spec.baseBranch());
            r.put("branch", spec.branch());
            r.put("token", spec.token()); // token 仅随帧传输，严禁进日志
            r.put("name", spec.name());
            repos.add(r);
        }
        frame.put("repos", repos);
        putProxy(frame, nodeId);
        long timeoutSec = 60L + 310L * Math.max(1, specs.size());
        long startMs = System.currentTimeMillis();
        try {
            send(ws, frame);
            FinalizeResult r = done.get(timeoutSec, TimeUnit.SECONDS);
            auditCommand("WS_FINALIZE", nodeId, sessionId, r.ok(), startMs,
                    "discard=" + discardChanges + (deleteRemoteBranch ? " deleteRemoteBranch" : "")
                            + (r.error() != null ? " err=" + r.error() : ""));
            return r;
        } catch (DevMindException e) {
            throw e;
        } catch (java.util.concurrent.TimeoutException e) {
            FinalizeResult r = FinalizeResult.failed("等待 runner workspace_finalize_ack 超时（runner 无响应）");
            auditCommand("WS_FINALIZE", nodeId, sessionId, false, startMs, "err=" + r.error());
            return r;
        } catch (Exception e) {
            auditCommand("WS_FINALIZE", nodeId, sessionId, false, startMs, "下发异常: " + e.getMessage());
            throw new DevMindException(ErrorCode.CONFLICT, "工作区收口下发异常: " + e.getMessage(), e);
        } finally {
            pendingFinalizes.remove(requestId);
        }
    }

    /**
     * CAP-42：下发 workspace_release 帧并阻塞等 workspace_release_ack（删除会话时释放固定工作区）。
     * 协议门控：runner 低于 v9 直接 409 提示升级——<b>不可静默跳过</b>：老 runner 会忽略该帧，
     * 目录留在磁盘成孤儿，该 (项目,用户) 之后所有会话 launch 都失败且无 UI 恢复入口。
     * 逐库只做本地目录/引用操作（不合并不 push），等待上限 60 + 120×库数 秒。
     */
    @Override
    public WorkspaceReleaseResult releaseWorkspace(String nodeId, String sessionId, String projectId,
                                                   String workspaceOwner,
                                                   List<AgentLaunchCommand.RepoSpec> specs,
                                                   String workspaceKey) {
        return releaseWorkspace(nodeId, sessionId, projectId, workspaceOwner, specs, workspaceKey, false);
    }

    /**
     * CAP-51 FR-06：{@code deleteRemoteBranch=true} 时帧携带该标志（协议 v13 可选字段，
     * <b>不门控</b>——老 runner 忽略字段 = 只释放本地不动远端，优雅降级），runner 本地释放
     * 成功后追加删除远端需求分支。
     */
    @Override
    public WorkspaceReleaseResult releaseWorkspace(String nodeId, String sessionId, String projectId,
                                                   String workspaceOwner,
                                                   List<AgentLaunchCommand.RepoSpec> specs,
                                                   String workspaceKey, boolean deleteRemoteBranch) {
        // 先判在线再判版本：断连会清掉 protocolVersions 记录（supports 按 v1 兜底），
        // 反过来就成了「节点明明离线却报协议版本过低、引导去升级 runner」——正是本事故里
        // 「报错指向错误对象」的翻版，必须让提示落在真实原因上。
        WebSocketSession ws = requireConnection(nodeId);
        if (!supports(nodeId, AgentProtocol.RELEASE_WORKSPACE)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（释放工作区需 v"
                            + AgentProtocol.RELEASE_WORKSPACE + "+），请到节点页升级 runner 后再删除会话"
                            + "（或到节点手工删除固定工作区目录）");
        }
        // CAP-51：带 workspaceKey 时必须 v10+（老 runner 会去释放 work/ 旧布局）
        requireKeyProtocol(nodeId, workspaceKey, "释放工作区");
        String requestId = "wr-" + System.currentTimeMillis() + "-" + sessionId;
        CompletableFuture<WorkspaceReleaseResult> done = new CompletableFuture<>();
        pendingReleases.put(requestId, new ReleaseWaiter(nodeId, done));
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "workspace_release");
        frame.put("requestId", requestId);
        frame.put("sessionId", sessionId);
        frame.put("projectId", projectId);
        frame.put("workspaceOwner", workspaceOwner);
        // CAP-51 红线：workspaceKey 必须在此 put（缺席 = runner 释放旧布局 work/）
        if (workspaceKey != null && !workspaceKey.isBlank()) {
            frame.put("workspaceKey", workspaceKey);
        }
        // CAP-51 FR-06：终态清理才置位（缺席/false = 不动远端）；可选字段不门控（v13），
        // 老 runner 忽略 = 只释放本地、远端分支留存（优雅降级）
        if (deleteRemoteBranch) {
            frame.put("deleteRemoteBranch", true);
        }
        List<Map<String, Object>> repos = new ArrayList<>();
        for (AgentLaunchCommand.RepoSpec spec : specs) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("remoteUrl", spec.remoteUrl());
            r.put("baseBranch", spec.baseBranch());
            r.put("branch", spec.branch());
            r.put("token", spec.token()); // token 仅随帧传输，严禁进日志
            r.put("name", spec.name());
            repos.add(r);
        }
        frame.put("repos", repos);
        long timeoutSec = 60L + 120L * Math.max(1, specs.size());
        long startMs = System.currentTimeMillis();
        try {
            send(ws, frame);
            WorkspaceReleaseResult r = done.get(timeoutSec, TimeUnit.SECONDS);
            auditCommand("WS_RELEASE", nodeId, sessionId, r.ok(), startMs,
                    (deleteRemoteBranch ? "deleteRemoteBranch " : "")
                            + (r.error() != null ? "err=" + r.error() : ""));
            return r;
        } catch (DevMindException e) {
            throw e;
        } catch (java.util.concurrent.TimeoutException e) {
            WorkspaceReleaseResult r = WorkspaceReleaseResult.failed("等待 runner workspace_release_ack 超时（runner 无响应）");
            auditCommand("WS_RELEASE", nodeId, sessionId, false, startMs, "err=" + r.error());
            return r;
        } catch (Exception e) {
            auditCommand("WS_RELEASE", nodeId, sessionId, false, startMs, "下发异常: " + e.getMessage());
            throw new DevMindException(ErrorCode.CONFLICT, "工作区释放下发异常: " + e.getMessage(), e);
        } finally {
            pendingReleases.remove(requestId);
        }
    }

    /**
     * CAP-54：下发 workspace_query 帧并阻塞等 workspace_query_ack（镜像 collect_output 模式）。
     * 协议门控：runner 低于 v11 直接 409 提示升级——不可静默发送，老 runner 忽略该帧会让
     * REST 端点空等 30s 超时。等待上限 30s（runner 侧 git/读盘均为秒级操作 + 余量）。
     */
    @Override
    public WorkspaceQueryResult workspaceQuery(String nodeId, String sessionId, String action,
                                               String repo, String path) {
        WebSocketSession ws = requireConnection(nodeId); // 先判在线再判版本，同 releaseWorkspace
        if (!supports(nodeId, AgentProtocol.WORKSPACE_VIEW)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（工作区视图需 v"
                            + AgentProtocol.WORKSPACE_VIEW + "+），请到节点页升级 runner");
        }
        String requestId = "wq-" + System.currentTimeMillis() + "-" + sessionId;
        CompletableFuture<WorkspaceQueryResult> done = new CompletableFuture<>();
        pendingWorkspaceQueries.put(requestId, new WorkspaceQueryWaiter(nodeId, done));
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "workspace_query");
        frame.put("requestId", requestId);
        frame.put("sessionId", sessionId);
        frame.put("action", action);
        if (repo != null && !repo.isBlank()) {
            frame.put("repo", repo);
        }
        if (path != null && !path.isBlank()) {
            frame.put("path", path);
        }
        long startMs = System.currentTimeMillis();
        try {
            send(ws, frame);
            WorkspaceQueryResult r = done.get(30, TimeUnit.SECONDS);
            auditCommand("WS_QUERY", nodeId, sessionId, r.ok(), startMs,
                    "action=" + action + (path != null && !path.isBlank() ? " path=" + path : "")
                            + (r.error() != null ? " err=" + r.error() : ""));
            return r;
        } catch (DevMindException e) {
            throw e;
        } catch (java.util.concurrent.TimeoutException e) {
            WorkspaceQueryResult r = WorkspaceQueryResult.failed("等待 runner workspace_query_ack 超时（runner 无响应）");
            auditCommand("WS_QUERY", nodeId, sessionId, false, startMs, "action=" + action + " err=" + r.error());
            return r;
        } catch (Exception e) {
            auditCommand("WS_QUERY", nodeId, sessionId, false, startMs,
                    "action=" + action + " 下发异常: " + e.getMessage());
            throw new DevMindException(ErrorCode.CONFLICT, "工作区查询下发异常: " + e.getMessage(), e);
        } finally {
            pendingWorkspaceQueries.remove(requestId);
        }
    }

    /**
     * CAP-58：下发 terminal_exec 帧并阻塞等 terminal_exec_ack（镜像 workspaceQuery 模式）。
     * 协议门控：runner 低于 v16 直接 409 提示升级——不可静默发送，老 runner 忽略该帧会让
     * REST 端点空等超时。等待上限 90s（runner 侧命令超时 60s + 余量）。
     * 红线：组帧每个字段都必须 put，配套 AgentConnectionRegistryTerminalExecTest 钉死。
     */
    @Override
    public TerminalExecResult terminalExec(String nodeId, String sessionId, String command, String cwd) {
        WebSocketSession ws = requireConnection(nodeId); // 先判在线再判版本，同 workspaceQuery
        if (!supports(nodeId, AgentProtocol.TERMINAL_EXEC)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（远程终端需 v"
                            + AgentProtocol.TERMINAL_EXEC + "+），请到节点页升级 runner");
        }
        String requestId = "tx-" + System.currentTimeMillis() + "-" + sessionId;
        CompletableFuture<TerminalExecResult> done = new CompletableFuture<>();
        pendingTerminalExecs.put(requestId, new TerminalExecWaiter(nodeId, done));
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "terminal_exec");
        frame.put("requestId", requestId);
        frame.put("sessionId", sessionId);
        frame.put("command", command);
        frame.put("cwd", cwd == null ? "" : cwd);
        long startMs = System.currentTimeMillis();
        try {
            send(ws, frame);
            TerminalExecResult r = done.get(90, TimeUnit.SECONDS);
            auditCommand("TERMINAL_EXEC", nodeId, sessionId, r.ok(), startMs,
                    "cmd=" + command + " | exit=" + r.exitCode()
                            + (r.timedOut() ? " timedOut" : "") + (r.cancelled() ? " cancelled" : "")
                            + (r.error() != null ? " err=" + r.error() : ""));
            return r;
        } catch (DevMindException e) {
            throw e;
        } catch (java.util.concurrent.TimeoutException e) {
            TerminalExecResult r = TerminalExecResult.failed("等待 runner terminal_exec_ack 超时（runner 无响应）");
            auditCommand("TERMINAL_EXEC", nodeId, sessionId, false, startMs,
                    "cmd=" + command + " | err=" + r.error());
            return r;
        } catch (Exception e) {
            auditCommand("TERMINAL_EXEC", nodeId, sessionId, false, startMs,
                    "cmd=" + command + " | 下发异常: " + e.getMessage());
            throw new DevMindException(ErrorCode.CONFLICT, "终端命令下发异常: " + e.getMessage(), e);
        } finally {
            pendingTerminalExecs.remove(requestId);
        }
    }

    /**
     * CAP-59：下发 terminal_complete 帧并阻塞等 terminal_complete_ack（镜像 terminalExec 模式）。
     * 协议门控：runner 低于 v17 直接 409 提示升级——老 runner 静默忽略该帧会让 REST 空等超时。
     * 等待上限 15s（runner 侧 compgen 5s 超时 + 余量）。
     */
    @Override
    public TerminalCompleteResult terminalComplete(String nodeId, String sessionId, String input, String cwd) {
        WebSocketSession ws = requireConnection(nodeId); // 先判在线再判版本，同 terminalExec
        if (!supports(nodeId, AgentProtocol.TERMINAL_SHELL)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（终端 Tab 补全需 v"
                            + AgentProtocol.TERMINAL_SHELL + "+），请到节点页升级 runner");
        }
        String requestId = "tc-" + System.currentTimeMillis() + "-" + sessionId;
        CompletableFuture<TerminalCompleteResult> done = new CompletableFuture<>();
        pendingTerminalCompletes.put(requestId, new TerminalCompleteWaiter(nodeId, done));
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "terminal_complete");
        frame.put("requestId", requestId);
        frame.put("sessionId", sessionId);
        frame.put("input", input == null ? "" : input);
        frame.put("cwd", cwd == null ? "" : cwd);
        long startMs = System.currentTimeMillis();
        try {
            send(ws, frame);
            TerminalCompleteResult r = done.get(15, TimeUnit.SECONDS);
            auditCommand("TERMINAL_COMPLETE", nodeId, sessionId, r.ok(), startMs,
                    "input=" + input + " candidates=" + r.candidates().size()
                            + (r.error() != null ? " err=" + r.error() : ""));
            return r;
        } catch (DevMindException e) {
            throw e;
        } catch (java.util.concurrent.TimeoutException e) {
            TerminalCompleteResult r = TerminalCompleteResult.failed("等待 runner terminal_complete_ack 超时（runner 无响应）");
            auditCommand("TERMINAL_COMPLETE", nodeId, sessionId, false, startMs,
                    "input=" + input + " err=" + r.error());
            return r;
        } catch (Exception e) {
            auditCommand("TERMINAL_COMPLETE", nodeId, sessionId, false, startMs,
                    "input=" + input + " 下发异常: " + e.getMessage());
            throw new DevMindException(ErrorCode.CONFLICT, "终端补全下发异常: " + e.getMessage(), e);
        } finally {
            pendingTerminalCompletes.remove(requestId);
        }
    }

    /**
     * CAP-59：下发 terminal_cancel 帧（fire-and-forget）——runner 整树杀该会话执行中的终端命令，
     * 进行中的 terminal_exec 以 cancelled=true 收口 ack。协议门控：老 runner 静默忽略会让
     * 用户以为取消了实际还在跑，故低于 v17 直接 409。
     */
    @Override
    public void terminalCancel(String nodeId, String sessionId) {
        WebSocketSession ws = requireConnection(nodeId); // 先判在线再判版本
        if (!supports(nodeId, AgentProtocol.TERMINAL_SHELL)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（终端命令取消需 v"
                            + AgentProtocol.TERMINAL_SHELL + "+），请到节点页升级 runner");
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "terminal_cancel");
        frame.put("sessionId", sessionId);
        send(ws, frame);
        auditCommand("TERMINAL_CANCEL", nodeId, sessionId, true, System.currentTimeMillis(), null);
    }

    /** CAP-65：write/read 文本上限（512KB，与 WS 缓冲同档）；upload/download 走 HTTP 中转（100MB）。 */
    static final long FILE_TEXT_CAP = 512L * 1024;
    static final long FILE_TRANSFER_CAP = 100L * 1024 * 1024;

    /**
     * CAP-65：下发 file 帧并阻塞等 file_ack（镜像 workspaceQuery 模式）。白名单权威在服务端 DB：
     * 组帧时从节点行读出 roots 全量随帧下发（runner 不做本地配置），并先做一道 root 命中与
     * 大小预检（超限直接 400/409 不发帧）；runner 侧还会再校验一道（防直连 WS 伪造帧）。
     * 协议门控：runner 低于 v18 直接 409 提示升级。等待上限：upload/download 300s（100MB 中转
     * + 余量），其余 30s。
     * 红线：组帧每个字段都必须 put，配套 AgentConnectionRegistryFileTest 钉死。
     */
    @Override
    public AgentFileResult file(String nodeId, AgentFileRequest req) {
        WebSocketSession ws = requireConnection(nodeId); // 先判在线再判版本，同 workspaceQuery
        if (!supports(nodeId, AgentProtocol.FILE_FRAMES)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 " + nodeId + " 的 runner 协议版本过低（文件浏览需 v"
                            + AgentProtocol.FILE_FRAMES + "+），请到节点页升级 runner");
        }
        if (req == null || req.op() == null || !AgentFileRequest.ALL_OPS.contains(req.op())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "未知文件操作: " + (req == null ? null : req.op()));
        }
        AgentNodeEntity node = nodeService.require(Long.parseLong(nodeId));
        List<String> roots = com.devmind.agent.service.AgentFileRoots.parse(node.getFileRoots());
        if (roots.isEmpty()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点未配置文件访问根目录白名单，请先在节点详情配置（留空 = 文件浏览不可用）");
        }
        if (!com.devmind.agent.service.AgentFileRoots.containsRoot(roots, req.root())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "root 不在节点文件访问白名单内: " + req.root());
        }
        // 大小服务端预检（runner 侧还会再校验一道）
        if (AgentFileRequest.OP_WRITE.equals(req.op()) && req.content() != null
                && req.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > FILE_TEXT_CAP) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "文本保存超限（>512KB），请改用上传");
        }
        if (AgentFileRequest.OP_UPLOAD.equals(req.op())
                && req.size() != null && req.size() > FILE_TRANSFER_CAP) {
            throw new DevMindException(ErrorCode.CONFLICT, "上传文件超限（>100MB）");
        }
        String requestId = "fa-" + System.currentTimeMillis() + "-" + req.op();
        CompletableFuture<AgentFileResult> done = new CompletableFuture<>();
        pendingFiles.put(requestId, new FileWaiter(nodeId, done));
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "file");
        frame.put("requestId", requestId);
        frame.put("op", req.op());
        frame.put("root", req.root() == null ? "" : req.root());
        frame.put("path", req.path() == null ? "" : req.path());
        frame.put("roots", roots); // DB 权威白名单全量下发（FR-01 信任模型）
        if (req.newName() != null) {
            frame.put("newName", req.newName());
        }
        if (req.content() != null) {
            frame.put("content", req.content());
        }
        if (req.recursive() != null) {
            frame.put("recursive", req.recursive());
        }
        if (req.transferId() != null) {
            frame.put("transferId", req.transferId());
        }
        if (req.size() != null) {
            frame.put("size", req.size());
        }
        if (req.sha256() != null) {
            frame.put("sha256", req.sha256());
        }
        boolean transfer = AgentFileRequest.OP_UPLOAD.equals(req.op())
                || AgentFileRequest.OP_DOWNLOAD.equals(req.op());
        long startMs = System.currentTimeMillis();
        try {
            send(ws, frame);
            AgentFileResult r = done.get(transfer ? 300 : 30, TimeUnit.SECONDS);
            auditCommand("FILE_OP", nodeId, req.path(), r.ok(), startMs,
                    "op=" + req.op() + " root=" + req.root()
                            + (r.error() != null ? " err=" + r.error() : ""));
            return r;
        } catch (DevMindException e) {
            throw e;
        } catch (java.util.concurrent.TimeoutException e) {
            AgentFileResult r = AgentFileResult.failed("等待 runner file_ack 超时（runner 无响应）");
            auditCommand("FILE_OP", nodeId, req.path(), false, startMs, "op=" + req.op() + " err=" + r.error());
            return r;
        } catch (Exception e) {
            auditCommand("FILE_OP", nodeId, req.path(), false, startMs,
                    "op=" + req.op() + " 下发异常: " + e.getMessage());
            throw new DevMindException(ErrorCode.CONFLICT, "文件操作下发异常: " + e.getMessage(), e);
        } finally {
            pendingFiles.remove(requestId);
        }
    }

    /** 非强制升级（force=false），等价于 {@link #sendUpgrade(Long, String, String, long, boolean)}。 */    public UpgradeAck sendUpgrade(Long nodeDbId, String version, String sha256, long sizeBytes) {
        return sendUpgrade(nodeDbId, version, sha256, sizeBytes, false);
    }

    /**
     * 下发 upgrade 指令并同步等 ack（CAP-21 FR-09，镜像 launch 模式；ack 超时覆盖 runner 侧
     * 下载+校验全程，故走独立的 upgradeAckTimeoutMs）。runner 忙碌时回 ok=false reason=busy。
     * force=true 时 runner 先终止全部活跃会话（正常 kill → exit 帧 → 收尾）再升级。
     */
    public UpgradeAck sendUpgrade(Long nodeDbId, String version, String sha256, long sizeBytes, boolean force) {
        String nodeId = String.valueOf(nodeDbId);
        WebSocketSession ws = requireConnection(nodeId);
        CompletableFuture<UpgradeAck> future = new CompletableFuture<>();
        if (pendingUpgrades.putIfAbsent(nodeId, future) != null) {
            throw new DevMindException(ErrorCode.CONFLICT, "该节点已有进行中的升级");
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "upgrade");
        frame.put("version", version);
        frame.put("sha256", sha256);
        frame.put("sizeBytes", sizeBytes);
        frame.put("force", force); // 旧 runner 忽略未知字段，仍按非 force 处理（回 busy）
        long startMs = System.currentTimeMillis();
        try {
            send(ws, frame);
            UpgradeAck ack = future.get(props.getUpgradeAckTimeoutMs(), TimeUnit.MILLISECONDS);
            auditCommand("UPGRADE", nodeId, "v" + version, ack.ok(), startMs,
                    (force ? "force " : "") + (ack.reason() != null ? "reason=" + ack.reason() : ""));
            return ack;
        } catch (Exception e) {
            auditCommand("UPGRADE", nodeId, "v" + version, false, startMs,
                    "等待升级确认超时/异常: " + e.getMessage());
            throw new DevMindException(ErrorCode.CONFLICT,
                    "等待升级确认超时/异常（在线旧版本 runner 不认识 upgrade 帧，需先手工部署基线版本）: "
                            + e.getMessage(), e);
        } finally {
            pendingUpgrades.remove(nodeId, future);
        }
    }

    /** 节点被删除/禁用时主动断开其连接。 */
    public void evict(Long nodeDbId) {
        WebSocketSession ws = connections.remove(String.valueOf(nodeDbId));
        if (ws != null) {
            closeQuietly(ws);
            auditLogService.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_CONNECTION, "EVICT")
                    .nodeId(nodeDbId).remoteAddr(AgentAuditLogService.formatRemoteAddr(ws.getRemoteAddress()))
                    .result(AgentAuditLogEntity.RESULT_OK).detail("节点删除/禁用，主动断开连接"));
        }
    }

    // ---------------- CAP-71 指令审计（COMMAND 分类；旁路落库，失败只降级 log.warn） ----------------

    /** 指令收口统一记录：result 取 OK/FAIL，durationMs 从 startMs 起算。 */
    private void auditCommand(String event, String nodeId, String target, boolean ok,
                              long startMs, String detail) {
        auditLogService.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_COMMAND, event)
                .nodeId(parseNodeId(nodeId))
                .target(target)
                .result(ok ? AgentAuditLogEntity.RESULT_OK : AgentAuditLogEntity.RESULT_FAIL)
                .durationMs(System.currentTimeMillis() - startMs)
                .detail(detail));
    }

    private static Long parseNodeId(String nodeId) {
        try {
            return Long.valueOf(nodeId);
        } catch (Exception e) {
            return null;
        }
    }

    private void sendCommand(String nodeId, String sessionId, String type) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("sessionId", sessionId);
        send(requireConnection(nodeId), frame);
        auditCommand(type.toUpperCase(java.util.Locale.ROOT), nodeId, sessionId, true,
                System.currentTimeMillis(), null);
    }

    private WebSocketSession requireConnection(String nodeId) {
        WebSocketSession ws = connections.get(nodeId);
        if (ws == null || !ws.isOpen()) {
            throw new DevMindException(ErrorCode.CONFLICT, "节点不在线: " + nodeId);
        }
        return ws;
    }

    private void send(WebSocketSession ws, Object frame) {
        try {
            synchronized (ws) {
                ws.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
            }
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.CONFLICT, "向节点发送指令失败: " + e.getMessage(), e);
        }
    }

    /** 同节点重复接入被踢的专用关闭码（4000-4999 私用段）：runner 凭此识别「本实例多余」走长退避。 */
    static final CloseStatus CLOSE_DUPLICATE = new CloseStatus(4000, "duplicate: kicked by newer connection");

    /** 重复接入踢旧连接：不用 NORMAL（1000），避免 runner 按普通断线 1s 重连形成双实例互踢风暴。 */
    private void kickDuplicate(WebSocketSession ws) {
        try {
            ws.close(CLOSE_DUPLICATE);
        } catch (Exception e) {
            // 忽略
        }
    }

    private void closeQuietly(WebSocketSession ws) {
        try {
            ws.close(CloseStatus.NORMAL);
        } catch (Exception e) {
            // 忽略
        }
    }

    // ---------------- 心跳超时巡检 ----------------

    /** 半开连接兜底：超过 heartbeatTimeoutMs 没收到任何帧即断开并判 OFFLINE。 */
    @Scheduled(fixedDelayString = "${devmind.agent.watchdog-ms:15000}",
            initialDelayString = "${devmind.agent.watchdog-ms:15000}")
    public void watchdog() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, WebSocketSession> e : connections.entrySet()) {
            Long seen = lastSeen.get(e.getKey());
            if (seen == null || now - seen <= props.getHeartbeatTimeoutMs()) {
                continue;
            }
            log.warn("agent 节点心跳超时，主动断开: nodeId={}", e.getKey());
            auditLogService.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_CONNECTION, "WATCHDOG_TIMEOUT")
                    .nodeId(parseNodeId(e.getKey()))
                    .remoteAddr(AgentAuditLogService.formatRemoteAddr(
                            e.getValue().getRemoteAddress()))
                    .result(AgentAuditLogEntity.RESULT_OK)
                    .detail("超过 " + props.getHeartbeatTimeoutMs() + "ms 未收到任何帧，主动断开"));
            closeQuietly(e.getValue()); // close 事件触发 onDisconnect 统一收尾
        }
    }
}
