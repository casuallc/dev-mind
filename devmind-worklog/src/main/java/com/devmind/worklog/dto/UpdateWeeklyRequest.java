package com.devmind.worklog.dto;

import jakarta.validation.constraints.Size;

/** 周报编辑请求。CAP-74 去状态机：status 已废弃，传了 400（防旧前端误以为确认生效）。 */
public record UpdateWeeklyRequest(String summaryMd, String nextPlanMd, @Size(max = 16) String status) {}
