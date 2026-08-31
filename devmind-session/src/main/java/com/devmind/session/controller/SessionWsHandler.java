package com.devmind.session.controller;

import com.devmind.session.model.SessionEvent;
import com.devmind.session.service.SessionManagerService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 会话实时流：WS /ws/sessions/{id}。
 * <ul>
 *   <li>连接后先发 snapshot（当前状态 + 环形缓冲回放），再推增量事件（客户端按 seq 去重）；</li>
 *   <li>客户端→服务端：input / authorize / ping。</li>
 * </ul>
 * 事件形如 {@code {"type":"event","event":{...}}}；snapshot 形如 {@code {"type":"snapshot","state":"...","seq":N,"events":[...]}}。
 */
@Component
public class SessionWsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionWsHandler.class);
    private static final String ATTR_CONSUMER = "consumer";

    private final SessionManagerService service;
    private final ObjectMapper mapper;

    public SessionWsHandler(SessionManagerService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String id = extractSessionId(session);
        if (id == null) {
            send(session, Map.of("type", "error", "message", "URL 缺少会话 ID"));
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
            log.warn("订阅会话失败: {} err={}", id, e.getMessage());
            send(session, Map.of("type", "error", "message", "会话不可用: " + e.getMessage()));
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
        String id = extractSessionId(session);
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
        String id = extractSessionId(session);
        Object consumer = session.getAttributes().remove(ATTR_CONSUMER);
        if (id != null && consumer instanceof Consumer<?> c) {
            @SuppressWarnings("unchecked")
            Consumer<SessionEvent> cast = (Consumer<SessionEvent>) c;
            service.unsubscribe(id, cast);
        }
    }

    private String extractSessionId(WebSocketSession session) {
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
