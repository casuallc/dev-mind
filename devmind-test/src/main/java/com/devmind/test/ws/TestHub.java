package com.devmind.test.ws;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import com.devmind.test.dto.CaseResultView;

/**
 * CAP-10 测试运行实时流分发：按 runId 分组会话。
 * 帧类型：result（单用例结果）/ done（终态）。
 */
@Component
public class TestHub {

    private static final Logger log = LoggerFactory.getLogger(TestHub.class);

    private final ObjectMapper mapper;
    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public TestHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void subscribe(Long runId, WebSocketSession session) {
        sessions.computeIfAbsent(runId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unsubscribe(Long runId, WebSocketSession session) {
        Set<WebSocketSession> set = sessions.get(runId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                sessions.remove(runId);
            }
        }
    }

    public void publishResult(Long runId, CaseResultView result) {
        try {
            send(runId, "{\"type\":\"result\",\"result\":" + mapper.writeValueAsString(result) + "}");
        } catch (Exception e) {
            log.debug("测试结果帧序列化失败: {}", e.getMessage());
        }
    }

    public void done(Long runId, String status) {
        send(runId, "{\"type\":\"done\",\"status\":" + jsonStr(status) + "}");
    }

    private void send(Long runId, String payload) {
        Set<WebSocketSession> set = sessions.get(runId);
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
                log.debug("测试 WS 发送失败(可能已关闭): {}", e.getMessage());
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
