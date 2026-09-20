package com.devmind.model.dto;

/**
 * CAP-48 FR-03 连接测试结果。{@code dimensions} 是本次<b>实测探测</b>出来的向量维度，
 * 不是配置回显——它的存在就是为了让"换模型后维度变了"这件事在切换当天就可见。
 *
 * @param ok               是否连通
 * @param latencyMs        往返耗时
 * @param model            端点模型名
 * @param dimensions       实测维度（仅 ok 时非空）
 * @param message          诊断信息（失败原因已脱敏，不含 apiKey）
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
