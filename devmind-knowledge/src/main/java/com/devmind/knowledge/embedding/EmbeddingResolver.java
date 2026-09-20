package com.devmind.knowledge.embedding;

/**
 * CAP-48 FR-04：按知识库解析出"这次该用哪个 embedding 端点"。
 *
 * <p>独立成接口是为了让索引/检索两条链路都能被单测直接给一个确定的解析结果，
 * 而不必拉起 Spring 去装配 {@code ModelEndpointProvider}。</p>
 */
public interface EmbeddingResolver {

    /**
     * 解析某库应使用的端点。
     *
     * @param kbEndpointId 库级覆盖端点 ID（{@code knowledge_bases.model_endpoint_id}），null = 平台默认
     * @return 永不为 null；无可用端点时返回 {@link Resolution#available()} 为 false 的降级结果
     */
    Resolution resolve(Long kbEndpointId);

    /** 是否存在任何可用端点（启动清扫是否值得跑的判据） */
    boolean anyConfigured();

    /**
     * 一次解析的全部结论。
     *
     * @param endpointId 端点 ID（走 CAP-44 旧全局配置时为 null——那不是端点资源，没有 ID）
     * @param dimensions 端点记录的向量维度（连接测试实测产物）；null = 尚未探测过
     * @param threshold  余弦阈值（端点覆盖优先，空回落平台默认）
     * @param topK       检索条数（端点覆盖优先，空回落平台默认）
     * @param client     实际调用用的客户端；null = 无可用端点
     */
    record Resolution(Long endpointId, String model, Integer dimensions, double threshold, int topK,
                      EmbeddingClient client) {

        /** 无可用端点：索引标 disabled、检索降级 LIKE（不 5xx、不静默空结果） */
        public static Resolution unavailable() {
            return new Resolution(null, "", null, 0, 0, null);
        }

        public boolean available() {
            return client != null;
        }
    }
}
