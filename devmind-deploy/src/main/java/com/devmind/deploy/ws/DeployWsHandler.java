package com.devmind.deploy.ws;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import com.devmind.deploy.dto.StepView;
import com.devmind.deploy.model.DeploymentEntity;
import com.devmind.deploy.model.DeploymentStepEntity;
import com.devmind.deploy.repo.DeploymentRepository;
import com.devmind.deploy.repo.DeploymentStepRepository;

/**
 * CAP-09 FR-02 部署实时流：WS /ws/deployments/{id}/stream。
 * 连接后先推快照（状态 + 计划 + 步骤），再实时收 step/log 增量，终态收 done。
 */
@Component
public class DeployWsHandler extends TextWebSocketHandler {

    private final DeployHub hub;
    private final DeploymentRepository repo;
    private final DeploymentStepRepository stepRepo;
    private final ObjectMapper mapper;
    private final Map<WebSocketSession, Long> deploymentBySession = new ConcurrentHashMap<>();

    public DeployWsHandler(DeployHub hub, DeploymentRepository repo, DeploymentStepRepository stepRepo, ObjectMapper mapper) {
        this.hub = hub;
        this.repo = repo;
        this.stepRepo = stepRepo;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long id = parseId(session.getUri());
        if (id == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        deploymentBySession.put(session, id);
        hub.subscribe(id, session);
        DeploymentEntity d = repo.findById(id).orElse(null);
        if (d == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        List<StepView> steps = stepRepo.findByDeploymentIdOrderBySeqAsc(id).stream()
                .map(this::toStepView).toList();
        send(session, mapper.writeValueAsString(Map.of(
                "type", "snapshot",
                "deploymentId", id,
                "status", d.getStatus(),
                "currentStep", d.getCurrentStep() == null ? 0 : d.getCurrentStep(),
                "backupRef", d.getBackupRef() == null ? "" : d.getBackupRef(),
                "steps", steps)));
        if (isTerminal(d.getStatus())) {
            send(session, mapper.writeValueAsString(Map.of("type", "done", "status", d.getStatus())));
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
        Long id = deploymentBySession.remove(session);
        if (id != null) {
            hub.unsubscribe(id, session);
        }
    }

    private boolean isTerminal(String status) {
        return DeploymentEntity.SUCCESS.equals(status) || DeploymentEntity.FAILED.equals(status)
                || DeploymentEntity.ROLLED_BACK.equals(status);
    }

    private StepView toStepView(DeploymentStepEntity e) {
        return new StepView(e.getId(), e.getSeq(), e.getName(), e.getType(), e.getStatus(),
                e.getDetail(), e.getStartedAt(), e.getFinishedAt());
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

    private Long parseId(URI uri) {
        if (uri == null) {
            return null;
        }
        String path = uri.getPath();
        int idx = path.indexOf("/deployments/");
        if (idx < 0) {
            return null;
        }
        String rest = path.substring(idx + "/deployments/".length());
        int slash = rest.indexOf('/');
        String id = slash > 0 ? rest.substring(0, slash) : rest;
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
