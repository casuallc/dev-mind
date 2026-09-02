package com.devmind.integration.config;

import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.execution.ws.ExecutionSnapshotProvider.ExecutionSnapshot;
import com.devmind.execution.ws.ExecutionWsHandler;
import com.devmind.integration.service.RepoCloneService;
import com.devmind.project.model.ProjectRepoEntity;
import com.devmind.project.repo.ProjectRepoRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-23：注册 /ws/repo-clones/** 克隆日志实时流（topic = clone-&lt;repoId&gt;），
 * 快照/终态由本配置按 repoId 从 project_repos 提供（照 BuildWebSocketConfig 结构）。
 */
@Configuration
@EnableWebSocket
public class CloneWebSocketConfig implements WebSocketConfigurer {

    private final ExecutionLogHub hub;
    private final ProjectRepoRepository repoRepo;
    private final ObjectMapper mapper;

    public CloneWebSocketConfig(ExecutionLogHub hub, ProjectRepoRepository repoRepo, ObjectMapper mapper) {
        this.hub = hub;
        this.repoRepo = repoRepo;
        this.mapper = mapper;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(
                        new ExecutionWsHandler(hub, this::snapshot, mapper, "/repo-clones/"),
                        "/ws/repo-clones/**")
                .setAllowedOrigins("http://localhost:5173", "http://127.0.0.1:5173",
                        "http://localhost:8080", "http://127.0.0.1:8080");
    }

    /** 按 repoId 提供历史日志快照与终态；非 CLONE 来源或记录不存在返回 null（关闭连接） */
    private ExecutionSnapshot snapshot(String topic) {
        if (topic == null || !topic.startsWith(RepoCloneService.TOPIC_PREFIX)) {
            return null;
        }
        Long repoId;
        try {
            repoId = Long.parseLong(topic.substring(RepoCloneService.TOPIC_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
        ProjectRepoEntity r = repoRepo.findById(repoId).orElse(null);
        if (r == null || !ProjectRepoEntity.SOURCE_CLONE.equals(r.getSourceType())) {
            return null;
        }
        boolean terminal = !ProjectRepoEntity.CLONE_CLONING.equals(r.getCloneStatus());
        return new ExecutionSnapshot(r.getCloneLogs(), r.getCloneStatus(), terminal);
    }
}
