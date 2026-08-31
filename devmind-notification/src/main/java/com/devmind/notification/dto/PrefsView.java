package com.devmind.notification.dto;

import java.util.List;
import java.util.Map;

/**
 * 通知偏好视图（FR-05 防打扰）。
 *
 * @param mutes             静默配置：{"eventTypes":[...],"entityIds":[...]}
 * @param quietStart        免打扰开始 "HH:mm"，空=不启用
 * @param quietEnd          免打扰结束 "HH:mm"
 * @param perSessionSilence 已静默的会话 ID 列表（快捷入口，与 mutes.entityIds 同源）
 */
public record PrefsView(
        Map<String, List<String>> mutes,
        String quietStart,
        String quietEnd,
        List<String> perSessionSilence) {
}
