package com.devmind.agent.ws;

import com.devmind.agent.model.AgentConnLogEntity;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentConnLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.agent.tunnel.AgentTunnelRegistry;
import com.devmind.common.egress.TunnelFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * CAP-70 FR-01：隧道接入端点 /ws/agent-tunnel?token=...（token 认证与控制通道同级，
 * /ws/** 在安全链 permitAll，handler 内校验）。tunnel_hello 为文本 JSON 帧（仅服务端下行），
 * 之后纯二进制流帧（{@link TunnelFrame}）。
 */
@Component
public class AgentTunnelWsHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentTunnelWsHandler.class);
    private static final String ATTR_NODE = "agentNode";

    private final AgentNodeService nodeService;
    private final AgentTunnelRegistry registry;
    private final AgentConnLogService connLogService;

    public AgentTunnelWsHandler(AgentNodeService nodeService, AgentTunnelRegistry registry,
                                AgentConnLogService connLogService) {
        this.nodeService = nodeService;
        this.registry = registry;
        this.connLogService = connLogService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = UriComponentsBuilder.fromUri(session.getUri()).build()
                .getQueryParams().getFirst("token");
        var nodeOpt = nodeService.resolveByToken(token);
        if (nodeOpt.isEmpty()) {
            String remote = AgentConnLogService.formatRemoteAddr(session.getRemoteAddress());
            log.warn("runner 隧道接入被拒绝（token 无效或节点已禁用）: remote={}", remote);
            connLogService.record(AgentConnLogEntity.EVENT_REJECT, null, remote, "隧道 token 无效或节点已禁用");
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        AgentNodeEntity node = nodeOpt.get();
        session.getAttributes().put(ATTR_NODE, node);
        registry.onConnect(node, session);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        AgentNodeEntity node = (AgentNodeEntity) session.getAttributes().get(ATTR_NODE);
        if (node == null) {
            return;
        }
        byte[] bytes = new byte[message.getPayload().remaining()];
        message.getPayload().get(bytes);
        TunnelFrame frame;
        try {
            frame = TunnelFrame.decode(bytes);
        } catch (Exception e) {
            log.warn("节点 {} 隧道坏帧（{}），断开隧道自保", node.getId(), e.getMessage());
            try {
                session.close(CloseStatus.PROTOCOL_ERROR);
            } catch (Exception ignored) {
            }
            return;
        }
        registry.onFrame(node.getId(), session, frame);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AgentNodeEntity node = (AgentNodeEntity) session.getAttributes().get(ATTR_NODE);
        if (node != null) {
            registry.onDisconnect(node.getId(), session);
        }
    }
}
