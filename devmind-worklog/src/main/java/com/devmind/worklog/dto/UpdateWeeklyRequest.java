package com.devmind.worklog.dto;

import jakarta.validation.constraints.Size;

/** 周报编辑确认请求。status 仅支持 DRAFT/CONFIRMED。 */
public record UpdateWeeklyRequest(String summaryMd, String nextPlanMd, @Size(max = 16) String status) {}
