package com.devmind.agent.tunnel;

import com.devmind.agent.config.EgressProperties;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.EgressRuleService;
import com.devmind.agent.service.EgressTunnelStatus;
import com.devmind.common.agent.AgentProtocol;
import com.devmind.common.egress.TunnelFrame;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CAP-70 FR-01/FR-04：隧道连接注册表（/ws/agent-tunnel）。一节点一条隧道（新连接顶掉旧连接）；
 * 连接建立即下发 tunnel_hello（protocolVersion + allowedHosts 全量快照），规则变更重推（FR-04）。
 *
 * <p>流复用：streamId 由服务端分配（隧道只有服务端一侧发起 OPEN），OPEN_ACK waiter 模式同
 * AgentConnectionRegistry 的 launch/file 帧先例；隧道断连 = 全部流 abort（socket 即断，
 * git/HTTP 客户端快速失败，FR-07）。</p>
 */
@Component
public class AgentTunnelRegistry implements EgressTunnelStatus {

    private static final Logger log = LoggerFactory.getLogger(AgentTunnelRegistry.class);

    /** 一条隧道连接的运行态 */
    public record TunnelConn(long nodeId, WebSocketSession session, Object sendLock,
                             Map<Integer, ServerTunnelStream> streams, AtomicInteger nextStreamId,
                             AgentTunnelRegistry owner) {

        /** 同步发送（WebSocketSession 并发 sendMessage 不安全——同 AgentConnectionRegistry send 纪律） */
        public void send(TunnelFrame frame) throws IOException {
            byte[] bytes = frame.encode();
            synchronized (sendLock) {
                session.sendMessage(new BinaryMessage(bytes));
            }
        }

        /** 尽力发送（abort 路径，失败不 cascaded） */
        public void sendQuietly(TunnelFrame frame) {
            try {
                send(frame);
            } catch (Exception e) {
                log.debug("隧道帧发送失败（流 {} 类型 {}）: {}", frame.streamId(), frame.type(), e.toString());
            }
        }
    }

    private final Map<Long, TunnelConn> tunnels = new ConcurrentHashMap<>();
    private final EgressRuleService ruleService;
    private final EgressProperties props;
    private final ObjectMapper mapper;

    public AgentTunnelRegistry(EgressRuleService ruleService, EgressProperties props, ObjectMapper mapper) {
        this.ruleService = ruleService;
        this.props = props;
        this.mapper = mapper;
    }

    /** 隧道连接建立：登记 + 下发 tunnel_hello（一节点一条，新顶旧） */
    public void onConnect(AgentNodeEntity node, WebSocketSession session) {
        long nodeId = node.getId();
        TunnelConn conn = new TunnelConn(nodeId, session, new Object(),
                new ConcurrentHashMap<>(), new AtomicInteger(), this);
        TunnelConn old = tunnels.put(nodeId, conn);
        if (old != null) {
            log.info("节点 {} 隧道重连，顶掉旧连接（旧连接 {} 条流全部中止）", nodeId, old.streams().size());
            abortAll(old, "隧道重连");
            try {
                old.session().close();
            } catch (IOException ignored) {
            }
        }
        pushSnapshot(conn);
        log.info("节点 {}（{}）隧道已建立", nodeId, node.getName());
    }

    /** 隧道断开：该连接全部流中止（SOCKS 侧 socket 断开，客户端快速失败） */
    public void onDisconnect(long nodeId, WebSocketSession session) {
        TunnelConn conn = tunnels.get(nodeId);
        if (conn == null || conn.session() != session) {
            return; // 旧连接的迟到断连事件（已被新连接顶替）
        }
        tunnels.remove(nodeId, conn);
        abortAll(conn, "隧道断开");
        log.info("节点 {} 隧道断开", nodeId);
    }

    @Override
    public boolean tunnelOnline(Long nodeId) {
        TunnelConn c = nodeId == null ? null : tunnels.get(nodeId);
        return c != null && c.session().isOpen();
    }

    /** 规则变更 → 全部在线隧道重推 allowedHosts 快照（FR-04，全量快照量小无需增量） */
    @EventListener
    public void onRulesChanged(EgressRuleService.EgressRulesChangedEvent event) {
        tunnels.values().forEach(this::pushSnapshot);
    }

    /** 下发 tunnel_hello（文本 JSON 帧：{type, protocolVersion, allowedHosts[]}） */
    public void pushSnapshot(TunnelConn conn) {
        List<String> allowed = ruleService.allowedHostsFor(conn.nodeId());
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "type", "tunnel_hello",
                    "protocolVersion", AgentProtocol.EGRESS_TUNNEL,
                    "allowedHosts", allowed));
            synchronized (conn.sendLock()) {
                conn.session().sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.warn("节点 {} 隧道快照下发失败: {}", conn.nodeId(), e.toString());
        }
    }

    /**
     * 开一条流：OPEN → 等 OPEN_ACK（超时/被拒/断连抛 DevMindException，fail-visible）。
     * 成功返回未 start 的流——由调用方回复 REP_OK 后再 {@link ServerTunnelStream#start()}。
     */
    public ServerTunnelStream openStream(long nodeId, String host, int port, Socket socket) {
        TunnelConn conn = tunnels.get(nodeId);
        if (conn == null || !conn.session().isOpen()) {
            throw new DevMindException(ErrorCode.CONFLICT, "出口节点 " + nodeId + " 的隧道未连接");
        }
        int streamId = conn.nextStreamId().incrementAndGet();
        ServerTunnelStream stream = new ServerTunnelStream(streamId, conn, socket, props.getWindowBytes());
        conn.streams().put(streamId, stream);
        try {
            conn.send(TunnelFrame.open(streamId, host, port));
        } catch (IOException e) {
            conn.streams().remove(streamId);
            throw new DevMindException(ErrorCode.CONFLICT, "隧道 OPEN 发送失败: " + e.getMessage());
        }
        String error;
        try {
            error = stream.openFuture().get(props.getOpenTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            conn.streams().remove(streamId);
            conn.sendQuietly(TunnelFrame.rst(streamId, "OPEN_ACK 超时"));
            // 健康 runner 对 OPEN 必在拨号超时（10s）内回 OPEN_ACK/RST；30s 全静默 = 隧道已
            // 静默断链（NAT/云网关丢空闲 TCP 无 RST，WS 层感知不到）。摘掉隧道让后续请求
            // 快速失败（FR-07）而非每单各挂 30s；runner 侧保活探到静默会自行重连顶回。
            dropSilent(conn, "OPEN_ACK 超时");
            throw new DevMindException(ErrorCode.CONFLICT,
                    "隧道 OPEN 超时（" + props.getOpenTimeoutMs() + "ms 未收到 OPEN_ACK）");
        } catch (Exception e) {
            conn.streams().remove(streamId);
            throw new DevMindException(ErrorCode.CONFLICT, "隧道 OPEN 中断: " + e.getMessage());
        }
        if (error != null) {
            conn.streams().remove(streamId);
            throw new DevMindException(ErrorCode.CONFLICT, "隧道 OPEN 被 runner 拒绝: " + error);
        }
        return stream; // 不 start：由调用方（Socks5Server 回复 REP_OK 后）启动搬运
    }

    /** 二进制流帧分发（WS handler 调用；坏帧 = RST 该流或忽略） */
    public void onFrame(long nodeId, WebSocketSession session, TunnelFrame frame) {
        TunnelConn conn = tunnels.get(nodeId);
        if (conn == null || conn.session() != session) {
            return;
        }
        ServerTunnelStream stream = conn.streams().get(frame.streamId());
        switch (frame.type()) {
            case TunnelFrame.TYPE_OPEN_ACK -> {
                if (stream != null) {
                    stream.onOpenAck(frame.isOk() ? null
                            : (frame.error() == null ? "OPEN 被拒" : frame.error()));
                }
            }
            case TunnelFrame.TYPE_DATA -> {
                if (stream != null) {
                    stream.onData(frame.payload());
                }
            }
            case TunnelFrame.TYPE_CLOSE -> {
                if (stream != null) {
                    stream.onClose();
                }
            }
            case TunnelFrame.TYPE_RST -> {
                if (stream != null) {
                    log.debug("隧道流 {} 被 runner RST: {}", frame.streamId(), frame.error());
                    stream.abort(frame.error());
                }
            }
            case TunnelFrame.TYPE_WINDOW -> {
                if (stream != null) {
                    stream.onWindow(frame.windowBytes());
                }
            }
            default -> {
                // OPEN 只应由服务端发起；runner 发来即伪造帧（FR-04 对称面），断隧道自保
                log.warn("节点 {} 隧道收到非法帧类型 {}，断开隧道", nodeId, frame.type());
                try {
                    session.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 静默断链判定后的隧道摘除：从注册表移除 + 全流中止 + 关会话（幂等，只摘当前登记的那条） */
    private void dropSilent(TunnelConn conn, String reason) {
        if (tunnels.remove(conn.nodeId(), conn)) {
            log.warn("节点 {} 隧道判定静默断链（{}），摘除等 runner 重连", conn.nodeId(), reason);
            abortAll(conn, reason);
            try {
                conn.session().close();
            } catch (IOException ignored) {
            }
        }
    }

    private void abortAll(TunnelConn conn, String reason) {
        conn.streams().values().forEach(s -> s.abort(reason));
    }
}
