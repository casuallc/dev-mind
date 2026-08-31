package com.devmind.common.notification;

import java.time.Instant;

/**
 * 通知事件（CAP-06 钩子）。MVP 只落日志；后续接入 IM/邮件时 Publisher 实现在本模块替换。
 *
 * @param kind      事件类型：SESSION_STARTED / SESSION_DONE / SESSION_FAILED / WAITING_AUTH / WAITING_INPUT / INPUT_TIMEOUT
 * @param sessionId 会话 ID
 * @param title     通知标题
 * @param content   通知正文
 * @param at        事件时间
 */
public record NotificationEvent(String kind, String sessionId, String title, String content, Instant at) {

    public static NotificationEvent of(String kind, String sessionId, String title, String content) {
        return new NotificationEvent(kind, sessionId, title, content, Instant.now());
    }
}
