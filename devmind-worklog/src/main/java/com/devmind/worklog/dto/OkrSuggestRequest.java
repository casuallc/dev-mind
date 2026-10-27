package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * CAP-93 FR-01「AI 生成 OKR」请求。
 *
 * @param scope  YEAR / QUARTER
 * @param period YEAR='2026'；QUARTER='2026-Q1'（格式与 scope 匹配，服务端校验）
 * @param hint   本人补充说明（可选，高优先级事实）
 * @param force  目标周期已有目标时仍重生成草稿（null/false = 已有目标则幂等跳过 reused=true）
 */
public record OkrSuggestRequest(@NotBlank @Size(max = 8) String scope,
                                @NotBlank @Size(max = 8) String period,
                                @Size(max = 500) String hint,
                                Boolean force) {
}
