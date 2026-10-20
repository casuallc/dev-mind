package com.devmind.notification.dto;

import java.util.Map;

/**
 * 通道配置更新请求。
 *
 * @param enabled        启用开关（null = 不变；Jackson 3 下请求 DTO 布尔必须用包装类型）
 * @param levelThreshold 可推送的最低级别
 * @param config         通道专属配置（合并更新）
 */
public record ChannelRequest(Boolean enabled, String levelThreshold, Map<String, Object> config) {
}
