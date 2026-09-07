package com.devmind.chat.controller;

import com.devmind.chat.service.ChatManagerService;
import com.devmind.common.agent.SessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CAP-30 问答实时流：WS /ws/chats/{id}，帧协议与 /ws/sessions/{id} 完全一致
 * （snapshot/event/error/pong 下行 + input/authorize/ping 上行），前端共享同一套流组件。
 */
@Component
public class ChatWsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWsHandler.class);
    private static final String ATTR_CONSUMER = "consumer";

    private final ChatManagerService service;
    private final ObjectMapper mapper;

    public ChatWsHandler(ChatManagerService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String id = extractChatId(session);
        if (id == null) {
            send(session, Map.of("type", "error", "message", "URL 缺少问答 ID"));
            closeQuietly(session);
            return;
        }
        try {
            Consumer<SessionEvent> consumer = ev -> send(session,
                    Map.of("type", "event", "seq", ev.seq(), "event", ev));
            List<SessionEvent> replay = service.subscribe(id, consumer);
            session.getAttributes().put(ATTR_CONSUMER, consumer);
            send(session, snapshot(id, replay));
        } catch (Exception e) {
            log.warn("订阅问答失败: {} err={}", id, e.getMessage());
            send(session, Map.of("type", "error", "message", "问答不可用: " + e.getMessage()));
            closeQuietly(session);
        }
    }

    private Map<String, Object> snapshot(String id, List<SessionEvent> replay) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "snapshot");
        m.put("sessionId", id);
        m.put("seq", replay.isEmpty() ? 0 : replay.get(replay.size() - 1).seq());
        m.put("events", replay);
        return m;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = mapper.readTree(message.getPayload());
        String type = node.path("type").asText("");
        String id = extractChatId(session);
        if (id == null) {
            return;
        }
        switch (type) {
            case "input" -> service.input(id, node.path("text").asText(""));
            case "authorize" -> service.authorize(id,
                    node.path("accepted").asBoolean(false),
                    node.path("scope").asText("once"),
                    node.path("requestId").asText(""));
            case "ping" -> send(session, Map.of("type", "pong"));
            default -> { }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String id = extractChatId(session);
        Object consumer = session.getAttributes().remove(ATTR_CONSUMER);
        if (id != null && consumer instanceof Consumer<?> c) {
            @SuppressWarnings("unchecked")
            Consumer<SessionEvent> cast = (Consumer<SessionEvent>) c;
            service.unsubscribe(id, cast);
        }
    }

    private String extractChatId(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        int idx = path.lastIndexOf('/');
        return idx >= 0 && idx < path.length() - 1 ? path.substring(idx + 1) : null;
    }

    private void send(WebSocketSession session, Object payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
                }
            }
        } catch (Exception e) {
            log.debug("WS 发送失败(可能已关闭): {}", e.getMessage());
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception e) {
            // 忽略
        }
    }
}
