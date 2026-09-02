package com.devmind.agent.ws;

import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.registry.AgentConnectionRegistry;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.AgentEventFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * runner 接入端点：WS /ws/agent?token=...（/ws/** 在安全链 permitAll，节点用注册 token 自证）。
 *
 * <p>上行帧：hello{os,capabilities,version,activeSessions[]} / heartbeat /
 * event{sessionId,type,content,source,timestamp,payload} / exit{sessionId,code} /
 * launched{sessionId,ok,error}（launch ack）。</p>
 */
@Component
public class AgentNodeWsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentNodeWsHandler.class);
    private static final String ATTR_NODE = "agentNode";

    private final AgentNodeService nodeService;
    private final AgentConnectionRegistry registry;
    private final ObjectMapper mapper;

    public AgentNodeWsHandler(AgentNodeService nodeService, AgentConnectionRegistry registry,
                              ObjectMapper mapper) {
        this.nodeService = nodeService;
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = UriComponentsBuilder.fromUri(session.getUri()).build()
                .getQueryParams().getFirst("token");
        var nodeOpt = nodeService.resolveByToken(token);
        if (nodeOpt.isEmpty()) {
            log.warn("runner 接入被拒绝（token 无效或节点已禁用）: remote={}", session.getRemoteAddress());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        AgentNodeEntity node = nodeOpt.get();
        session.getAttributes().put(ATTR_NODE, node);
        registry.onConnect(node, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        AgentNodeEntity node = (AgentNodeEntity) session.getAttributes().get(ATTR_NODE);
        if (node == null) {
            return;
        }
        JsonNode frame;
        try {
            frame = mapper.readTree(message.getPayload());
        } catch (Exception e) {
            log.warn("runner 帧解析失败: node={} err={}", node.getId(), e.getMessage());
            return;
        }
        String type = frame.path("type").asText("");
        switch (type) {
            case "hello" -> {
                List<String> active = new ArrayList<>();
                JsonNode arr = frame.path("activeSessions");
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        active.add(n.asText(""));
                    }
                }
                registry.onHello(node, frame.path("os").asText(null),
                        frame.path("capabilities").asText(null),
                        frame.path("version").asText(null), active);
            }
            case "heartbeat" -> {
                registry.touch(String.valueOf(node.getId()));
                nodeService.touchHeartbeat(node.getId());
            }
            case "event" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = frame.has("payload")
                        ? mapper.convertValue(frame.path("payload"), Map.class) : Map.of();
                registry.onEvent(node, new AgentEventFrame(
                        frame.path("sessionId").asText(""),
                        frame.path("eventType").asText(""),
                        frame.path("content").asText(""),
                        frame.path("source").asText("stdout"),
                        frame.path("timestamp").asLong(System.currentTimeMillis()),
                        payload));
            }
            case "exit" -> registry.onExit(node, frame.path("sessionId").asText(""),
                    frame.path("code").asInt(-1));
            case "launched" -> registry.onLaunchAck(frame.path("sessionId").asText(""),
                    frame.path("ok").asBoolean(false), frame.path("error").asText(null));
            default -> log.debug("未知 runner 帧类型: {}", type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AgentNodeEntity node = (AgentNodeEntity) session.getAttributes().get(ATTR_NODE);
        if (node != null) {
            registry.onDisconnect(node, session);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.debug("runner 连接传输异常: err={}", exception.getMessage());
        session.close(CloseStatus.SERVER_ERROR);
    }
}
