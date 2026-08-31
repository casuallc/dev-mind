package com.devmind.common.event;

import java.time.Instant;

/**
 * 领域事件（P0-3 统一事件总线）。各业务模块发布，通知/审计等底座订阅，
 * 模块间不直接依赖（如 build 不再 import notification）。
 *
 * <p>简单场景用 {@link SimpleDomainEvent}；需要强类型载体时自建 record 实现本接口。</p>
 */
public interface DomainEvent {

    /** 事件类型，约定 <域>.<动作>，如 build.completed / deploy.completed / task.advanced */
    String type();

    Instant occurredAt();
}
