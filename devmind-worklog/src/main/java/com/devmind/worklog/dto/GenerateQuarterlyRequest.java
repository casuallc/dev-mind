package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 季度复盘生成请求（CAP-91 FR-09）。period='2026-Q1'；force=true 显式覆盖已有成稿。
 * hint 为本人补充说明（高优先级事实注入 prompt）；null/空白 = 不使用。
 */
public record GenerateQuarterlyRequest(@NotBlank @Size(max = 8) String period, Boolean force,
                                       @Size(max = 2000) String hint) {}
