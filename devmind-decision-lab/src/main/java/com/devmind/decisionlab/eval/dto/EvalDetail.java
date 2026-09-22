package com.devmind.decisionlab.eval.dto;

import java.util.List;
import java.util.Map;

/**
 * CAP-56 评测详情：视图 + 完整报告 + 从报告里提出来给人看的分块。
 *
 * <p>分块（{@code byCaseGroup}/{@code perItem}/{@code calibration}）是从 {@code report} 里
 * 原样取出来的引用，不是重算的：一次评测只有一份报告，页面要展示的是它的不同切面。</p>
 *
 * @param report     完整报告原文（脚本给什么就是什么，未做删改）
 * @param byCaseGroup 对照组分解（FR-02 的对照组在结果里必须能被单独看见）
 * @param perItem    逐题明细
 * @param commandText 节点上实际跑的那条命令（诊断第一手材料；含节点本地路径，不含凭据）
 */
public record EvalDetail(EvalView view, Map<String, Object> report,
                        List<Map<String, Object>> byCaseGroup,
                        List<Map<String, Object>> perItem,
                        Map<String, Object> calibration,
                        Map<String, Object> compare,
                        String commandText) {
}
