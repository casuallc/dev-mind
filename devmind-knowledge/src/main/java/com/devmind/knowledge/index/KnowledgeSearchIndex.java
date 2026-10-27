package com.devmind.knowledge.index;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * CAP-84 FR-09 检索索引 SPI：默认 Lucene 实现；未来可加 pgvector/独立检索服务实现，
 * 消费方只依赖本接口。
 */
public interface KnowledgeSearchIndex {

    /** 覆盖写入一个条目的全部分块（先删旧文档再写新）。vectors 可为 null 或含 null 元素。 */
    void upsert(EntryMeta entry, List<Chunk> chunks, List<float[]> vectors);

    void deleteEntry(long entryId);

    void deleteAll();

    /** 提交并刷新 searcher（批量导入结束后调用）。 */
    void flush();

    List<RankedHit> searchText(Filter filter, String query, int topK);

    List<RankedHit> searchVector(Filter filter, float[] queryVector, int topK);

    /** 索引中实际存在的向量维度（Lucene 字段 vec_<dim>）。 */
    Set<Integer> vectorDimensions();

    long numDocs();

    /** 索引体积与 schema（运维统计用）；默认实现只给文档数与维度，老实现无需改动。 */
    default IndexStats stats() {
        return new IndexStats(numDocs(), -1L, null, vectorDimensions());
    }

    record IndexStats(long docs, long sizeBytes, String schemaVersion, Set<Integer> vectorDimensions) {
    }

    record EntryMeta(long entryId, long kbId, String docType, String status,
                     List<String> tags, String title, String sourceUri, Instant validFrom, Instant validTo,
                     Instant updatedAt) {
    }

    record Chunk(long chunkId, String headingPath, String content) {
    }

    /** tags 多选为 OR（命中任一标签即可）；空/null = 不按标签过滤。CAP-94 起无 projectId 维度。 */
    record Filter(List<Long> kbIds, List<String> docTypes, List<String> tags) {
    }

    record RankedHit(long chunkId, long entryId, long kbId, double score) {
    }
}
