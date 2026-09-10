package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.model.AgentConnLogEntity;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentEventFrame;
import com.devmind.common.agent.AgentEventListener;
import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.agent.AgentExecResult;
import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.AgentProtocol;
import com.devmind.common.agent.InputImage;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
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

    private final AgentNodeService nodeService;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final ObjectProvider<AgentEventListener> listenerProvider;
    private final AgentConnLogService connLogService;

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

    public AgentConnectionRegistry(AgentNodeService nodeService, AgentProperties props,
                                   ObjectMapper mapper, ObjectProvider<AgentEventListener> listenerProvider,
                                   AgentConnLogService connLogService) {
        this.nodeService = nodeService;
        this.props = props;
        this.mapper = mapper;
        this.listenerProvider = listenerProvider;
        this.connLogService = connLogService;
    }

    // ---------------- 连接生命周期（AgentNodeWsHandler 回调） ----------------

    public void onConnect(AgentNodeEntity node, WebSocketSession ws) {
        String nodeId = String.valueOf(node.getId());
        WebSocketSession old = connections.put(nodeId, ws);
        if (old != null && old.isOpen()) {
            kickDuplicate(old); // 同节点重复接入：踢掉旧连接（专用关闭码 4000，runner 侧走长退避防互踢风暴）
        }
        lastSeen.put(nodeId, System.currentTimeMillis());
        String remoteAddr = AgentConnLogService.formatRemoteAddr(ws.getRemoteAddress());
        nodeService.markOnline(node.getId(), remoteAddr);
        connLogService.record(AgentConnLogEntity.EVENT_CONNECT, node, remoteAddr, null);
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
        connLogService.record(AgentConnLogEntity.EVENT_DISCONNECT, node,
                AgentConnLogService.formatRemoteAddr(ws.getRemoteAddress()), null);
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
        try {
            send(ws, frame);
            LaunchAck ack = future.get(props.getLaunchAckTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!ack.ok()) {
                throw new DevMindException(ErrorCode.INTERNAL,
                        "runner 拉起会话失败: " + (ack.error() == null ? "未知原因" : ack.error()));
            }
        } catch (DevMindException e) {
            pendingLaunches.remove(cmd.sessionId());
            throw e;
        } catch (Exception e) {
            pendingLaunches.remove(cmd.sessionId());
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
        send(requireConnection(nodeId), frame);
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
        try {
            send(ws, frame);
            return done.get(cmd.timeoutSec() + 120, TimeUnit.SECONDS);
        } catch (DevMindException e) {
            throw e;
        } catch (java.util.concurrent.TimeoutException e) {
            return new AgentExecResult(-1, true, "等待 runner exec_exit 超时（runner 无响应）");
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.CONFLICT, "exec 下发异常: " + e.getMessage(), e);
        } finally {
            pendingExecs.remove(cmd.execId());
        }
    }

    /** 非强制升级（force=false），等价于 {@link #sendUpgrade(Long, String, String, long, boolean)}。 */
    public UpgradeAck sendUpgrade(Long nodeDbId, String version, String sha256, long sizeBytes) {
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
        try {
            send(ws, frame);
            return future.get(props.getUpgradeAckTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
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
        }
    }

    private void sendCommand(String nodeId, String sessionId, String type) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("sessionId", sessionId);
        send(requireConnection(nodeId), frame);
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
            closeQuietly(e.getValue()); // close 事件触发 onDisconnect 统一收尾
        }
    }
}
