package com.devmind.decisionlab.dataset.dto;

import java.util.Map;

/**
 * CAP-56 评测样本详情：列表行 + 逐字回放的三份 JSON + 由它们现算的 gold 分布。
 *
 * <p>{@code distributions} 是 {@code GoldDistributions} 从"人工原值 + 题面"摊出来的结果，
 * 也是评测与微调真正消费的形状。把它一并返回是为了让「我标的 gold 落上了吗」在界面上当场可答——
 * 原值看着没问题、却因为题面选项对不上被丢掉，是这套数据里最容易静默出错的一处。</p>
 */
public record DatasetItemDetail(
        DatasetItemView item,
        Map<String, Object> state,
        /** 题面台账：{题 id: {type,instructions,criteria}}，与 laya 原生 schema 同形 */
        Map<String, Map<String, Object>> questions,
        Map<String, Object> gold,
        Map<String, Object> distributions,
        /** 原值里落不上题面的题 id —— 这些题等于没标，界面需要点名 */
        java.util.List<String> goldNotLanded) {
}
