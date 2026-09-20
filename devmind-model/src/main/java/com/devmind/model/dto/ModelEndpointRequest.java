package com.devmind.model.dto;

/**
 * CAP-48 FR-01 端点创建/更新请求。
 *
 * <p>注意<b>没有 dimensions 字段</b>——维度是连接测试实测探测的产物，结构上就不给人工提交的入口
 * （人工填错维度是 FR-06 要防的事故源）。</p>
 *
 * @param kind           本期只接受 EMBEDDING（CHAT/RERANK 预留，传值 400）
 * @param name           展示名（创建必填）
 * @param provider       openai-compatible | mock
 * @param baseUrl        OpenAI 兼容服务根地址（openai-compatible 必填）
 * @param apiKey         密钥；<b>更新时留空 = 保持不变</b>，新建时留空 = 无凭据端点
 * @param model          模型名（openai-compatible 必填）
 * @param timeoutSeconds 单次请求超时秒数（1~600，空取 30）
 * @param batchSize      单次请求最大条数（1~256，空取 32）
 * @param topK           检索条数覆盖（空 = 平台默认）
 * @param threshold      余弦阈值覆盖（空 = 平台默认）
 * @param status         active | disabled
 */
public record ModelEndpointRequest(
        String kind,
        String name,
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        Integer timeoutSeconds,
        Integer batchSize,
        Integer topK,
        Double threshold,
        String status) {
}
