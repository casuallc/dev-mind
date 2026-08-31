package com.devmind.notification.dto;

import com.devmind.notification.model.NotificationLevel;
import java.util.List;

/**
 * 通知草稿（内部/测试 emit 用）：等级 + 内容 + 关联实体 + 快捷动作。
 */
public record NotificationDraft(
        NotificationLevel level,
        String eventType,
        String title,
        String body,
        String entityType,
        String entityId,
        List<ActionDef> actions) {
}
