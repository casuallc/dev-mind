package com.devmind.worklog.dto;

/** 个人设置更新请求（Jackson 3：布尔必用包装类型）。 */
public record SettingsRequest(Boolean autoDaily, Boolean autoWeekly, Integer dailyMinutesTarget) {}
