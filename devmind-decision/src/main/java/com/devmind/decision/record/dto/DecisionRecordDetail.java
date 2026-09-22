package com.devmind.decision.record.dto;

import java.util.Map;

/**
 * CAP-55 FR-05 决策记录详情：列表视图 + 当初发给模型的上下文与题面（逐字回放）。
 *
 * <p>组合而不是复制字段：列表与详情必须永远显示同一份"模型说了什么/人说了什么"，
 * 分成两个平行结构迟早会漂移（改了一个忘了另一个）。详情多出来的只有两坨快照。</p>
 *
 * @param record    列表视图那份
 * @param state     发给模型的 state 快照（截断后的原文）
 * @param questions 发出的题面（含 criteria）
 */
public record DecisionRecordDetail(
        DecisionRecordView record,
        Map<String, Object> state,
        Map<String, Map<String, Object>> questions) {
}
