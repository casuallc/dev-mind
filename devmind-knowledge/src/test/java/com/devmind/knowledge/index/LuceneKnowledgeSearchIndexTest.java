package com.devmind.knowledge.index;

import com.devmind.knowledge.config.KnowledgeProperties;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.MMapDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Lucene 索引集成测试（临时目录）：BM25 中文检索、组合过滤、过期/废弃排除、
 * 向量 kNN、覆盖写与删除、重启保留、schema 版本失配自动清空。
 */
class LuceneKnowledgeSearchIndexTest {

    @TempDir
    Path tempDir;

    private KnowledgeProperties props;
    private LuceneKnowledgeSearchIndex index;

    @BeforeEach
    void setUp() {
        props = new KnowledgeProperties();
        props.getIndex().setPath(tempDir.resolve("index").toString());
        props.getIndex().setRamBufferMb(16);
        props.getIndex().setCommitEveryChunks(200);
        index = new LuceneKnowledgeSearchIndex(props);
        index.init();
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    @Test
    void textSearchMatchesChineseContentAndRespectsFilters() {
        index.upsert(meta(1, 1, "p1", "MAINT", List.of("ops", "deploy"), "部署手册"),
                List.of(chunk(101, "部署", "发布前检查环境变量与磁盘余量，然后执行发布脚本。")), null);
        index.upsert(meta(2, 1, "p2", "TECH", List.of("db"), "数据库备份"),
                List.of(chunk(102, "备份", "每日凌晨执行 pg_dump 备份，保留七天。")), null);
        index.upsert(meta(3, 2, "p1", "FAQ", List.of("deploy"), "FAQ 回滚"),
                List.of(chunk(103, "回滚", "回滚失败怎么办？先摘流量再切换版本。")), null);
        index.flush();

        assertEquals(1L, only(index.searchText(null, "部署", 10)).entryId(), "CJK 大词匹配标题");
        assertEquals(2L, only(index.searchText(null, "数据库", 10)).entryId());

        assertEquals(1L, only(index.searchText(new KnowledgeSearchIndex.Filter(
                List.of(1L), null, null, null), "发布", 10)).entryId(), "kbIds 过滤");
        assertEquals(2L, only(index.searchText(new KnowledgeSearchIndex.Filter(
                null, List.of("tech"), null, null), "备份", 10)).entryId(), "docType 大小写不敏感");
        assertEquals(3L, only(index.searchText(new KnowledgeSearchIndex.Filter(
                null, null, "p1", null), "回滚", 10)).entryId(), "projectId 过滤");
        assertEquals(1L, only(index.searchText(new KnowledgeSearchIndex.Filter(
                null, null, null, List.of("Deploy")), "发布", 10)).entryId(), "tag 大小写不敏感");
        Set<Long> multiTag = index.searchText(new KnowledgeSearchIndex.Filter(
                        null, null, null, List.of("ops", "db")), "备份 发布", 10).stream()
                .map(KnowledgeSearchIndex.RankedHit::entryId)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(1L, 2L), multiTag, "多标签取并集");
    }

    @Test
    void pendingWritesAreVisibleWithoutExplicitFlush() {
        index.upsert(meta(1, 1, null, "TECH", null, "集群部署"),
                List.of(chunk(101, "节点", "RabbitMQ 集群建议至少三个节点。")), null);

        assertEquals(1, index.numDocs(), "少量写入未达 commit 阈值时，读前刷新也应看到新文档");
        assertEquals(1L, only(index.searchText(null, "集群", 10)).entryId());

        index.deleteEntry(1);
        assertEquals(0, index.numDocs(), "删除同样无需显式 flush 即可见");
    }

    @Test
    void excludesDeprecatedAndExpiredEntries() {
        index.upsert(meta(1, 1, null, "TECH", null, "回滚方案"),
                List.of(chunk(101, "回滚", "回滚时先摘流量，再切到上一个稳定版本。")), null);
        KnowledgeSearchIndex.EntryMeta deprecated = new KnowledgeSearchIndex.EntryMeta(
                2, 1, null, "TECH", "deprecated", null, "旧回滚方案", null, null, null, Instant.now());
        index.upsert(deprecated, List.of(chunk(102, "回滚", "回滚时直接重启。")), null);
        KnowledgeSearchIndex.EntryMeta expired = new KnowledgeSearchIndex.EntryMeta(
                3, 1, null, "TECH", "active", null, "过期回滚方案", null, null,
                Instant.now().minusSeconds(3600), Instant.now());
        index.upsert(expired, List.of(chunk(103, "回滚", "回滚时删库重建。")), null);
        KnowledgeSearchIndex.EntryMeta notYetValid = new KnowledgeSearchIndex.EntryMeta(
                4, 1, null, "TECH", "active", null, "未生效回滚方案", null,
                Instant.now().plusSeconds(3600), null, Instant.now());
        index.upsert(notYetValid, List.of(chunk(104, "回滚", "回滚时先演练。")), null);
        index.flush();

        List<KnowledgeSearchIndex.RankedHit> hits = index.searchText(null, "回滚", 10);
        assertEquals(List.of(1L), hits.stream().map(KnowledgeSearchIndex.RankedHit::entryId).toList(),
                "deprecated、validTo 已过期与 validFrom 未生效的条目都不参与检索");
    }

    @Test
    void vectorSearchRanksByCosineSimilarityAndTracksDimensions() {
        index.upsert(meta(1, 1, null, "TECH", null, "缓存设计"),
                List.of(chunk(101, "缓存", "使用本地缓存减少数据库压力。")),
                List.of(new float[]{1f, 0f, 0f, 0f}));
        index.upsert(meta(2, 1, null, "TECH", null, "消息队列"),
                List.of(chunk(102, "队列", "异步任务通过消息队列削峰。")),
                List.of(new float[]{0f, 1f, 0f, 0f}));
        index.flush();

        assertEquals(Set.of(4), index.vectorDimensions());

        List<KnowledgeSearchIndex.RankedHit> hits = index.searchVector(null, new float[]{0.9f, 0.1f, 0f, 0f}, 5);
        assertEquals(1L, hits.get(0).entryId(), "最相近的向量排第一");
        assertEquals(101L, hits.get(0).chunkId());

        List<KnowledgeSearchIndex.RankedHit> filtered = index.searchVector(
                new KnowledgeSearchIndex.Filter(List.of(1L), List.of("TECH"), null, null),
                new float[]{0.9f, 0.1f, 0f, 0f}, 5);
        assertEquals(1L, filtered.get(0).entryId());

        assertTrue(index.searchVector(null, new float[]{1f, 0f, 0f}, 5).isEmpty(),
                "未建索引的维度直接返回空，避免 Lucene 维度异常");
    }

    @Test
    void upsertReplacesAllPreviousChunksAndDeleteRemovesEntry() {
        index.upsert(meta(1, 1, null, "TECH", null, "构建规范"),
                List.of(chunk(101, "旧一", "旧版本缓存预热说明"), chunk(102, "旧二", "旧版本日志轮转说明")), null);
        index.flush();
        assertEquals(2, index.numDocs());

        index.upsert(meta(1, 1, null, "TECH", null, "构建规范"),
                List.of(chunk(103, "新一", "新版本队列削峰说明")), null);
        index.flush();

        assertEquals(1, index.numDocs(), "同一 entry 重复 upsert 先删旧文档");
        assertTrue(index.searchText(null, "缓存预热", 10).isEmpty());
        assertEquals(1L, only(index.searchText(null, "队列削峰", 10)).entryId());

        index.deleteEntry(1);
        index.flush();
        assertEquals(0, index.numDocs());
    }

    @Test
    void reopenKeepsCommittedDocsAndVectorDimensions() {
        index.upsert(meta(1, 1, null, "TECH", null, "部署手册"),
                List.of(chunk(101, "部署", "发布前检查环境变量。")),
                List.of(new float[]{1f, 0f, 0f, 0f}));
        index.flush();
        index.close();

        index = new LuceneKnowledgeSearchIndex(props);
        index.init();

        assertEquals(1, index.numDocs());
        assertTrue(index.vectorDimensions().contains(4), "重启后从字段信息恢复向量维度");
        assertEquals(1L, only(index.searchText(null, "部署", 10)).entryId());
    }

    @Test
    void reopenClearsIndexWhenSchemaVersionMismatches() throws Exception {
        index.upsert(meta(1, 1, null, "TECH", null, "部署手册"),
                List.of(chunk(101, "部署", "发布前检查环境变量。")), null);
        index.flush();
        index.close();

        try (MMapDirectory dir = new MMapDirectory(tempDir.resolve("index"));
             IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new CJKAnalyzer()))) {
            writer.setLiveCommitData(Map.of("schemaVersion", "0").entrySet());
            writer.commit();
        }

        index = new LuceneKnowledgeSearchIndex(props);
        index.init();

        assertEquals(0, index.numDocs(), "schema 版本不匹配时清空旧索引，等待 DB 重建");
        assertTrue(index.searchText(null, "部署", 10).isEmpty());
        assertFalse(index.vectorDimensions().contains(4));
    }

    private static KnowledgeSearchIndex.EntryMeta meta(long entryId, long kbId, String projectId,
            String docType, List<String> tags, String title) {
        return new KnowledgeSearchIndex.EntryMeta(entryId, kbId, projectId, docType, "active",
                tags, title, null, null, null, Instant.now());
    }

    private static KnowledgeSearchIndex.Chunk chunk(long chunkId, String heading, String content) {
        return new KnowledgeSearchIndex.Chunk(chunkId, heading, content);
    }

    private static KnowledgeSearchIndex.RankedHit only(List<KnowledgeSearchIndex.RankedHit> hits) {
        assertEquals(1, hits.size(), "期望恰好命中 1 条: " + hits);
        return hits.get(0);
    }
}
