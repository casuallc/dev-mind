package com.devmind.notification.controller;

import com.devmind.notification.channel.NotificationWsPush;
import com.devmind.notification.dto.NotificationView;
import com.devmind.notification.dto.NotificationViews;
import com.devmind.notification.model.NotificationEntity;
import com.devmind.notification.repo.NotificationRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * 通知实时流：WS /ws/notifications/stream（FR-06 实时推送）。
 * <ul>
 *   <li>连接后先推 snapshot（最近 30 条未读），再推增量 {@code {"type":"notification","notification":{...}}}；</li>
 *   <li>客户端→服务端：ping → pong。</li>
 * </ul>
 */
@Component
public class NotificationWsHandler extends TextWebSocketHandler implements NotificationWsPush {

    private static final Logger log = LoggerFactory.getLogger(NotificationWsHandler.class);

    private final NotificationRepository repo;
    private final ObjectMapper mapper;
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public NotificationWsHandler(NotificationRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        List<NotificationView> recent = new ArrayList<>();
        for (NotificationEntity e : repo.findByReadAtIsNullOrderByCreatedAtDesc()) {
            recent.add(NotificationViews.toView(e, mapper));
            if (recent.size() >= 30) {
                break;
            }
        }
        send(session, Map.of("type", "snapshot", "notifications", recent));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String type = mapper.readTree(message.getPayload()).path("type").asText("");
        if ("ping".equals(type)) {
            send(session, Map.of("type", "pong"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @Override
    public void broadcast(NotificationView notification) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "notification");
        frame.put("notification", notification);
        String payload = null;
        try {
            payload = mapper.writeValueAsString(frame);
        } catch (Exception e) {
            log.warn("通知 WS 帧序列化失败: id={}", notification.id(), e);
            return;
        }
        for (WebSocketSession s : sessions) {
            send(s, payload);
        }
    }

    private void send(WebSocketSession session, Object payload) {
        try {
            String json = payload instanceof String s ? s : mapper.writeValueAsString(payload);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.debug("通知 WS 发送失败(可能已关闭): {}", e.getMessage());
        }
    }
}
