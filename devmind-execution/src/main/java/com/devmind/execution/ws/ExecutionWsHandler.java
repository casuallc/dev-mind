package com.devmind.execution.ws;

import com.devmind.execution.ws.ExecutionSnapshotProvider.ExecutionSnapshot;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用执行日志 WS 处理器（P0-1）：连接后先推历史快照 {@code {"type":"snapshot","logs":…}}，
 * 再实时收 {@code {"type":"log"}} / 业务事件帧，终态收 {@code {"type":"done","status":…}}。
 * 非 @Component——由各业务模块的 WebSocketConfigurer 按路径前缀实例化注册
 * （如 build 注册 /ws/builds/**，pathPrefix="/builds/"）。
 */
public class ExecutionWsHandler extends TextWebSocketHandler {

    private final ExecutionLogHub hub;
    private final ExecutionSnapshotProvider snapshots;
    private final ObjectMapper mapper;
    private final String pathPrefix;
    private final Map<WebSocketSession, String> topicBySession = new ConcurrentHashMap<>();

    /**
     * @param pathPrefix 路径中 topic 之前的片段（含首尾斜杠），如 "/builds/"
     */
    public ExecutionWsHandler(ExecutionLogHub hub, ExecutionSnapshotProvider snapshots,
                              ObjectMapper mapper, String pathPrefix) {
        this.hub = hub;
        this.snapshots = snapshots;
        this.mapper = mapper;
        this.pathPrefix = pathPrefix;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String topic = parseTopic(session.getUri());
        if (topic == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        ExecutionSnapshot snapshot = snapshots.lookup(topic);
        if (snapshot == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        topicBySession.put(session, topic);
        hub.subscribe(topic, session);
        boolean hasLogs = snapshot.logsText() != null && !snapshot.logsText().isBlank();
        boolean hasExtra = snapshot.extra() != null && !snapshot.extra().isEmpty();
        if (hasLogs || hasExtra) {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", "snapshot");
            if (hasLogs) {
                frame.put("logs", snapshot.logsText());
            }
            if (hasExtra) {
                frame.putAll(snapshot.extra());
            }
            send(session, mapper.writeValueAsString(frame));
        }
        if (snapshot.terminal()) {
            send(session, mapper.writeValueAsString(Map.of("type", "done", "status",
                    snapshot.status() == null ? "" : snapshot.status())));
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
        String topic = topicBySession.remove(session);
        if (topic != null) {
            hub.unsubscribe(topic, session);
        }
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

    private String parseTopic(URI uri) {
        if (uri == null) {
            return null;
        }
        String path = uri.getPath();
        int idx = path.indexOf(pathPrefix);
        if (idx < 0) {
            return null;
        }
        String rest = path.substring(idx + pathPrefix.length());
        int slash = rest.indexOf('/');
        String topic = slash > 0 ? rest.substring(0, slash) : rest;
        return topic.isBlank() ? null : topic;
    }
}
