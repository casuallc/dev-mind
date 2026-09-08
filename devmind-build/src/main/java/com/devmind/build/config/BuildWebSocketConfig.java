package com.devmind.build.config;

import com.devmind.build.model.BuildEntity;
import com.devmind.build.repo.BuildRepository;
import com.devmind.common.security.CorsProperties;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.execution.ws.ExecutionSnapshotProvider.ExecutionSnapshot;
import com.devmind.execution.ws.ExecutionWsHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * 注册 /ws/builds/** 构建日志实时流（P0-1 起由统一执行底座的通用
 * {@link ExecutionWsHandler} 承担，快照/终态判定由本配置按 buildId 提供）。
 */
@Configuration
@EnableWebSocket
public class BuildWebSocketConfig implements WebSocketConfigurer {

    private final ExecutionLogHub hub;
    private final BuildRepository repo;
    private final ObjectMapper mapper;
    private final CorsProperties corsProperties;

    public BuildWebSocketConfig(ExecutionLogHub hub, BuildRepository repo, ObjectMapper mapper,
                                CorsProperties corsProperties) {
        this.hub = hub;
        this.repo = repo;
        this.mapper = mapper;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(
                        new ExecutionWsHandler(hub, this::snapshot, mapper, "/builds/"),
                        "/ws/builds/**")
                .setAllowedOrigins(corsProperties.originsArray());
    }

    /** 按 buildId 提供历史日志快照与终态；id 非法或记录不存在返回 null（关闭连接） */
    private ExecutionSnapshot snapshot(String topic) {
        Long id;
        try {
            id = Long.parseLong(topic);
        } catch (NumberFormatException e) {
            return null;
        }
        BuildEntity b = repo.findById(id).orElse(null);
        if (b == null) {
            return null;
        }
        boolean terminal = BuildEntity.SUCCESS.equals(b.getStatus()) || BuildEntity.FAILED.equals(b.getStatus());
        return new ExecutionSnapshot(b.getLogsText(), b.getStatus(), terminal);
    }
}
