package com.devmind.notification.channel;

import com.devmind.notification.dto.NotificationView;

/**
 * WS 推送汇：把一条通知广播给所有 /ws/notifications/stream 连接（由 NotificationWsHandler 实现）。
 * 抽出接口是为了让 WsNotificationChannel 不依赖 controller 包。
 */
public interface NotificationWsPush {

    void broadcast(NotificationView notification);
}
