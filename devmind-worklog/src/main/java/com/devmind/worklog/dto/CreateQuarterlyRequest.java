package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 季度复盘手动空白稿请求（CAP-91 FR-08）：period='2026-Q1'。 */
public record CreateQuarterlyRequest(@NotBlank @Size(max = 8) String period) {}
