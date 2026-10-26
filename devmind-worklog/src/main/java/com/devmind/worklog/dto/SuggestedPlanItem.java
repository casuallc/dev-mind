package com.devmind.worklog.dto;

/**
 * CAP-88 M3 FR-10 AI 建议计划项（解析产物 → 插库前的中间形态）。
 *
 * @param title           标题（解析后已截到 256 以内）
 * @param planType        类型（DEV/SUPPORT/MEETING/RESEARCH/OTHER；行内未标注回退 DEV）
 * @param estimateMinutes 预估工时（分钟；行内未写为 null）
 */
public record SuggestedPlanItem(String title, String planType, Integer estimateMinutes) {
}
