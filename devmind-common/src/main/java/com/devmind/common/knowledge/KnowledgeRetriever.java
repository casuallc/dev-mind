package com.devmind.common.knowledge;

import java.util.List;
import java.util.Optional;

/**
 * CAP-44 知识库检索 SPI：devmind-knowledge 实现，消费方（CAP-46 会话每轮注入、
 * 前端检索测试）以 {@code ObjectProvider<KnowledgeRetriever>} 探测注入，未装配时自行降级。
 *
 * <p>embedding 未配置时实现侧降级 LIKE 关键词检索（score=0），调用方无需分叉；
 * 可用 {@link #vectorAvailable()} 探测以便 UI 明示降级。</p>
 */
public interface KnowledgeRetriever {

    /** 是否走向量检索（embedding 已配置）；false = 实现内部降级 LIKE */
    boolean vectorAvailable();

    /**
     * 在指定知识库集合内检索与 query 相关的内容块。
     *
     * @param kbIds 目标知识库；空 → 空结果
     * @param topK  ≤0 由实现取配置默认
     * @return 按相关度降序，最多 topK 条；无命中返回空列表（不抛异常，检索失败按无命中降级）
     */
    List<RetrievedChunk> retrieve(List<Long> kbIds, String query, int topK);

    /**
     * CAP-46 FR-02：库概览（会话启动注入用）。库不存在/已归档 → empty。
     *
     * @param entryNames 库内 active 条目名（创建时间倒序，实现侧截断上限）
     */
    Optional<KbOverview> overview(long kbId);

    /**
     * @param score 向量余弦相似度；LIKE 降级命中为 0
     */
    record RetrievedChunk(Long entryId, String entryName, Long kbId, String content, double score) {
    }

    record KbOverview(String name, String description, String injectMode, List<String> entryNames) {
    }
}
