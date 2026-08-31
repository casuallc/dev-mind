package com.devmind.common.event;

import java.time.Instant;

/**
 * 通用领域事件载体（P0-3）：不带专用字段的事件直接用本类，避免每个域各造 record。
 *
 * @param type          事件类型（域.动作），如 build.completed
 * @param projectId     所属项目（P0-6 关联约定）
 * @param workItemId        所属任务（可空 = 项目级）
 * @param actor         操作者（P0-2 Identity；系统触发填 system/触发来源）
 * @param summary       一句话描述（通知正文/审计摘要直接用）
 * @param entityType    关联实体类型（BUILD/DEPLOYMENT/TEST_RUN/…），通知动作定位用
 * @param entityId      关联实体 id
 * @param success       结果类事件：true/false；纯信息事件为 null
 */
public record SimpleDomainEvent(String type,
                                String projectId,
                                String workItemId,
                                String actor,
                                String summary,
                                String entityType,
                                String entityId,
                                Boolean success,
                                Instant occurredAt) implements DomainEvent {

    public static SimpleDomainEvent of(String type, String projectId, String workItemId, String actor,
                                       String summary, String entityType, String entityId, Boolean success) {
        return new SimpleDomainEvent(type, projectId, workItemId, actor, summary, entityType, entityId,
                success, Instant.now());
    }
}
