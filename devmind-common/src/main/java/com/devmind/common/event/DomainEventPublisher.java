package com.devmind.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 领域事件总线（P0-3）：对 ApplicationEventPublisher 的薄包装。
 * 业务模块只依赖本类发布 {@link DomainEvent}；订阅方（通知/审计/测试自动回归等）
 * 用 Spring @EventListener 按类型接收，互不依赖。进程内同步分发，持久化/跨进程以后再加。
 */
@Component
public class DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final ApplicationEventPublisher delegate;

    public DomainEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    public void publish(DomainEvent event) {
        try {
            delegate.publishEvent(event);
        } catch (Exception e) {
            // 事件是旁路：订阅方异常不影响主流程
            log.warn("领域事件发布失败: type={} err={}", event.type(), e.getMessage());
        }
    }
}
