package com.devmind.task.ws;

import com.devmind.common.security.CorsProperties;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.execution.ws.ExecutionSnapshotProvider.ExecutionSnapshot;
import com.devmind.execution.ws.ExecutionWsHandler;
import com.devmind.task.model.AsyncTaskRunEntity;
import com.devmind.task.repo.AsyncTaskRunRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-80 M3：注册 /ws/async-tasks/** 执行日志实时流（复用 CAP-12 通用
 * {@link ExecutionWsHandler}，topic=runId；快照/终态判定由本配置按 runId 提供）。
 */
@Configuration
@EnableWebSocket
public class AsyncTaskWsConfig implements WebSocketConfigurer {

    private final ExecutionLogHub hub;
    private final AsyncTaskRunRepository runRepo;
    private final ObjectMapper mapper;
    private final CorsProperties corsProperties;

    public AsyncTaskWsConfig(ExecutionLogHub hub, AsyncTaskRunRepository runRepo,
                             ObjectMapper mapper, CorsProperties corsProperties) {
        this.hub = hub;
        this.runRepo = runRepo;
        this.mapper = mapper;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(
                        new ExecutionWsHandler(hub, this::snapshot, mapper, "/async-tasks/"),
                        "/ws/async-tasks/**")
                .setAllowedOrigins(corsProperties.originsArray());
    }

    /** 按 runId 提供历史日志快照与终态；run 不存在返回 null（关闭连接） */
    private ExecutionSnapshot snapshot(String topic) {
        AsyncTaskRunEntity run = runRepo.findById(topic).orElse(null);
        if (run == null) {
            return null;
        }
        return new ExecutionSnapshot(run.getLogs(), run.getStatus(), run.isTerminal());
    }
}
