package com.devmind.knowledge.retrieve;

import com.devmind.common.knowledge.KnowledgeRetriever.DegradedReason;
import com.devmind.common.knowledge.KnowledgeRetriever.Detailed;
import com.devmind.common.knowledge.KnowledgeRetriever.KbOverview;
import com.devmind.common.knowledge.KnowledgeRetriever.RetrievedChunk;
import com.devmind.knowledge.embedding.EmbeddingResolver;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-84 检索 SPI：委托混合检索服务，保留块级返回形状；overview 行为不变。
 */
class KnowledgeRetrieverImplTest {

    private KnowledgeSearchService searchService;
    private KnowledgeEntryRepository entryRepo;
    private KnowledgeBaseRepository kbRepo;
    private EmbeddingResolver resolver;
    private KnowledgeRetrieverImpl retriever;

    @BeforeEach
    void setUp() {
        searchService = mock(KnowledgeSearchService.class);
        entryRepo = mock(KnowledgeEntryRepository.class);
        kbRepo = mock(KnowledgeBaseRepository.class);
        resolver = mock(EmbeddingResolver.class);
        lenient().when(resolver.anyConfigured()).thenReturn(true);
        retriever = new KnowledgeRetrieverImpl(searchService, entryRepo, kbRepo, resolver);
    }

    private KnowledgeSearchService.ChunkHit hit(long entryId, long kbId, String name,
                                                 String content, double score) {
        return new KnowledgeSearchService.ChunkHit(100 + entryId, entryId, kbId, score, name,
                content, "部署 > 回滚", 0, 10, 200, "摘要", "MAINT", "maint/runbook.md",
                "maint/runbook.md", "运维,部署", Instant.now());
    }

    @Test
    void mapsFusedChunksToSpiShape() {
        when(searchService.fusedChunks("回滚", List.of(10L), null, null, null, 8))
                .thenReturn(new KnowledgeSearchService.Fused(
                        List.of(hit(1, 10, "发布手册", "回滚步骤：先摘流量", 0.03)),
                        true, DegradedReason.NONE));

        List<RetrievedChunk> hits = retriever.retrieve(List.of(10L), "回滚", 8);

        assertEquals(1, hits.size());
        assertEquals("发布手册", hits.get(0).entryName());
        assertEquals("回滚步骤：先摘流量", hits.get(0).content());
        assertEquals(0.03, hits.get(0).score());
        assertEquals(101L, hits.get(0).chunkId(), "引用可定位到具体分块");
        assertEquals("maint/runbook.md", hits.get(0).sourceUri());
        assertEquals("部署 > 回滚", hits.get(0).headingPath());
        assertTrue(hits.get(0).snippet().contains("回滚步骤"), "引用片段用于列表展示");
    }

    @Test
    void baseNamesMapsIdsForEvidenceHint() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(10L);
        kb.setName("研发规范库");
        when(kbRepo.findAllById(List.of(10L))).thenReturn(List.of(kb));

        assertEquals(java.util.Map.of(10L, "研发规范库"), retriever.baseNames(List.of(10L)));
        assertTrue(retriever.baseNames(List.of()).isEmpty());
    }

    @Test
    void bm25OnlySearchStillReturnsHitsWithoutEmbedding() {
        when(searchService.fusedChunks(any(), anyList(), any(), any(), any(), eq(8)))
                .thenReturn(new KnowledgeSearchService.Fused(
                        List.of(hit(1, 10, "发布手册", "回滚步骤", 0.016)),
                        false, DegradedReason.NO_EMBEDDING));
        when(resolver.anyConfigured()).thenReturn(false);

        Detailed result = retriever.retrieveDetailed(List.of(10L), "回滚", 8);

        assertEquals(1, result.chunks().size(), "BM25 不依赖向量端点");
        assertEquals(DegradedReason.NO_EMBEDDING, result.degradedReason());
        assertTrue(!result.vector());
    }

    @Test
    void dimensionMismatchIsExposedForRebuildGuidance() {
        when(searchService.fusedChunks(any(), anyList(), any(), any(), any(), anyInt()))
                .thenReturn(new KnowledgeSearchService.Fused(List.of(), true,
                        DegradedReason.DIMENSION_MISMATCH));

        Detailed result = retriever.retrieveDetailed(List.of(10L), "回滚", 8);

        assertEquals(DegradedReason.DIMENSION_MISMATCH, result.degradedReason());
        assertTrue(result.vector(), "仍然走了向量通道");
    }

    @Test
    void filteredRetrievalPassesDocTypesAndTags() {
        when(searchService.fusedChunks(eq("回滚"), eq(List.of(10L)), eq(List.of("MAINT")),
                isNull(), eq(List.of("部署", "运维")), eq(5)))
                .thenReturn(new KnowledgeSearchService.Fused(
                        List.of(hit(1, 10, "发布手册", "回滚步骤", 0.03)), false, DegradedReason.NONE));

        Detailed result = retriever.retrieveDetailed(List.of(10L), "回滚", 5,
                List.of("MAINT"), List.of("部署", "运维"));

        assertEquals(1, result.chunks().size());
        verify(searchService).fusedChunks("回滚", List.of(10L), List.of("MAINT"),
                null, List.of("部署", "运维"), 5);
    }

    @Test
    void emptyInputShortCircuits() {
        assertEquals(List.of(), retriever.retrieve(List.of(), "q", 8));
        assertEquals(List.of(), retriever.retrieve(List.of(10L), " ", 8));
        verify(searchService, never()).fusedChunks(any(), anyList(), any(), any(), any(), anyInt());
    }

    @Test
    void overviewOfActiveKbListsEntryNames() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setName("研发规范库");
        kb.setDescription("团队研发规范");
        kb.setStatus(KnowledgeBaseEntity.STATUS_ACTIVE);
        when(kbRepo.findById(10L)).thenReturn(Optional.of(kb));
        KnowledgeEntryEntity e1 = new KnowledgeEntryEntity();
        e1.setName("新条目");
        KnowledgeEntryEntity e2 = new KnowledgeEntryEntity();
        e2.setName("旧条目");
        when(entryRepo.findByKbIdAndStatusOrderByCreatedAtDesc(10L, "active"))
                .thenReturn(List.of(e1, e2));

        Optional<KbOverview> overview = retriever.overview(10L);

        assertTrue(overview.isPresent());
        assertEquals("研发规范库", overview.get().name());
        assertEquals("团队研发规范", overview.get().description());
        assertEquals(List.of("新条目", "旧条目"), overview.get().entryNames());
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
