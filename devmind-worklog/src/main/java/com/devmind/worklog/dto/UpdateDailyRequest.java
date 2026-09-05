package com.devmind.worklog.dto;

import jakarta.validation.constraints.Size;

/** 日报编辑确认请求。status 仅支持 DRAFT/CONFIRMED。 */
public record UpdateDailyRequest(String contentMd, @Size(max = 16) String status) {}
