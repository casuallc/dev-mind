package com.devmind.worklog.dto;

import jakarta.validation.constraints.Size;

/** 日报编辑请求。CAP-74 去状态机：status 已废弃，传了 400（防旧前端误以为确认生效）。 */
public record UpdateDailyRequest(String contentMd, @Size(max = 16) String status) {}
