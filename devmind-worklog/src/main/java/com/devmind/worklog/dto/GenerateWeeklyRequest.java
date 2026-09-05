package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 周报生成请求。weekStart 为该周周一；force=true 时覆盖已有 DRAFT。 */
public record GenerateWeeklyRequest(@NotNull LocalDate weekStart, Boolean force) {}
