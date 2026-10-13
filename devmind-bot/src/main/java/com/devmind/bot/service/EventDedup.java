package com.devmind.bot.service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件去重（CAP-75 FR-06）：channel:eventId → 首次处理时间，TTL 内重放直接丢弃。
 * 平台重推（长连接重连补偿、webhook 重试）会产生同 eventId 重复事件。
 * 全 channel 通用，放底座；内存表，重启清零（重启期间的事件由平台重推，TTL 窗口内仍兜得住）。
 */
public class EventDedup {

    private static final long TTL_MS = 60L * 60 * 1000;

    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    /** 首次见到返回 true；TTL 内重复返回 false。 */
    public boolean markIfFirst(String channel, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true; // 无事件 id 不去重（协议不含或适配器未填）
        }
        sweepExpired();
        return seen.putIfAbsent(channel + ":" + eventId, System.currentTimeMillis()) == null;
    }

    private void sweepExpired() {
        // 访问时惰性清理；表很小（一小时窗口的入站事件），全扫代价可忽略
        if (seen.size() < 512) {
            return;
        }
        long cutoff = System.currentTimeMillis() - TTL_MS;
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < cutoff) {
                it.remove();
            }
        }
    }

    /** 测试用：当前在册条目数。 */
    int size() {
        return seen.size();
    }
}
