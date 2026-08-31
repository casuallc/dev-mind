package com.devmind.notification.dto;

import java.util.List;
import java.util.Map;

/**
 * 通知偏好更新请求。
 */
public record PrefsRequest(
        Map<String, List<String>> mutes,
        String quietStart,
        String quietEnd,
        List<String> perSessionSilence) {
}
