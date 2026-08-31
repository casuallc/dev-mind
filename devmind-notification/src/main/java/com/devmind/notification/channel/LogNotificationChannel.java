package com.devmind.notification.channel;

import com.devmind.notification.dto.NotificationView;
import com.devmind.notification.model.NotificationChannelEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 日志通道：始终留一条可审计的记录（替代早期 LogNotificationPublisher）。
 */
@Component
public class LogNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationChannel.class);

    @Override
    public String code() {
        return "log";
    }

    @Override
    public String name() {
        return "日志";
    }

    @Override
    public void send(NotificationChannelEntity channel, NotificationView notification) {
        log.info("[NOTIFY] {} {} entity={}:{} title={} body={}",
                notification.level(), notification.eventType(),
                notification.entityType(), notification.entityId(),
                notification.title(), notification.body());
    }
}
