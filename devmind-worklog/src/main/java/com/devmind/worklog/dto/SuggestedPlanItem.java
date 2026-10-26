package com.devmind.worklog.dto;

/**
 * CAP-88 M3 FR-10 AI 建议计划项（解析产物 → 插库前的中间形态）。
 *
 * @param title           标题（解析后已截到 256 以内）
 * @param planType        类型（DEV/SUPPORT/MEETING/RESEARCH/OTHER；行内未标注回退 DEV）
 * @param estimateMinutes 预估工时（分钟；行内未写为 null）
 * @param krId            对齐的当季 KR（CAP-91 M3 FR-11 可选标注 `（KR#id）`；未标注为 null，
 *                        落库前由 insertSuggestions 校验归属，非法置空不炸整批）
 */
public record SuggestedPlanItem(String title, String planType, Integer estimateMinutes, Long krId) {
}
