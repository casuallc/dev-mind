package com.devmind.knowledge.index;

import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.embedding.EmbeddingClient;
import com.devmind.knowledge.embedding.EmbeddingConfig;
import com.devmind.knowledge.embedding.EmbeddingException;
import com.devmind.knowledge.embedding.EmbeddingResolver;
import com.devmind.knowledge.embedding.MockEmbeddingClient;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeChunkEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeChunkRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-84 摄入管线：结构化分块落 DB、向量可选、Lucene upsert 始终发生。
 * 无端点/embedding 失败只降级向量（disabled），文本仍可 BM25 检索。
 */
class KnowledgeIndexServiceTest {

    private static final String CONTENT = """
            # 部署

            """ + "部署前先检查环境变量、数据库连接与磁盘余量，确认版本号一致后再执行发布脚本。".repeat(4) + """

            # 回滚

            """ + "回滚时先摘流量，再切换到上一个稳定版本，确认健康检查通过后再恢复流量。".repeat(4);

    private KnowledgeEntryRepository entryRepo;
    private KnowledgeBaseRepository kbRepo;
    private KnowledgeChunkRepository chunkRepo;
    private EmbeddingClient embeddingClient;
    private EmbeddingResolver resolver;
    private KnowledgeSearchIndex searchIndex;
    private KnowledgeIndexService service;
    private KnowledgeEntryEntity entry;
    private final AtomicLong chunkSeq = new AtomicLong();

    @BeforeEach
    void setUp() {
        entryRepo = mock(KnowledgeEntryRepository.class);
        kbRepo = mock(KnowledgeBaseRepository.class);
        chunkRepo = mock(KnowledgeChunkRepository.class);
        embeddingClient = mock(EmbeddingClient.class);
        resolver = mock(EmbeddingResolver.class);
        searchIndex = mock(KnowledgeSearchIndex.class);
        KnowledgeProperties props = new KnowledgeProperties();
        props.getEmbedding().setChunkSize(160);
        props.getEmbedding().setChunkOverlap(20);
        service = new KnowledgeIndexService(entryRepo, kbRepo, chunkRepo, resolver,
                new KnowledgeIndexWriter(entryRepo, chunkRepo), searchIndex, props);

        entry = new KnowledgeEntryEntity();
        entry.setId(7L);
        entry.setKbId(3L);
        entry.setName("构建规范");
        entry.setDocType(KnowledgeEntryEntity.DOC_MAINT);
        entry.setStatus("active");
        entry.setContentMd(CONTENT);
        entry.setIndexStatus(KnowledgeEntryEntity.INDEX_PENDING);
        lenient().when(entryRepo.findById(7L)).thenReturn(Optional.of(entry));
        lenient().when(entryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(chunkRepo.saveAll(anyList())).thenAnswer(inv -> {
            List<KnowledgeChunkEntity> list = inv.getArgument(0);
            list.forEach(c -> c.setId(chunkSeq.incrementAndGet()));
            return list;
        });
        lenient().when(embeddingClient.available()).thenReturn(true);
        lenient().when(embeddingClient.embed(anyList())).thenAnswer(inv -> {
            MockEmbeddingClient mock = new MockEmbeddingClient(16);
            return mock.embed(inv.getArgument(0));
        });
        lenient().when(embeddingClient.model()).thenReturn("mock-embedding");
        lenient().when(resolver.anyConfigured()).thenReturn(true);
        lenient().when(resolver.resolve(any())).thenReturn(
                new EmbeddingResolver.Resolution(11L, "mock-embedding", 16, 0.15, 8, embeddingClient));
    }

    @Test
    void indexesStructuredChunksWithoutEmbeddingColumn() {
        service.indexEntry(7L);

        verify(chunkRepo).deleteByEntryId(7L);
        ArgumentCaptor<List<KnowledgeChunkEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepo).saveAll(captor.capture());
        List<KnowledgeChunkEntity> chunks = captor.getValue();
        assertTrue(chunks.size() >= 2, "两个标题段落应切成多块");
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunkEntity c = chunks.get(i);
            assertEquals(3L, c.getKbId());
            assertEquals(7L, c.getEntryId());
            assertEquals(i, c.getChunkIndex());
            assertNull(c.getEmbedding(), "CAP-84 起向量不再写 DB CLOB");
            assertNotNull(c.getChunkHash());
            assertNotNull(c.getHeadingPath());
            assertEquals(c.getContent().length(), c.getTokenCount());
        }
        assertEquals(KnowledgeEntryEntity.INDEX_READY, entry.getIndexStatus());
        assertNull(entry.getIndexError());

        ArgumentCaptor<List<float[]>> vectors = ArgumentCaptor.forClass(List.class);
        verify(searchIndex).upsert(any(), anyList(), vectors.capture());
        assertEquals(chunks.size(), vectors.getValue().size(), "每个块都有对应向量");
        assertEquals(16, vectors.getValue().get(0).length);
    }

    @Test
    void recordsIndexLineageForMismatchDiagnosis() {
        service.indexEntry(7L);

        assertEquals(11L, entry.getIndexedEndpointId(), "血缘要记是哪个端点建的索引");
        assertEquals("mock-embedding", entry.getIndexedModel());
        assertEquals(16, entry.getIndexedDimensions(), "维度记实测向量长度");
    }

    @Test
    void resolvesKbLevelEndpointOverride() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(3L);
        kb.setModelEndpointId(11L);
        when(kbRepo.findById(3L)).thenReturn(Optional.of(kb));

        service.indexEntry(7L);

        verify(resolver).resolve(11L);
    }

    @Test
    void noEndpointStillIndexesBm25() {
        when(resolver.resolve(any())).thenReturn(EmbeddingResolver.Resolution.unavailable());

        service.indexEntry(7L);

        assertEquals(KnowledgeEntryEntity.INDEX_DISABLED, entry.getIndexStatus(), "向量不可用标 disabled");
        verify(chunkRepo).deleteByEntryId(7L);
        ArgumentCaptor<List<float[]>> vectors = ArgumentCaptor.forClass(List.class);
        verify(searchIndex).upsert(any(), anyList(), vectors.capture());
        assertTrue(vectors.getValue().isEmpty(), "无端点时 Lucene 只写 BM25 文档");
        verify(embeddingClient, never()).embed(anyList());
    }

    @Test
    void embeddingFailureDegradesToBm25InsteadOfFailing() {
        when(embeddingClient.embed(anyList()))
                .thenThrow(new EmbeddingException("embedding 端点返回 500: boom"));

        service.indexEntry(7L);

        assertEquals(KnowledgeEntryEntity.INDEX_DISABLED, entry.getIndexStatus());
        assertTrue(entry.getIndexError().contains("500"), "降级原因落 index_error");
        verify(chunkRepo).saveAll(anyList());
        verify(searchIndex).upsert(any(), anyList(), anyList());
    }

    @Test
    void luceneWriteFailureMarksFailed() {
        org.mockito.Mockito.doThrow(new IllegalStateException("index dir locked"))
                .when(searchIndex).upsert(any(), anyList(), anyList());

        service.indexEntry(7L);

        assertEquals(KnowledgeEntryEntity.INDEX_FAILED, entry.getIndexStatus());
        assertTrue(entry.getIndexError().contains("locked"));
    }

    @Test
    void emptyContentMarksReadyAndClearsLuceneDocs() {
        entry.setContentMd("  ");

        service.indexEntry(7L);

        assertEquals(KnowledgeEntryEntity.INDEX_READY, entry.getIndexStatus());
        verify(chunkRepo).deleteByEntryId(7L);
        verify(chunkRepo, never()).saveAll(anyList());
        assertNull(entry.getIndexedDimensions(), "无分块时没有可记的维度");
        ArgumentCaptor<List<KnowledgeSearchIndex.Chunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(searchIndex).upsert(any(), chunks.capture(), anyList());
        assertTrue(chunks.getValue().isEmpty(), "空正文要删掉旧 Lucene 文档");
    }

    @Test
    void sweepOnlyWhenEndpointAvailable() {
        when(entryRepo.findByIndexStatusIn(anyList())).thenReturn(List.of(entry));

        when(resolver.anyConfigured()).thenReturn(false);
        service.sweepPending();
        verify(entryRepo, never()).findByIndexStatusIn(anyList());

        when(resolver.anyConfigured()).thenReturn(true);
        service.sweepPending();
        assertEquals(KnowledgeEntryEntity.INDEX_READY, entry.getIndexStatus(), "清扫应索引 pending 条目");
        verify(searchIndex, atLeastOnce()).flush();
    }

    @Test
    void rebuildAllPagesByKeysetAndFlushes() {
        KnowledgeEntryEntity second = new KnowledgeEntryEntity();
        second.setId(8L);
        second.setKbId(3L);
        second.setName("第二篇");
        second.setStatus("active");
        second.setContentMd(CONTENT);
        when(entryRepo.findTop100ByKbIdAndIdGreaterThanOrderByIdAsc(3L, 0L))
                .thenReturn(List.of(entry, second));
        when(entryRepo.findById(8L)).thenReturn(Optional.of(second));

        service.rebuildAll(3L, null);

        verify(entryRepo).findTop100ByKbIdAndIdGreaterThanOrderByIdAsc(3L, 0L);
        verify(searchIndex).flush();
        assertEquals(KnowledgeEntryEntity.INDEX_READY, entry.getIndexStatus());
        assertEquals(KnowledgeEntryEntity.INDEX_READY, second.getIndexStatus());
    }

    @Test
    void unavailableClientBean() {
        EmbeddingClient none = new EmbeddingConfig().embeddingClient(new KnowledgeProperties());
        org.junit.jupiter.api.Assertions.assertFalse(none.available());
        assertEquals("", none.model());
        org.junit.jupiter.api.Assertions.assertThrows(EmbeddingException.class,
                () -> none.embed(List.of("x")));
    }
}
