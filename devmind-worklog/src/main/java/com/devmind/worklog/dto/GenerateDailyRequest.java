package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 日报生成请求。force=true 时显式覆盖已有成稿（旧稿 git 历史可找回）。
 * hint 为本人补充说明（重点/影响/数字），作为高优先级事实注入生成 prompt；null/空白 = 不使用。
 */
public record GenerateDailyRequest(@NotNull LocalDate date, Boolean force,
                                   @Size(max = 2000) String hint) {}
