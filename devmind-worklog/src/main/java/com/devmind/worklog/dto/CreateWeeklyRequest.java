package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 手动创建空白周报草稿请求。weekStart 为该周周一。 */
public record CreateWeeklyRequest(@NotNull LocalDate weekStart) {}
