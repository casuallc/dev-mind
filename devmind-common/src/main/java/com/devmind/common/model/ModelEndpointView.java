package com.devmind.common.model;

/**
 * CAP-48 模型端点解析视图 —— SPI 内部类型，只在模块间内存传递。
 *
 * <p><b>凭据红线</b>：{@link #apiKey()} 是解密后的明文密钥，因此本类型
 * <b>禁止</b>序列化进任何 HTTP 响应、禁止写日志、禁止进异常消息。
 * HTTP 侧视图只暴露 {@code hasApiKey} 布尔位。与 CAP-18
 * {@code IntegrationConnector.testConnection(cfg, token)} 显式传凭据同口径——
 * 凭据由服务层解密后经 SPI 交给真正的调用方，连接器/客户端不接触持久层。</p>
 *
 * <p>{@code provider} 决定调用方式：{@code openai-compatible} 走 HTTP
 * {@code {baseUrl}/embeddings}；{@code mock} 走确定性哈希向量（测试/E2E 用）。</p>
 *
 * @param id             端点 ID（索引血缘以此记录"用了谁"）
 * @param kind           EMBEDDING / CHAT / RERANK（CHAT 自 FR-11 起可登记；RERANK 仍是预留值）
 * @param provider       openai-compatible | mock
 * @param name           展示名
 * @param baseUrl        OpenAI 兼容服务根地址（mock 可空）
 * @param apiKey         解密后的密钥（可空；禁外泄）
 * @param model          模型名
 * @param dimensions     向量维度；<b>连接测试探测的产物</b>，未探测过为 null
 * @param timeoutSeconds 单次 HTTP 请求超时
 * @param batchSize      单次请求最大文本条数
 * @param topK           检索条数覆盖（null = 用平台默认）
 * @param threshold      余弦阈值覆盖（null = 用平台默认；不同模型的合理阈值差异极大）
 */
public record ModelEndpointView(
        long id,
        String kind,
        String provider,
        String name,
        String baseUrl,
        String apiKey,
        String model,
        Integer dimensions,
        int timeoutSeconds,
        int batchSize,
        Integer topK,
        Double threshold) {

    public static final String KIND_EMBEDDING = "EMBEDDING";
    public static final String PROVIDER_OPENAI = "openai-compatible";
    public static final String PROVIDER_MOCK = "mock";
    /**
     * mock provider 的模型名。<b>跨模块合同值</b>：它会被写进索引血缘（indexed_model）并与
     * 端点侧比对，两处硬编码各写一遍就有漂移风险，因此统一以本常量为准。
     */
    public static final String MODEL_MOCK = "mock-embedding";

    public boolean mock() {
        return PROVIDER_MOCK.equalsIgnoreCase(provider);
    }

    /**
     * 是否向量端点。FR-11 起端点表不再只有 EMBEDDING，<b>消费方必须用本方法过滤</b>——
     * 把 CHAT 端点当向量端点用会拿对话模型名去打 {@code /embeddings}，失败之外还会把
     * 对话模型名写进索引血缘（{@code indexed_model}）。
     */
    public boolean embedding() {
        return KIND_EMBEDDING.equals(kind);
    }

    /** 平台默认端点无库级覆盖时的展示名 */
    public String display() {
        return name == null || name.isBlank() ? (model == null ? provider : model) : name;
    }
}
