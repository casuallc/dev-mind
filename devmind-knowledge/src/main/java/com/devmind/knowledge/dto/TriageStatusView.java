package com.devmind.knowledge.dto;

/**
 * CAP-55 FR-04 分诊可用性（FR-07 按钮置灰用）：只反映<b>配置侧</b>能不能分诊
 * （没装配决策模块 / 没配 DECISION 端点 / 端点缺地址）。
 *
 * <p>刻意不探活：探活要么多一次网络往返，要么给出一个"刚刚还活着"的答案。
 * 边车真关了也不必拦住按钮——点了会得到一次降级、无徽标、原因进 triage_json，
 * 那是比"灰按钮"更清楚的信息。</p>
 *
 * @param available 现在点分诊能不能拿到建议
 * @param reason    不可用原因（available=true 时空串），直接给用户看
 */
public record TriageStatusView(boolean available, String reason) {
}
