package com.devmind.deploy.config;

import com.devmind.deploy.dto.StepView;
import com.devmind.deploy.model.DeploymentEntity;
import com.devmind.deploy.repo.DeploymentRepository;
import com.devmind.deploy.repo.DeploymentStepRepository;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.execution.ws.ExecutionSnapshotProvider.ExecutionSnapshot;
import com.devmind.execution.ws.ExecutionWsHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 注册 /ws/deployments/** 部署实时流（收尾2 起复用执行底座 ExecutionWsHandler/ExecutionLogHub）。
 * topic = deploymentId 字符串；快照帧带业务字段 deploymentId/status/currentStep/backupRef/steps，
 * 帧协议与原 DeployWsHandler 完全一致（log / step / done），前端无改动。
 */
@Configuration
@EnableWebSocket
public class DeployWebSocketConfig implements WebSocketConfigurer {

    private final ExecutionLogHub hub;
    private final DeploymentRepository repo;
    private final DeploymentStepRepository stepRepo;
    private final ObjectMapper mapper;

    public DeployWebSocketConfig(ExecutionLogHub hub, DeploymentRepository repo,
                                 DeploymentStepRepository stepRepo, ObjectMapper mapper) {
        this.hub = hub;
        this.repo = repo;
        this.stepRepo = stepRepo;
        this.mapper = mapper;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new ExecutionWsHandler(hub, this::snapshot, mapper, "/deployments/"),
                        "/ws/deployments/**")
                .setAllowedOrigins("http://localhost:5173", "http://127.0.0.1:5173",
                        "http://localhost:8080", "http://127.0.0.1:8080");
    }

    /** 部署快照：步骤列表 + 当前步 + 备份引用（日志不重复快照，前端只看实时流） */
    private ExecutionSnapshot snapshot(String topic) {
        Long id = parseLong(topic);
        if (id == null) {
            return null;
        }
        DeploymentEntity d = repo.findById(id).orElse(null);
        if (d == null) {
            return null;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("deploymentId", id);
        extra.put("status", d.getStatus());
        extra.put("currentStep", d.getCurrentStep() == null ? 0 : d.getCurrentStep());
        extra.put("backupRef", d.getBackupRef() == null ? "" : d.getBackupRef());
        extra.put("steps", stepRepo.findByDeploymentIdOrderBySeqAsc(id).stream()
                .map(e -> new StepView(e.getId(), e.getSeq(), e.getName(), e.getType(), e.getStatus(),
                        e.getDetail(), e.getStartedAt(), e.getFinishedAt()))
                .toList());
        return new ExecutionSnapshot(null, d.getStatus(), isTerminal(d.getStatus()), extra);
    }

    private boolean isTerminal(String status) {
        return DeploymentEntity.SUCCESS.equals(status) || DeploymentEntity.FAILED.equals(status)
                || DeploymentEntity.ROLLED_BACK.equals(status);
    }

    private Long parseLong(String topic) {
        try {
            return Long.parseLong(topic);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
