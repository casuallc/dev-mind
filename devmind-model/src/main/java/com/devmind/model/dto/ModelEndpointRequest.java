package com.devmind.model.dto;

/**
 * CAP-48 FR-01 端点创建/更新请求。
 *
 * <p>注意<b>没有 dimensions 字段</b>——维度是连接测试实测探测的产物，结构上就不给人工提交的入口
 * （人工填错维度是 FR-06 要防的事故源）。</p>
 *
 * <p>{@code batchSize}/{@code topK}/{@code threshold} 是<b>向量语义</b>（FR-11）：{@code kind} 不是
 * EMBEDDING 时服务端把它们一律落 null（越界值也不报错），调用方不必为此分叉。</p>
 *
 * @param kind           EMBEDDING | CHAT | DECISION（RERANK 预留，传值 400）
 * @param name           展示名（创建必填）
 * @param provider       openai-compatible（EMBEDDING/CHAT）| laya（DECISION，laya 决策边车）| mock；
 *                       与 kind 配套校验，不匹配 400（EMBEDDING + laya 这类组合落库了必错）
 * @param baseUrl        服务根地址（openai-compatible / laya 均必填）；
 *                       <b>laya 只填边车根地址</b>如 {@code http://host:8377}，路径由平台拼，<b>不带 /v1</b>
 * @param apiKey         密钥；<b>更新时留空 = 保持不变</b>，新建时留空 = 无凭据端点
 * @param model          模型名（openai-compatible 必填）；DECISION 时是 laya 的 checkpoint 别名
 *                       （english / multilingual / typed-decisions），<b>可空 = 由边车按语言自动路由</b>
 * @param timeoutSeconds 单次请求超时秒数（1~600，空取 30）
 * @param batchSize      单次请求最大条数（1~256，空取 32；仅 EMBEDDING 使用）
 * @param topK           检索条数覆盖（空 = 平台默认；仅 EMBEDDING 使用）
 * @param threshold      余弦阈值覆盖（空 = 平台默认；仅 EMBEDDING 使用）
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
