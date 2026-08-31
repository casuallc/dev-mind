package com.devmind.session.action;

import com.devmind.notification.action.NotificationActionHandler;
import com.devmind.session.service.SessionManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 会话通知快捷动作（FR-04）：WAITING_AUTH 的允许/拒绝、WAITING_INPUT 的结束、完成后查看。
 * 实现 notification 模块的 SPI，由 NotificationService 分发；避免 notification ↔ session 循环依赖。
 */
@Component
public class SessionNotificationActionHandler implements NotificationActionHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionNotificationActionHandler.class);

    private final SessionManagerService service;

    public SessionNotificationActionHandler(SessionManagerService service) {
        this.service = service;
    }

    @Override
    public boolean supports(String entityType) {
        return "SESSION".equalsIgnoreCase(entityType);
    }

    @Override
    public boolean canHandle(String action) {
        return switch (action == null ? "" : action) {
            case "authorize", "deny", "finish", "view" -> true;
            default -> false;
        };
    }

    @Override
    public void handle(String entityType, String entityId, String action) {
        if (entityId == null || entityId.isBlank()) {
            return;
        }
        try {
            switch (action) {
                case "authorize" -> service.authorize(entityId, true, "once", "");
                case "deny" -> service.authorize(entityId, false, "", "");
                case "finish" -> service.finish(entityId);
                case "view" -> { /* 前端跳转 /sessions/{id}，服务端无需动作 */ }
                default -> log.debug("未识别的会话动作: {}", action);
            }
        } catch (Exception e) {
            log.warn("会话快捷动作执行失败: session={} action={} err={}",
                    entityId, action, e.getMessage());
            // 通知动作失败由调用方（NotificationService.action）抛出，前端可感知
            throw e;
        }
    }
}
