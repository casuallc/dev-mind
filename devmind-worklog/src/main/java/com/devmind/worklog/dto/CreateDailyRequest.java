package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 手动创建空白日报草稿请求（不经 AI、不要求素材）。 */
public record CreateDailyRequest(@NotNull LocalDate date) {}
