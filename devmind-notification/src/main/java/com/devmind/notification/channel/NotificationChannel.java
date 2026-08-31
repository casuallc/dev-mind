package com.devmind.notification.channel;

import com.devmind.notification.dto.NotificationView;
import com.devmind.notification.model.NotificationChannelEntity;

/**
 * 通知通道 SPI（CAP-06 FR-03 通道插件化）。
 *
 * <p>实现按 code 注册（ws / log / bark / wecom），由 {@code NotificationService} 统一调度：
 * 先按通道启用开关 + 分级阈值 + 免打扰/静默过滤，通过的调用 {@link #send}。</p>
 */
public interface NotificationChannel {

    /** 通道唯一标识（与 notification_channels.code 对应）。 */
    String code();

    /** 展示名。 */
    String name();

    /**
     * 发送一条通知。失败抛 {@link RuntimeException}，由服务捕获并记录为 FAILED（不阻塞通知流程）。
     *
     * @param channel      通道配置（含启用/阈值/专属配置）
     * @param notification 完整通知视图（含快捷动作、通道状态）
     */
    void send(NotificationChannelEntity channel, NotificationView notification);
}
