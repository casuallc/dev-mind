package com.devmind.common.model;

import java.util.Map;

/**
 * CAP-55 决策答案：laya 三原语（choice / score / noul）共用一个形状，与边车
 * {@code /v1/predict} 应答里的 {@code answers} 逐题对应。
 *
 * <p>哪个字段有值由 {@link #type()} 决定，其余为 null——这是刻意的取舍：三个原语在协议里
 * 本来就是同一层级的答案对象（{@code choice}/{@code score}/{@code noul} 三键之一 + 共有的
 * {@code confidence}），拆成三个子类型只会让消费方（FR-04 的分诊要同时读三题）
 * 每次取值前都得先判类型再分支。</p>
 *
 * @param type          choice | score | noul
 * @param choice        choice 题的选中项名；其余原语为 null
 * @param score         score 题的期望分（0 ~ 等级数-1 的连续值）；其余原语为 null
 * @param noul          noul 题的概率（0~1）；其余原语为 null
 * @param confidence    模型自评置信度；<b>温度校准前只作展示参考，不作自动执行依据</b>（CAP-55 §3）
 * @param probabilities 概率分布：choice 题以选项名为键、score 题以等级下标（"0"/"1"/…）为键；
 *                      边车未给分布时为空 map（不是 null）
 */
public record DecisionAnswer(
        String type,
        String choice,
        Double score,
        Double noul,
        Double confidence,
        Map<String, Double> probabilities) {

    /** choice 题选中项的概率；非 choice 题（没有"选中项"这个概念）→ null */
    public Double choiceProbability() {
        return choice == null || probabilities == null ? null : probabilities.get(choice);
    }
}
