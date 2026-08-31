package com.devmind.build.ws;

import com.devmind.build.model.BuildEntity;
import com.devmind.build.repo.BuildRepository;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-08 FR-05 构建日志实时流：WS /ws/builds/{id}/logs。
 * 连接后先推已持久化日志快照 {@code {"type":"snapshot","logs":…}}，再实时收 {@code {"type":"log"}} 增量，
 * 构建结束收 {@code {"type":"done","status":…}}。
 */
@Component
public class BuildLogWsHandler extends TextWebSocketHandler {

    private final BuildLogHub hub;
    private final BuildRepository repo;
    private final ObjectMapper mapper;
    private final Map<WebSocketSession, Long> buildBySession = new ConcurrentHashMap<>();

    public BuildLogWsHandler(BuildLogHub hub, BuildRepository repo, ObjectMapper mapper) {
        this.hub = hub;
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long buildId = parseId(session.getUri());
        if (buildId == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        buildBySession.put(session, buildId);
        hub.subscribe(buildId, session);
        BuildEntity b = repo.findById(buildId).orElse(null);
        if (b == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        if (b.getLogsText() != null && !b.getLogsText().isBlank()) {
            send(session, mapper.writeValueAsString(Map.of("type", "snapshot", "logs", b.getLogsText())));
        }
        if (isTerminal(b.getStatus())) {
            send(session, mapper.writeValueAsString(Map.of("type", "done", "status", b.getStatus())));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String type = mapper.readTree(message.getPayload()).path("type").asText("");
        if ("ping".equals(type)) {
            send(session, mapper.writeValueAsString(Map.of("type", "pong")));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long id = buildBySession.remove(session);
        if (id != null) {
            hub.unsubscribe(id, session);
        }
    }

    private boolean isTerminal(String status) {
        return BuildEntity.SUCCESS.equals(status) || BuildEntity.FAILED.equals(status);
    }

    private void send(WebSocketSession session, String payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            }
        } catch (Exception e) {
            // 会话已关闭则忽略
        }
    }

    private Long parseId(URI uri) {
        if (uri == null) {
            return null;
        }
        String path = uri.getPath();
        int idx = path.indexOf("/builds/");
        if (idx < 0) {
            return null;
        }
        String rest = path.substring(idx + "/builds/".length());
        int slash = rest.indexOf('/');
        String id = slash > 0 ? rest.substring(0, slash) : rest;
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
