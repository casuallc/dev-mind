package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 日报生成请求。force=true 时覆盖已有 DRAFT（CONFIRMED 不覆盖）。 */
public record GenerateDailyRequest(@NotNull LocalDate date, Boolean force) {}
