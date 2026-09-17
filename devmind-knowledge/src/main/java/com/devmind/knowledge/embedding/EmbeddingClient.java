package com.devmind.knowledge.embedding;

import java.util.List;

/**
 * CAP-44 FR-05 文本向量化客户端。实现：OpenAI 兼容端点 / mock（确定性哈希向量）。
 * 未配置时装配 UnavailableEmbeddingClient（available()=false），索引标 disabled、检索降级 LIKE。
 */
public interface EmbeddingClient {

    /** 是否可用（已配置有效端点或 mock） */
    boolean available();

    /** 当前模型名（不可用时空串） */
    String model();

    /**
     * 批量向量化。返回与 texts 等长、同序的向量列表。
     * 远程调用失败抛 EmbeddingException（由调用方落 index_status=failed）。
     */
    List<float[]> embed(List<String> texts);
}
