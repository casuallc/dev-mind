package com.devmind.usage.dto;

/** CAP-67 FR-03：每日趋势点（date 为 yyyy-MM-dd 日标签，非时间戳，不走全局时间格式化）。 */
public record UsageDailyPoint(String date, double costUsd, long tokens, long turns) {
}
