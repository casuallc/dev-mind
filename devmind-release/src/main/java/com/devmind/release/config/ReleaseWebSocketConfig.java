package com.devmind.release.config;

import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.execution.ws.ExecutionSnapshotProvider.ExecutionSnapshot;
import com.devmind.execution.ws.ExecutionWsHandler;
import com.devmind.release.model.ReleaseEntity;
import com.devmind.release.repo.ReleaseRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 注册 /ws/releases/** 发版实时流（CAP-11，复用执行底座 ExecutionWsHandler/ExecutionLogHub）。
 * topic = releaseId 字符串；快照帧带 releaseId/status/version/tagName/nexusRef，
 * 帧协议与 build/deploy/test 一致（snapshot / log / done），前端直接复用连接方式。
 */
@Configuration
@EnableWebSocket
public class ReleaseWebSocketConfig implements WebSocketConfigurer {

    private final ExecutionLogHub hub;
    private final ReleaseRepository repo;
    private final ObjectMapper mapper;

    public ReleaseWebSocketConfig(ExecutionLogHub hub, ReleaseRepository repo, ObjectMapper mapper) {
        this.hub = hub;
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new ExecutionWsHandler(hub, this::snapshot, mapper, "/releases/"),
                        "/ws/releases/**")
                .setAllowedOrigins("http://localhost:5173", "http://127.0.0.1:5173",
                        "http://localhost:8080", "http://127.0.0.1:8080");
    }

    /** 发版快照：业务字段（日志不重复快照，前端只看实时流）；终态立即补发 done 帧 */
    private ExecutionSnapshot snapshot(String topic) {
        Long id = parseLong(topic);
        if (id == null) {
            return null;
        }
        ReleaseEntity r = repo.findById(id).orElse(null);
        if (r == null) {
            return null;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("releaseId", id);
        extra.put("status", r.getStatus());
        extra.put("version", r.getReleaseVersion() == null ? "" : r.getReleaseVersion());
        extra.put("tagName", r.getTagName() == null ? "" : r.getTagName());
        extra.put("nexusRef", r.getNexusRef() == null ? "" : r.getNexusRef());
        return new ExecutionSnapshot(null, r.getStatus(), isTerminal(r.getStatus()), extra);
    }

    private boolean isTerminal(String status) {
        return ReleaseEntity.SUCCESS.equals(status) || ReleaseEntity.FAILED.equals(status)
                || ReleaseEntity.ROLLED_BACK.equals(status);
    }

    private Long parseLong(String topic) {
        try {
            return Long.parseLong(topic);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
