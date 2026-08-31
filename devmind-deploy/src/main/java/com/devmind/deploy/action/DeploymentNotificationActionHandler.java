package com.devmind.deploy.action;

import com.devmind.deploy.service.DeploymentService;
import com.devmind.notification.action.NotificationActionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 部署通知快捷动作（CAP-17 FR-04）：确认门部署从通知中心一键 confirm（确认+执行）。
 * 实现 notification 模块的 SPI，由 NotificationService 分发；避免 notification ↔ deploy 循环依赖。
 */
@Component
public class DeploymentNotificationActionHandler implements NotificationActionHandler {

    private static final Logger log = LoggerFactory.getLogger(DeploymentNotificationActionHandler.class);

    private final DeploymentService service;

    public DeploymentNotificationActionHandler(DeploymentService service) {
        this.service = service;
    }

    @Override
    public boolean supports(String entityType) {
        return "deployment".equalsIgnoreCase(entityType);
    }

    @Override
    public boolean canHandle(String action) {
        return "confirm".equals(action);
    }

    @Override
    public void handle(String entityType, String entityId, String action) {
        if (entityId == null || entityId.isBlank()) {
            return;
        }
        Long id = Long.valueOf(entityId);
        if ("confirm".equals(action)) {
            service.confirm(id);
            service.execute(id);
            log.info("部署已通过通知动作确认并执行: deployment={}", id);
        }
    }
}
