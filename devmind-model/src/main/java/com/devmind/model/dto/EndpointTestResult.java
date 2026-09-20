package com.devmind.model.dto;

/**
 * CAP-48 FR-03 连接测试结果。{@code dimensions} 是本次<b>实测探测</b>出来的向量维度，
 * 不是配置回显——它的存在就是为了让"换模型后维度变了"这件事在切换当天就可见。
 *
 * <p>FR-11：{@code kind=CHAT} 的探针打 {@code /chat/completions}，没有维度这回事，
 * 所以 {@code dimensions} 与 {@code dimensionChanged} 恒为 null，模型回复摘要走 {@code message}
 * （不再新增字段——它已经流到 toast / Alert / 抽屉 / 落库，加一个只会重复）。</p>
 *
 * @param ok               是否连通
 * @param latencyMs        往返耗时
 * @param model            端点模型名
 * @param dimensions       实测维度（仅 EMBEDDING 且 ok 时非空）
 * @param message          诊断信息（失败原因已脱敏，不含 apiKey；CHAT 成功时带回复摘要）
 * @param dimensionChanged 已存端点重测得到不同维度时的变化对（前端据此提示"该端点上已有索引需重建"）
 */
public record EndpointTestResult(
        boolean ok,
        long latencyMs,
        String model,
        Integer dimensions,
        String message,
        DimensionChange dimensionChanged) {

    /** 维度变化：from 原记录（null = 首次探测），to 本次实测 */
    public record DimensionChange(Integer from, Integer to) {
    }
}
