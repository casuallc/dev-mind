package com.devmind.agent.registry;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentEventFrame;
import com.devmind.common.agent.AgentEventListener;
import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.AgentNodeConnector;
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

    private final AgentNodeService nodeService;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final ObjectProvider<AgentEventListener> listenerProvider;

    /** nodeId(字符串) → runner WS 连接 */
    private final Map<String, WebSocketSession> connections = new ConcurrentHashMap<>();
    /** nodeId → 最近一次收到任何帧的时间（心跳超时判 OFFLINE） */
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    /** sessionId → launch ack 等待者 */
    private final Map<String, CompletableFuture<LaunchAck>> pendingLaunches = new ConcurrentHashMap<>();
    /** nodeId → upgrade ack 等待者（同节点同时只允许一个升级） */
    private final Map<String, CompletableFuture<UpgradeAck>> pendingUpgrades = new ConcurrentHashMap<>();

    public AgentConnectionRegistry(AgentNodeService nodeService, AgentProperties props,
                                   ObjectMapper mapper, ObjectProvider<AgentEventListener> listenerProvider) {
        this.nodeService = nodeService;
        this.props = props;
        this.mapper = mapper;
        this.listenerProvider = listenerProvider;
    }

    // ---------------- 连接生命周期（AgentNodeWsHandler 回调） ----------------

    public void onConnect(AgentNodeEntity node, WebSocketSession ws) {
        String nodeId = String.valueOf(node.getId());
        WebSocketSession old = connections.put(nodeId, ws);
        if (old != null && old.isOpen()) {
            closeQuietly(old); // 同节点重复接入：踢掉旧连接
        }
        lastSeen.put(nodeId, System.currentTimeMillis());
        nodeService.markOnline(node.getId());
        log.info("agent 节点上线: id={} name={}", nodeId, node.getName());
    }

    public void onDisconnect(AgentNodeEntity node, WebSocketSession ws) {
        String nodeId = String.valueOf(node.getId());
        // 只清自己这条连接（防旧连接关闭事件误清新连接）
        if (!connections.remove(nodeId, ws)) {
            return;
        }
        lastSeen.remove(nodeId);
        nodeService.markOffline(node.getId());
        log.info("agent 节点离线: id={} name={}", nodeId, node.getName());
        // 断线即失败进行中的升级等待（升级中的 runner 断连属预期：换包重启）
        CompletableFuture<UpgradeAck> pending = pendingUpgrades.remove(nodeId);
        if (pending != null) {
            pending.complete(new UpgradeAck(false, "disconnect", 0));
        }
        AgentEventListener listener = listenerProvider.getIfAvailable();
        if (listener != null) {
            listener.onAgentDisconnected(nodeId);
        }
    }

    // ---------------- 上行帧处理 ----------------

    public void onHello(AgentNodeEntity node, String os, String capabilities, String version,
                        List<String> activeSessionIds) {
        String nodeId = String.valueOf(node.getId());
        touch(nodeId);
        nodeService.updateMeta(node.getId(), os, capabilities, version);
        AgentEventListener listener = listenerProvider.getIfAvailable();
        if (listener != null) {
            listener.onAgentHello(nodeId, activeSessionIds);
        }
    }

    public void onEvent(AgentNodeEntity node, AgentEventFrame frame) {
        touch(String.valueOf(node.getId()));
        AgentEventListener listener = listenerProvider.getIfAvailable();
        if (listener != null) {
            listener.onAgentEvent(String.valueOf(node.getId()), frame);
        }
    }

    public void onExit(AgentNodeEntity node, String sessionId, int exitCode) {
        touch(String.valueOf(node.getId()));
        AgentEventListener listener = listenerProvider.getIfAvailable();
        if (listener != null) {
            listener.onAgentExit(String.valueOf(node.getId()), sessionId, exitCode);
        }
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
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "input");
        frame.put("sessionId", sessionId);
        frame.put("text", text);
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
     * 下发 upgrade 指令并同步等 ack（CAP-21 FR-09，镜像 launch 模式；ack 超时覆盖 runner 侧
     * 下载+校验全程，故走独立的 upgradeAckTimeoutMs）。runner 忙碌时回 ok=false reason=busy。
     */
    public UpgradeAck sendUpgrade(Long nodeDbId, String version, String sha256, long sizeBytes) {
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
