package com.devmind.decisionlab.checkpoint.dto;

import java.util.Map;

/**
 * CAP-56 产物详情：列表行 + 三坨正文（评测指标 / 温度校准 / 上次 serve 自检）。
 *
 * <p>三坨都解析成 map 返回；解析失败的当空 map（同 {@code DatasetJson} 的口径）——
 * 一份脏报告不该让详情页 500，而「没有指标」与「指标读不出来」在界面上都是「该去跑一次评测」。</p>
 *
 * <p>{@code serveCheck} 是<b>完整的</b>自检结果（{@code status} / {@code summary} / {@code checks} /
 * {@code report} / {@code checkedAt}），不是只有那份原始健康应答——详情页要显示的第一件事就是逐项
 * 检查明细与结论，把它们再拆一次没有意义（边车原值在 {@code report} 里）。</p>
 */
public record CheckpointDetail(
        CheckpointView checkpoint,
        Map<String, Object> metrics,
        Map<String, Object> calibration,
        Map<String, Object> serveCheck) {
}
