package com.devmind.notification.dto;

import java.util.Map;

/**
 * 通知通道配置视图。
 *
 * @param id            通道 ID
 * @param code          ws / log / bark / wecom
 * @param name          展示名
 * @param enabled       启用开关
 * @param levelThreshold 可推送的最低级别（P0/P1/P2）
 * @param config        通道专属配置（bark 的 server/key、wecom 的 webhookUrl 等）
 */
public record ChannelView(
        Long id,
        String code,
        String name,
        boolean enabled,
        String levelThreshold,
        Map<String, Object> config) {
}
