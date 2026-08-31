package com.devmind.notification.dto;

import com.devmind.notification.model.NotificationLevel;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 通知视图（通知中心/WS 推送共用）。
 *
 * @param id            通知 ID
 * @param level         分级 P0/P1/P2
 * @param eventType     事件类型（WAITING_INPUT 等）
 * @param title         标题
 * @param body          正文
 * @param entityType    关联实体类型（SESSION）
 * @param entityId      关联实体 ID（会话 ID）
 * @param actions       快捷动作
 * @param channelStatus 各通道发送结果 {"ws":"SENT",...}
 * @param readAt        已读时间（null=未读）
 * @param createdAt     创建时间
 */
public record NotificationView(
        Long id,
        NotificationLevel level,
        String eventType,
        String title,
        String body,
        String entityType,
        String entityId,
        List<ActionDef> actions,
        Map<String, String> channelStatus,
        Instant readAt,
        Instant createdAt) {
}
