package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/** 滚转请求（CAP-88 FR-05）：把 ids 各项滚转到目标周期（DAY=目标日期；WEEK=周内任意日归一周一）。 */
public record CarryRequest(
        @NotBlank @Size(max = 8) String scope,
        @NotNull LocalDate to,
        @NotEmpty List<Long> ids) {}
