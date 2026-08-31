package com.devmind.execution.ws;

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
 * 统一执行日志分发枢纽（P0-1）：按 topic 分组会话（topic 由各业务定义，如构建=buildId）。
 * 帧协议（各业务前端共用）：
 * <ul>
 *   <li>{@code {"type":"log","line":…}} 实时日志行</li>
 *   <li>{@code {"type":<event>,"payload":…}} 业务事件（步骤状态/用例结果等，见 {@link #publishEvent}）</li>
 *   <li>{@code {"type":"done","status":…}} 终态收尾</li>
 * </ul>
 * 替代原 BuildLogHub / DeployHub / TestHub 三份拷贝。
 */
@Component
public class ExecutionLogHub {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLogHub.class);

    private final ObjectMapper mapper;
    private final Map<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public ExecutionLogHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void subscribe(String topic, WebSocketSession session) {
        sessions.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unsubscribe(String topic, WebSocketSession session) {
        Set<WebSocketSession> set = sessions.get(topic);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                sessions.remove(topic);
            }
        }
    }

    /** 实时日志行帧 */
    public void publishLog(String topic, String line) {
        send(topic, "{\"type\":\"log\",\"line\":" + jsonStr(line) + "}");
    }

    /** 业务事件帧：{@code {"type":<type>,"payload":<json>}}（如 step 状态变化、测试用例结果） */
    public void publishEvent(String topic, String type, Object payload) {
        try {
            send(topic, "{\"type\":" + jsonStr(type) + ",\"payload\":" + mapper.writeValueAsString(payload) + "}");
        } catch (Exception e) {
            log.debug("事件帧序列化失败: {}", e.getMessage());
        }
    }

    /** 终态帧 */
    public void done(String topic, String status) {
        send(topic, "{\"type\":\"done\",\"status\":" + jsonStr(status) + "}");
    }

    private void send(String topic, String payload) {
        Set<WebSocketSession> set = sessions.get(topic);
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
                log.debug("执行日志 WS 发送失败(可能已关闭): {}", e.getMessage());
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
