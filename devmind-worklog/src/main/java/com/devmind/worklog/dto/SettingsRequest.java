package com.devmind.worklog.dto;

/**
 * 个人设置更新请求（Jackson 3：布尔必用包装类型）。
 * dailyTemplateMd/weeklyTemplateMd：null = 不变；空白串 = 清除自定义（回退内置默认）。
 */
public record SettingsRequest(Boolean autoDaily, Boolean autoWeekly, Integer dailyMinutesTarget,
                              String dailyTemplateMd, String weeklyTemplateMd) {}
