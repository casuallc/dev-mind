package com.devmind.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAP-06 通知中心配置。
 *
 * @param dedupMinutes 同事件+同实体去重时间窗（分钟），默认 5
 */
@ConfigurationProperties(prefix = "devmind.notification")
public record NotificationProperties(int dedupMinutes) {

    public NotificationProperties {
        if (dedupMinutes <= 0) {
            dedupMinutes = 5;
        }
    }
}
