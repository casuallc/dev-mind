package com.devmind.notification.dto;

import com.devmind.notification.model.NotificationLevel;

/**
 * 测试/调试 emit 请求：POST /api/notifications/emit。
 */
public record EmitRequest(
        String eventType,
        String title,
        String body,
        NotificationLevel level,
        String entityType,
        String entityId) {
}
