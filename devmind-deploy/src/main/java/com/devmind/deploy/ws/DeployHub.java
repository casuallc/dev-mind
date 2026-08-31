package com.devmind.deploy.ws;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import com.devmind.deploy.dto.StepView;

/**
 * CAP-09 FR-02 部署实时流分发：按 deploymentId 分组会话。
 * 帧类型：log（实时日志行）/ step（步骤状态变化）/ done（终态）。
 */
@Component
public class DeployHub {

    private static final Logger log = LoggerFactory.getLogger(DeployHub.class);

    private final ObjectMapper mapper;
    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public DeployHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void subscribe(Long deploymentId, WebSocketSession session) {
        sessions.computeIfAbsent(deploymentId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unsubscribe(Long deploymentId, WebSocketSession session) {
        Set<WebSocketSession> set = sessions.get(deploymentId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                sessions.remove(deploymentId);
            }
        }
    }

    public void publishLog(Long deploymentId, String line) {
        send(deploymentId, "{\"type\":\"log\",\"line\":" + jsonStr(line) + "}");
    }

    public void publishStep(Long deploymentId, StepView step) {
        try {
            send(deploymentId, "{\"type\":\"step\",\"step\":" + mapper.writeValueAsString(step) + "}");
        } catch (Exception e) {
            log.debug("步骤帧序列化失败: {}", e.getMessage());
        }
    }

    public void done(Long deploymentId, String status) {
        send(deploymentId, "{\"type\":\"done\",\"status\":" + jsonStr(status) + "}");
    }

    private void send(Long deploymentId, String payload) {
        Set<WebSocketSession> set = sessions.get(deploymentId);
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
                log.debug("部署 WS 发送失败(可能已关闭): {}", e.getMessage());
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
