package com.devmind.usage.dto;

/**
 * 按小时趋势点：hour 为 "00".."23"（服务器时区），tokens = 输入+输出（与汇总口径一致，
 * 不含缓存读），turns = 回合数（一行账本 = 一回合 = 一次请求）。
 */
public record UsageHourlyPoint(String hour, long tokens, long turns) {
}
