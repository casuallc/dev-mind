package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * CAP-93 FR-01「AI 生成 OKR」请求。
 *
 * @param scope    YEAR / QUARTER
 * @param period   YEAR='2026'；QUARTER='2026-Q1'（格式与 scope 匹配，服务端校验）
 * @param hint     本人补充说明（可选，高优先级事实）；textOnly 时为必填的唯一生成依据
 * @param force    目标周期已有目标时仍重生成草稿（null/false = 已有目标则幂等跳过 reused=true）
 * @param textOnly FR-07 纯文本模式：不聚合既有目标/周报素材，仅以 hint 文本为生成依据（hint 必填）
 */
public record OkrSuggestRequest(@NotBlank @Size(max = 8) String scope,
                                @NotBlank @Size(max = 8) String period,
                                @Size(max = 4000) String hint,
                                Boolean force,
                                Boolean textOnly) {
}
