package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * CAP-88 M3「AI 建议计划」请求。
 *
 * @param date  目标周（周内任意日，服务端归一到周一）
 * @param hint  本人补充说明（可选，高优先级事实）
 * @param force 目标周已有计划项时仍重生成（只追加不覆盖；null/false = 已有项则幂等跳过）
 */
public record SuggestPlanRequest(@NotNull LocalDate date, String hint, Boolean force) {
}
