package com.devmind.knowledge.index;

import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.embedding.EmbeddingClient;
import com.devmind.knowledge.embedding.EmbeddingConfig;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeIndexService（CAP-44 FR-04 摄入管线 + CAP-48 端点解析/血缘，mock repo 不拉起 Spring）：
 * 成功 → 删旧写新 chunks + ready + 血缘；无可用端点 → disabled；调用失败 → failed+error；
 * 启动清扫只捞 pending+disabled。
 */
class KnowledgeIndexServiceTest {

    private KnowledgeEntryRepository entryRepo;
    private KnowledgeBaseRepository kbRepo;
    private KnowledgeChunkRepository chunkRepo;
    private EmbeddingClient embeddingClient;
    private EmbeddingResolver resolver;
    private KnowledgeIndexService service;
    private KnowledgeEntryEntity entry;

    @BeforeEach
    void setUp() {
        entryRepo = mock(KnowledgeEntryRepository.class);
        kbRepo = mock(KnowledgeBaseRepository.class);
        chunkRepo = mock(KnowledgeChunkRepository.class);
        embeddingClient = mock(EmbeddingClient.class);
        resolver = mock(EmbeddingResolver.class);
        KnowledgeProperties props = new KnowledgeProperties();
        props.getEmbedding().setChunkSize(20);
        props.getEmbedding().setChunkOverlap(5);
        // 真 writer + mock repo：落库断言（删旧写新/血缘）才有意义
        service = new KnowledgeIndexService(entryRepo, kbRepo, resolver,
                new KnowledgeIndexWriter(entryRepo, chunkRepo), props);

        entry = new KnowledgeEntryEntity();
        entry.setId(7L);
        entry.setKbId(3L);
        entry.setName("构建规范");
        entry.setContentMd("第一段内容。\n\n第二段内容，字数凑一凑超过二十字符触发分块。");
        entry.setIndexStatus(KnowledgeEntryEntity.INDEX_PENDING);
        lenient().when(entryRepo.findById(7L)).thenReturn(Optional.of(entry));
        lenient().when(entryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
    void indexesChunksAndMarksReady() {
        service.indexEntry(7L);

        verify(chunkRepo).deleteByEntryId(7L);
        ArgumentCaptor<List<KnowledgeChunkEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepo).saveAll(captor.capture());
        List<KnowledgeChunkEntity> chunks = captor.getValue();
        assertTrue(chunks.size() >= 2, "20 字符块长应切多块");
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunkEntity c = chunks.get(i);
            assertEquals(3L, c.getKbId());
            assertEquals(7L, c.getEntryId());
            assertEquals(i, c.getChunkIndex());
            assertNotNull(VectorJson.parse(c.getEmbedding()), "embedding 应为合法 JSON 向量");
            assertEquals(c.getContent().length(), c.getTokenCount());
        }
        assertEquals(KnowledgeEntryEntity.INDEX_READY, entry.getIndexStatus());
        assertNull(entry.getIndexError());
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
    void marksDisabledWhenNoEndpointAvailable() {
        when(resolver.resolve(any())).thenReturn(EmbeddingResolver.Resolution.unavailable());

        service.indexEntry(7L);

        assertEquals(KnowledgeEntryEntity.INDEX_DISABLED, entry.getIndexStatus());
        verify(chunkRepo, never()).deleteByEntryId(anyLong());
        verify(chunkRepo, never()).saveAll(anyList());
    }

    @Test
    void marksFailedOnEmbeddingError() {
        when(embeddingClient.embed(anyList()))
                .thenThrow(new EmbeddingException("embedding 端点返回 500: boom"));

        service.indexEntry(7L);

        assertEquals(KnowledgeEntryEntity.INDEX_FAILED, entry.getIndexStatus());
        assertTrue(entry.getIndexError().contains("500"), "失败原因落 index_error");
        verify(chunkRepo, never()).saveAll(anyList());
    }

    @Test
    void failureDoesNotWipeExistingChunks() {
        when(embeddingClient.embed(anyList())).thenThrow(new EmbeddingException("boom"));

        service.indexEntry(7L);

        verify(chunkRepo, never()).deleteByEntryId(anyLong());
    }

    @Test
    void emptyContentMarksReadyWithoutChunks() {
        entry.setContentMd("  ");

        service.indexEntry(7L);

        assertEquals(KnowledgeEntryEntity.INDEX_READY, entry.getIndexStatus());
        verify(chunkRepo).deleteByEntryId(7L);
        verify(chunkRepo, never()).saveAll(anyList());
        assertNull(entry.getIndexedDimensions(), "无分块时没有可记的维度");
    }

    @Test
    void sweepOnlyWhenEndpointAvailable() {
        List<KnowledgeEntryEntity> saved = new ArrayList<>();
        when(entryRepo.findByIndexStatusIn(anyList())).thenReturn(List.of(entry));
        when(entryRepo.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        when(resolver.anyConfigured()).thenReturn(false);
        service.sweepPending();
        verify(entryRepo, never()).findByIndexStatusIn(anyList());

        when(resolver.anyConfigured()).thenReturn(true);
        service.sweepPending();
        assertEquals(KnowledgeEntryEntity.INDEX_READY, entry.getIndexStatus(), "清扫应索引 pending 条目");
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
