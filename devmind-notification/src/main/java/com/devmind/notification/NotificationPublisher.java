package com.devmind.notification;

import com.devmind.common.notification.NotificationEvent;

/**
 * 通知发布 SPI（CAP-06）。会话层只调用 publish，不关心通道。
 */
public interface NotificationPublisher {

    void publish(NotificationEvent event);
}
