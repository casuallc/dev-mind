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
     * CAP-48 FR-06：带降级原因的检索。默认实现按 {@link #vectorAvailable()} 推断，
     * 老实现无需改动；向量通道实现应覆盖以区分"没配 embedding"与"配了但维度对不上"——
     * 后者旧行为是静默 0 命中，用户只看到"搜不到"。
     */
    default Detailed retrieveDetailed(List<Long> kbIds, String query, int topK) {
        List<RetrievedChunk> chunks = retrieve(kbIds, query, topK);
        return vectorAvailable()
                ? new Detailed(chunks, true, DegradedReason.NONE)
                : new Detailed(chunks, false, DegradedReason.NO_EMBEDDING);
    }

    /** 检索降级原因（诊断用；NONE = 向量通道完全正常） */
    enum DegradedReason {
        /** 正常 */
        NONE,
        /** 未配置可用 embedding 端点：检索退化 LIKE 关键词 */
        NO_EMBEDDING,
        /** 端点维度与库内已建索引维度不一致：余弦恒 0，命中被阈值全部过滤 */
        DIMENSION_MISMATCH
    }

    /**
     * @param vector         本次是否走了向量通道（false = LIKE 降级）
     * @param degradedReason 降级原因，供 UI 明示与「重建索引」入口判定
     */
    record Detailed(List<RetrievedChunk> chunks, boolean vector, DegradedReason degradedReason) {
    }

    /**
     * @param score 向量余弦相似度；LIKE 降级命中为 0
     */
    record RetrievedChunk(Long entryId, String entryName, Long kbId, String content, double score) {
    }

    record KbOverview(String name, String description, String injectMode, List<String> entryNames) {
    }
}
