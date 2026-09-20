package com.devmind.knowledge.retrieve;

import com.devmind.common.knowledge.KnowledgeRetriever.DegradedReason;
import com.devmind.common.knowledge.KnowledgeRetriever.Detailed;
import com.devmind.common.knowledge.KnowledgeRetriever.KbOverview;
import com.devmind.common.knowledge.KnowledgeRetriever.RetrievedChunk;
import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.embedding.EmbeddingClient;
import com.devmind.knowledge.embedding.EmbeddingException;
import com.devmind.knowledge.embedding.EmbeddingResolver;
import com.devmind.knowledge.embedding.MockEmbeddingClient;
import com.devmind.knowledge.embedding.VectorJson;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeChunkEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeChunkRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeRetrieverImpl（CAP-44 FR-06 + CAP-48 按端点分组/降级诊断）：向量路径排序/阈值/topK/
 * active 过滤，无端点 LIKE 降级，异常按无命中降级，维度失配显式报 DIMENSION_MISMATCH。
 */
class KnowledgeRetrieverImplTest {

    // 64 维降低哈希碰撞（16 维下无关文本碰撞余弦会误过 0.15 阈值）
    private static final MockEmbeddingClient MOCK = new MockEmbeddingClient(64);

    private KnowledgeChunkRepository chunkRepo;
    private KnowledgeEntryRepository entryRepo;
    private KnowledgeBaseRepository kbRepo;
    private EmbeddingClient embeddingClient;
    private EmbeddingResolver resolver;
    private KnowledgeProperties props;
    private KnowledgeRetrieverImpl retriever;

    @BeforeEach
    void setUp() {
        chunkRepo = mock(KnowledgeChunkRepository.class);
        entryRepo = mock(KnowledgeEntryRepository.class);
        kbRepo = mock(KnowledgeBaseRepository.class);
        embeddingClient = mock(EmbeddingClient.class);
        resolver = mock(EmbeddingResolver.class);
        props = new KnowledgeProperties();
        lenient().when(embeddingClient.available()).thenReturn(true);
        lenient().when(embeddingClient.model()).thenReturn("mock-embedding");
        lenient().when(embeddingClient.embed(anyList()))
                .thenAnswer(inv -> MOCK.embed(inv.getArgument(0)));
        lenient().when(resolver.anyConfigured()).thenReturn(true);
        lenient().when(resolver.resolve(any())).thenReturn(resolution(11L, 64, 0.15));
        retriever = new KnowledgeRetrieverImpl(chunkRepo, entryRepo, kbRepo, resolver, props);
    }

    private EmbeddingResolver.Resolution resolution(Long endpointId, Integer dimensions, double threshold) {
        return new EmbeddingResolver.Resolution(endpointId, "mock-embedding", dimensions, threshold,
                props.getEmbedding().getTopK(), embeddingClient);
    }

    private void unavailable() {
        lenient().when(resolver.anyConfigured()).thenReturn(false);
        lenient().when(resolver.resolve(any())).thenReturn(EmbeddingResolver.Resolution.unavailable());
    }

    private KnowledgeEntryEntity entry(long id, long kbId, String name, String status) {
        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        e.setId(id);
        e.setKbId(kbId);
        e.setName(name);
        e.setStatus(status);
        e.setContentMd("content-" + name);
        return e;
    }

    private KnowledgeChunkEntity chunk(long entryId, long kbId, int idx, String content) {
        return chunk(entryId, kbId, idx, content, 64);
    }

    private KnowledgeChunkEntity chunk(long entryId, long kbId, int idx, String content, int dims) {
        KnowledgeChunkEntity c = new KnowledgeChunkEntity();
        c.setEntryId(entryId);
        c.setKbId(kbId);
        c.setChunkIndex(idx);
        c.setContent(content);
        c.setEmbedding(VectorJson.toJson(new MockEmbeddingClient(dims).embed(List.of(content)).get(0)));
        return c;
    }

    @Test
    void vectorRetrieveRanksAndFilters() {
        // 测试侧阈值提到 0.5：mock 哈希向量无关文本碰撞分可达 ~0.15（真实 embedding 不存在此问题），
        // 本用例验证排序/过滤逻辑而非 mock 向量质量
        lenient().when(resolver.resolve(any())).thenReturn(resolution(11L, 64, 0.5));

        List<KnowledgeChunkEntity> chunks = List.of(
                chunk(1, 10, 0, "前端构建规范与产物说明"),
                chunk(1, 10, 1, "数据库备份策略恢复演练流程"),
                chunk(2, 10, 0, "前端构建规范"),       // deprecated 条目
                chunk(3, 20, 0, "前端构建规范细则"));   // 其他库
        when(chunkRepo.findByKbIdIn(List.of(10L))).thenReturn(
                chunks.stream().filter(c -> c.getKbId() == 10L).toList());
        when(entryRepo.findAllById(anyList())).thenReturn(List.of(
                entry(1, 10, "构建规范", "active"),
                entry(2, 10, "废弃规范", "deprecated")));

        List<RetrievedChunk> hits = retriever.retrieve(List.of(10L), "前端构建规范", 8);

        assertEquals(1, hits.size(), "只命中相关块：无关块过阈值、deprecated 条目剔除");
        assertEquals("构建规范", hits.get(0).entryName());
        assertEquals("前端构建规范与产物说明", hits.get(0).content());
        assertTrue(hits.get(0).score() >= 0.5, "命中分应过阈值");
    }

    @Test
    void vectorRetrieveRespectsTopK() {
        when(chunkRepo.findByKbIdIn(List.of(10L))).thenReturn(List.of(
                chunk(1, 10, 0, "前端构建规范与产物"),
                chunk(1, 10, 1, "前端构建规范")));
        when(entryRepo.findAllById(anyList())).thenReturn(List.of(entry(1, 10, "规范", "active")));

        List<RetrievedChunk> hits = retriever.retrieve(List.of(10L), "前端构建规范", 1);

        assertEquals(1, hits.size(), "topK=1 只留最高分");
        assertEquals("前端构建规范", hits.get(0).content(), "与查询完全相同的块分最高");
    }

    @Test
    void reportsDimensionMismatchInsteadOfSilentEmptyResult() {
        // 库内向量是 64 维（旧端点建的），当前端点返回 16 维：余弦恒 0、命中被阈值全过滤，
        // 旧行为只表现为"搜不到"；现在必须显式告诉用户去重建索引
        when(chunkRepo.findByKbIdIn(List.of(10L))).thenReturn(List.of(chunk(1, 10, 0, "前端构建规范", 64)));
        when(entryRepo.findAllById(anyList())).thenReturn(List.of(entry(1, 10, "规范", "active")));
        lenient().when(resolver.resolve(any())).thenReturn(resolution(11L, 16, 0.15));
        lenient().when(embeddingClient.embed(anyList()))
                .thenAnswer(inv -> new MockEmbeddingClient(16).embed(inv.getArgument(0)));

        Detailed result = retriever.retrieveDetailed(List.of(10L), "前端构建规范", 8);

        assertTrue(result.chunks().isEmpty(), "维度不等时不应有命中");
        assertEquals(DegradedReason.DIMENSION_MISMATCH, result.degradedReason());
        assertTrue(result.vector(), "仍然是走了向量通道的");
    }

    @Test
    void healthyVectorRetrieveReportsNoDegradation() {
        when(chunkRepo.findByKbIdIn(List.of(10L))).thenReturn(List.of(chunk(1, 10, 0, "前端构建规范")));
        when(entryRepo.findAllById(anyList())).thenReturn(List.of(entry(1, 10, "规范", "active")));

        Detailed result = retriever.retrieveDetailed(List.of(10L), "前端构建规范", 8);

        assertEquals(DegradedReason.NONE, result.degradedReason());
        assertTrue(result.vector());
    }

    @Test
    void eachKbUsesItsOwnEndpointThenMergesHits() {
        KnowledgeBaseEntity kb10 = new KnowledgeBaseEntity();
        kb10.setId(10L);
        kb10.setModelEndpointId(11L);
        KnowledgeBaseEntity kb20 = new KnowledgeBaseEntity();
        kb20.setId(20L);
        kb20.setModelEndpointId(22L);
        when(kbRepo.findAllById(List.of(10L, 20L))).thenReturn(List.of(kb10, kb20));
        // 22 号端点用另一个客户端（另一个模型）：查询要向两个端点各投一次
        EmbeddingClient other = mock(EmbeddingClient.class);
        lenient().when(other.available()).thenReturn(true);
        lenient().when(other.model()).thenReturn("other-model");
        lenient().when(other.embed(anyList())).thenAnswer(inv -> MOCK.embed(inv.getArgument(0)));
        when(resolver.resolve(11L)).thenReturn(resolution(11L, 64, 0.15));
        when(resolver.resolve(22L)).thenReturn(new EmbeddingResolver.Resolution(
                22L, "other-model", 64, 0.15, 8, other));
        when(chunkRepo.findByKbIdIn(List.of(10L))).thenReturn(List.of(chunk(1, 10, 0, "前端构建规范")));
        when(chunkRepo.findByKbIdIn(List.of(20L))).thenReturn(List.of(chunk(2, 20, 0, "前端构建规范")));
        when(entryRepo.findAllById(anyList())).thenReturn(List.of(
                entry(1, 10, "A 库条目", "active"), entry(2, 20, "B 库条目", "active")));

        List<RetrievedChunk> hits = retriever.retrieve(List.of(10L, 20L), "前端构建规范", 8);

        verify(other).embed(anyList());
        assertEquals(2, hits.size(), "两个库各自的命中合并返回");
    }

    @Test
    void fallsBackToLikeWhenNoEndpoint() {
        unavailable();
        KnowledgeEntryEntity e = entry(1, 10, "构建规范", "active");
        e.setContentMd("长".repeat(600));
        when(entryRepo.searchInBases(List.of(10L), "构建")).thenReturn(List.of(e));

        Detailed result = retriever.retrieveDetailed(List.of(10L), "构建", 8);

        assertEquals(1, result.chunks().size());
        assertEquals(0.0, result.chunks().get(0).score(), "LIKE 降级命中 score=0");
        assertEquals(KnowledgeRetrieverImpl.FALLBACK_CONTENT_LEN + 1,
                result.chunks().get(0).content().length(), "降级命中内容截断 500+省略号");
        assertTrue(result.chunks().get(0).content().endsWith("…"));
        assertEquals(DegradedReason.NO_EMBEDDING, result.degradedReason());
        assertTrue(!result.vector());
        verify(embeddingClient, never()).embed(anyList());
    }

    @Test
    void emptyInputShortCircuits() {
        assertEquals(List.of(), retriever.retrieve(List.of(), "q", 8));
        assertEquals(List.of(), retriever.retrieve(List.of(10L), " ", 8));
        verify(chunkRepo, never()).findByKbIdIn(anyList());
        verify(entryRepo, never()).searchInBases(anyList(), any());
    }

    @Test
    void embeddingFailureDegradesToEmpty() {
        when(embeddingClient.embed(anyList())).thenThrow(new EmbeddingException("boom"));

        assertEquals(List.of(), retriever.retrieve(List.of(10L), "q", 8), "检索异常按无命中降级");
    }

    @Test
    void overviewOfActiveKbListsEntryNames() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setName("研发规范库");
        kb.setDescription("团队研发规范");
        kb.setInjectMode("RAG");
        kb.setStatus(KnowledgeBaseEntity.STATUS_ACTIVE);
        when(kbRepo.findById(10L)).thenReturn(Optional.of(kb));
        when(entryRepo.findByKbIdAndStatusOrderByCreatedAtDesc(10L, "active")).thenReturn(List.of(
                entry(1, 10, "新条目", "active"),
                entry(2, 10, "旧条目", "active"),
                entry(3, 10, "废弃条目", "deprecated")));

        Optional<KbOverview> overview = retriever.overview(10L);

        assertTrue(overview.isPresent());
        assertEquals("研发规范库", overview.get().name());
        assertEquals("团队研发规范", overview.get().description());
        assertEquals("RAG", overview.get().injectMode());
        assertEquals(List.of("新条目", "旧条目", "废弃条目"), overview.get().entryNames(),
                "按创建时间倒序取回（截断由 SQL/实现侧 limit 处理，测试桩少于上限即全量）");
    }

    @Test
    void overviewOfMissingOrArchivedKbIsEmpty() {
        when(kbRepo.findById(anyLong())).thenReturn(Optional.empty());
        assertTrue(retriever.overview(99L).isEmpty(), "库不存在 → empty");

        KnowledgeBaseEntity archived = new KnowledgeBaseEntity();
        archived.setName("归档库");
        archived.setStatus("archived");
        when(kbRepo.findById(11L)).thenReturn(Optional.of(archived));
        assertTrue(retriever.overview(11L).isEmpty(), "已归档 → empty");
        verify(entryRepo, never()).findByKbIdAndStatusOrderByCreatedAtDesc(eq(11L), any());
    }
}
