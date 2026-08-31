package com.devmind.build.ws;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-08 FR-05 构建日志实时分发：按 buildId 分组会话，runner 每产出一行即广播；
 * 构建结束时广播 {@code {"type":"done","status":…}} 供前端收尾。
 */
@Component
public class BuildLogHub {

    private static final Logger log = LoggerFactory.getLogger(BuildLogHub.class);

    private final ObjectMapper mapper;
    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public BuildLogHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void subscribe(Long buildId, WebSocketSession session) {
        sessions.computeIfAbsent(buildId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unsubscribe(Long buildId, WebSocketSession session) {
        Set<WebSocketSession> set = sessions.get(buildId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                sessions.remove(buildId);
            }
        }
    }

    public void publish(Long buildId, String line) {
        send(buildId, "{\"type\":\"log\",\"line\":" + jsonStr(line) + "}");
    }

    public void done(Long buildId, String status) {
        send(buildId, "{\"type\":\"done\",\"status\":" + jsonStr(status) + "}");
    }

    private void send(Long buildId, String payload) {
        Set<WebSocketSession> set = sessions.get(buildId);
        if (set == null) {
            return;
        }
        for (WebSocketSession s : set) {
            try {
                synchronized (s) {
                    if (s.isOpen()) {
                        s.sendMessage(new TextMessage(payload));
                    }
                }
            } catch (Exception e) {
                log.debug("构建日志 WS 发送失败(可能已关闭): {}", e.getMessage());
            }
        }
    }

    private String jsonStr(String s) {
        try {
            return mapper.writeValueAsString(s == null ? "" : s);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
