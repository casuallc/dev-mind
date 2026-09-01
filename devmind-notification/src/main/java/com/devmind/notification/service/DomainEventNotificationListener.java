package com.devmind.notification.service;

import com.devmind.common.event.DomainEvent;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.notification.dto.ActionDef;
import com.devmind.notification.dto.NotificationDraft;
import com.devmind.notification.model.NotificationLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 领域事件 → 通知（P0-3 统一事件总线订阅方）：各业务模块只向总线发 {@link DomainEvent}，
 * 本监听器负责分级路由，业务模块不再依赖 notification。
 *
 * <p>级别约定：success=false → P0；*.completed 成功 → P1；其余 → P2。</p>
 */
@Component
public class DomainEventNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(DomainEventNotificationListener.class);

    /** 忽略清单：高频中性事件不转通知（workitem 状态翻转/拆分固化由 CAP-15 编排器自行发"已自动派发"通知，信息更明确）。 */
    private static final Set<String> IGNORED_TYPES = Set.of("workitem.status.changed", "flow.split.confirmed");

    private final NotificationService notificationService;

    public DomainEventNotificationListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @EventListener
    public void on(DomainEvent event) {
        try {
            if (IGNORED_TYPES.contains(event.type())) {
                return;
            }
            if (event instanceof SimpleDomainEvent e) {
                notificationService.emit(draft(e));
            }
            // 强类型事件（如 DeploymentCompletedEvent）实现 DomainEvent 后在此扩展路由
        } catch (Exception ex) {
            log.warn("领域事件通知失败: type={} err={}", event.type(), ex.getMessage());
        }
    }

    private NotificationDraft draft(SimpleDomainEvent e) {
        NotificationLevel level = Boolean.FALSE.equals(e.success()) ? NotificationLevel.P0
                : e.type().endsWith(".completed") ? NotificationLevel.P1
                : NotificationLevel.P2;
        List<ActionDef> actions = e.entityId() == null ? List.of()
                : List.of(new ActionDef("view", "查看" + domainLabel(e.type())));
        return new NotificationDraft(level, e.type().toUpperCase().replace('.', '_'),
                title(e), e.summary(), e.entityType(), e.entityId(), actions);
    }

    private String title(SimpleDomainEvent e) {
        String label = domainLabel(e.type());
        if (Boolean.FALSE.equals(e.success())) {
            return label + "失败";
        }
        if (e.type().endsWith(".completed")) {
            return label + "完成";
        }
        return label + "事件";
    }

    private String domainLabel(String type) {
        String domain = type == null || !type.contains(".") ? "" : type.substring(0, type.indexOf('.'));
        return switch (domain) {
            case "build" -> "构建";
            case "deploy" -> "部署";
            case "test" -> "测试";
            case "release" -> "发版";
            case "session" -> "会话";
            case "integration" -> "集成";
            default -> "任务";
        };
    }
}
