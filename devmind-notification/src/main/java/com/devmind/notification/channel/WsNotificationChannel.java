package com.devmind.notification.channel;

import com.devmind.notification.dto.NotificationView;
import com.devmind.notification.model.NotificationChannelEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 站内中心通道：把通知实时推给所有连上 /ws/notifications/stream 的前端。
 * 通知中心本体（未读列表/历史）不依赖本通道，本通道只负责"实时增量"。
 */
@Component
public class WsNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(WsNotificationChannel.class);

    private final NotificationWsPush push;

    public WsNotificationChannel(NotificationWsPush push) {
        this.push = push;
    }

    @Override
    public String code() {
        return "ws";
    }

    @Override
    public String name() {
        return "站内（WebSocket）";
    }

    @Override
    public void send(NotificationChannelEntity channel, NotificationView notification) {
        push.broadcast(notification);
    }
}
