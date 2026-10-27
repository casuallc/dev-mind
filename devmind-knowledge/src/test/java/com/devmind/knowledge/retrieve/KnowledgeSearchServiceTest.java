package com.devmind.knowledge.retrieve;

import com.devmind.common.knowledge.KnowledgeRetriever.DegradedReason;
import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.dto.KnowledgeSearchV2Request;
import com.devmind.knowledge.dto.KnowledgeSearchV2Response;
import com.devmind.knowledge.embedding.EmbeddingClient;
import com.devmind.knowledge.embedding.EmbeddingResolver;
import com.devmind.knowledge.index.KnowledgeSearchIndex;
import com.devmind.knowledge.index.KnowledgeSearchIndex.Filter;
import com.devmind.knowledge.index.KnowledgeSearchIndex.RankedHit;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeChunkEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeChunkRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-84 混合检索：BM25 单独可用、向量与 BM25 RRF 融合、文档级聚合、高亮片段与降级诊断。
 */
class KnowledgeSearchServiceTest {

    private KnowledgeSearchIndex searchIndex;
    private KnowledgeChunkRepository chunkRepo;
    private KnowledgeEntryRepository entryRepo;
    private KnowledgeBaseRepository kbRepo;
    private EmbeddingResolver resolver;
    private EmbeddingClient embeddingClient;
    private KnowledgeProperties props;
    private KnowledgeSearchService service;

    private final List<KnowledgeChunkEntity> chunks = new ArrayList<>();
    private final List<KnowledgeEntryEntity> entries = new ArrayList<>();

    @BeforeEach
    void setUp() {
        searchIndex = mock(KnowledgeSearchIndex.class);
        chunkRepo = mock(KnowledgeChunkRepository.class);
        entryRepo = mock(KnowledgeEntryRepository.class);
        kbRepo = mock(KnowledgeBaseRepository.class);
        resolver = mock(EmbeddingResolver.class);
        embeddingClient = mock(EmbeddingClient.class);
        props = new KnowledgeProperties();
        service = new KnowledgeSearchService(searchIndex, chunkRepo, entryRepo, kbRepo, resolver, props);

        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(10L);
        kb.setName("运维库");
        kb.setStatus(KnowledgeBaseEntity.STATUS_ACTIVE);
        lenient().when(kbRepo.findAllById(anyList())).thenReturn(List.of(kb));
        lenient().when(resolver.anyConfigured()).thenReturn(false);
        lenient().when(resolver.resolve(any())).thenReturn(EmbeddingResolver.Resolution.unavailable());
        lenient().when(searchIndex.vectorDimensions()).thenReturn(Set.of());
        lenient().when(searchIndex.searchText(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(searchIndex.searchVector(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(chunkRepo.findAllById(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return chunks.stream().filter(c -> ids.contains(c.getId())).toList();
        });
        lenient().when(entryRepo.findAllById(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return entries.stream().filter(e -> ids.contains(e.getId())).toList();
        });
    }

    private KnowledgeEntryEntity entry(long id, String name, String status) {
        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        e.setId(id);
        e.setKbId(10L);
        e.setName(name);
        e.setStatus(status);
        e.setDocType(KnowledgeEntryEntity.DOC_MAINT);
        e.setSummary("摘要-" + name);
        e.setUpdatedAt(Instant.now());
        entries.add(e);
        return e;
    }

    private KnowledgeChunkEntity chunk(long id, long entryId, String content) {
        KnowledgeChunkEntity c = new KnowledgeChunkEntity();
        c.setId(id);
        c.setEntryId(entryId);
        c.setKbId(10L);
        c.setContent(content);
        c.setHeadingPath("回滚");
        c.setStartOffset(0);
        c.setEndOffset(content.length());
        chunks.add(c);
        return c;
    }

    @Test
    void aggregatesChunksByDocumentAndHighlightsSnippet() {
        entry(1, "发布手册", "active");
        entry(2, "部署指南", "active");
        chunk(101, 1, "回滚步骤：先摘流量，再切换上一个稳定版本。");
        chunk(102, 1, "回滚完成后观察健康检查与错误率。");
        chunk(201, 2, "部署步骤：先检查环境变量。");
        when(searchIndex.searchText(any(), eq("回滚"), anyInt())).thenReturn(List.of(
                new RankedHit(101, 1, 10L, 5.0),
                new RankedHit(102, 1, 10L, 3.0),
                new RankedHit(201, 2, 10L, 1.0)));

        KnowledgeSearchV2Response result = service.searchDocuments(
                new KnowledgeSearchV2Request(List.of(10L), "回滚", null, null, 10));

        assertEquals(2, result.hits().size(), "同一文档的多个块聚合为一条");
        KnowledgeSearchV2Response.Hit top = result.hits().get(0);
        assertEquals("发布手册", top.title());
        assertEquals(2, top.chunkCount(), "命中两个块");
        assertTrue(top.snippet().contains("<mark>回滚</mark>"), "片段高亮命中词: " + top.snippet());
        assertTrue(top.score() > result.hits().get(1).score(), "块分累加后排序");
        assertFalse(result.vector());
        assertEquals(DegradedReason.NO_EMBEDDING.name(), result.degradedReason());
    }

    @Test
    void filtersDeprecatedAndExpiredDocuments() {
        entry(1, "有效文档", "active");
        KnowledgeEntryEntity expired = entry(2, "过期文档", "active");
        expired.setValidTo(Instant.now().minusSeconds(3600));
        entry(3, "废弃文档", "deprecated");
        chunk(101, 1, "回滚方法一");
        chunk(201, 2, "回滚方法二");
        chunk(301, 3, "回滚方法三");
        when(searchIndex.searchText(any(), eq("回滚"), anyInt())).thenReturn(List.of(
                new RankedHit(101, 1, 10L, 3.0),
                new RankedHit(201, 2, 10L, 2.0),
                new RankedHit(301, 3, 10L, 1.0)));

        KnowledgeSearchV2Response result = service.searchDocuments(
                new KnowledgeSearchV2Request(List.of(10L), "回滚", null, null, 10));

        assertEquals(1, result.hits().size());
        assertEquals("有效文档", result.hits().get(0).title());
    }

    @Test
    void fusesBm25AndVectorWithRrf() {
        entry(1, "BM25 命中", "active");
        entry(2, "向量命中", "active");
        chunk(101, 1, "回滚手册");
        chunk(201, 2, "恢复流程");
        when(searchIndex.searchText(any(), eq("回滚"), anyInt()))
                .thenReturn(List.of(new RankedHit(101, 1, 10L, 8.0)));
        when(resolver.anyConfigured()).thenReturn(true);
        when(resolver.resolve(any())).thenReturn(new EmbeddingResolver.Resolution(
                11L, "mock-embedding", 16, 0.15, 8, embeddingClient));
        when(embeddingClient.embed(anyList()))
                .thenReturn(List.of(new float[16]));
        when(searchIndex.vectorDimensions()).thenReturn(Set.of(16));
        when(searchIndex.searchVector(any(), any(), anyInt()))
                .thenReturn(List.of(new RankedHit(201, 2, 10L, 0.82)));

        KnowledgeSearchService.Fused fused = service.fusedChunks(
                "回滚", List.of(10L), null, null, 10);

        assertEquals(2, fused.chunks().size(), "两路召回都保留");
        assertTrue(fused.vector());
        assertEquals(DegradedReason.NONE, fused.reason());
        verify(searchIndex).searchVector(any(), any(), anyInt());
    }

    @Test
    void reportsDimensionMismatchButStillReturnsBm25Hits() {
        entry(1, "发布手册", "active");
        chunk(101, 1, "回滚手册");
        when(searchIndex.searchText(any(), eq("回滚"), anyInt()))
                .thenReturn(List.of(new RankedHit(101, 1, 10L, 8.0)));
        when(resolver.anyConfigured()).thenReturn(true);
        when(resolver.resolve(any())).thenReturn(new EmbeddingResolver.Resolution(
                11L, "new-model", 16, 0.15, 8, embeddingClient));
        when(embeddingClient.embed(anyList())).thenReturn(List.of(new float[16]));
        when(searchIndex.vectorDimensions()).thenReturn(Set.of(8));

        KnowledgeSearchService.Fused fused = service.fusedChunks(
                "回滚", List.of(10L), null, null, 10);

        assertEquals(1, fused.chunks().size(), "BM25 命中不受向量维度影响");
        assertEquals(DegradedReason.DIMENSION_MISMATCH, fused.reason());
        assertFalse(fused.vector());
    }

    @Test
    void noIndexedVectorsReportsNoEmbedding() {
        entry(1, "发布手册", "active");
        chunk(101, 1, "回滚手册");
        when(searchIndex.searchText(any(), eq("回滚"), anyInt()))
                .thenReturn(List.of(new RankedHit(101, 1, 10L, 8.0)));
        when(resolver.anyConfigured()).thenReturn(true);
        when(resolver.resolve(any())).thenReturn(new EmbeddingResolver.Resolution(
                11L, "mock", 16, 0.15, 8, embeddingClient));
        when(embeddingClient.embed(anyList())).thenReturn(List.of(new float[16]));
        when(searchIndex.vectorDimensions()).thenReturn(Set.of());

        KnowledgeSearchService.Fused fused = service.fusedChunks(
                "回滚", List.of(10L), null, null, 10);

        assertEquals(DegradedReason.NO_EMBEDDING, fused.reason());
        assertFalse(fused.vector());
    }

    @Test
    void snippetEscapesHtmlFromDocuments() {
        entry(1, "危险文档", "active");
        chunk(101, 1, "<script>alert(1)</script> 回滚步骤");
        when(searchIndex.searchText(any(), eq("回滚"), anyInt()))
                .thenReturn(List.of(new RankedHit(101, 1, 10L, 1.0)));

        KnowledgeSearchV2Response result = service.searchDocuments(
                new KnowledgeSearchV2Request(List.of(10L), "回滚", null, null, 5));

        String snippet = result.hits().get(0).snippet();
        assertTrue(snippet.contains("&lt;script&gt;"), snippet);
        assertFalse(snippet.contains("<script>"), "文档内容必须转义后再拼 <mark>");
        assertTrue(snippet.contains("<mark>回滚</mark>"), snippet);
    }

    @Test
    void passesDocTypeAndTagFiltersToIndex() {
        entry(1, "FAQ", "active");
        chunk(101, 1, "回滚常见问题");
        when(searchIndex.searchText(any(), eq("回滚"), anyInt()))
                .thenReturn(List.of(new RankedHit(101, 1, 10L, 1.0)));

        service.fusedChunks("回滚", List.of(10L), List.of("FAQ"), List.of("运维", "部署"), 5);

        org.mockito.ArgumentCaptor<Filter> captor = org.mockito.ArgumentCaptor.forClass(Filter.class);
        verify(searchIndex).searchText(captor.capture(), eq("回滚"), anyInt());
        assertEquals(List.of("FAQ"), captor.getValue().docTypes());
        assertEquals(List.of("运维", "部署"), captor.getValue().tags());
    }

    @Test
    void noBasesReturnsEmpty() {
        when(kbRepo.findAllById(anyList())).thenReturn(List.of());

        KnowledgeSearchService.Fused fused = service.fusedChunks(
                "回滚", List.of(99L), null, null, 5);

        assertTrue(fused.chunks().isEmpty());
        verify(searchIndex, org.mockito.Mockito.never()).searchText(any(), any(), anyInt());
    }
}
