package com.devmind.knowledge.index;

import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.embedding.EmbeddingClient;
import com.devmind.knowledge.embedding.EmbeddingConfig;
import com.devmind.knowledge.embedding.EmbeddingException;
import com.devmind.knowledge.embedding.MockEmbeddingClient;
import com.devmind.knowledge.embedding.VectorJson;
import com.devmind.knowledge.model.KnowledgeChunkEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
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
 * KnowledgeIndexService（CAP-44 FR-04 摄入管线，mock repo 不拉起 Spring）：
 * 成功 → 删旧写新 chunks + ready；embedding 未配置 → disabled；调用失败 → failed+error；
 * 启动清扫只捞 pending+disabled。
 */
class KnowledgeIndexServiceTest {

    private KnowledgeEntryRepository entryRepo;
    private KnowledgeChunkRepository chunkRepo;
    private EmbeddingClient embeddingClient;
    private KnowledgeIndexService service;
    private KnowledgeEntryEntity entry;

    @BeforeEach
    void setUp() {
        entryRepo = mock(KnowledgeEntryRepository.class);
        chunkRepo = mock(KnowledgeChunkRepository.class);
        embeddingClient = mock(EmbeddingClient.class);
        KnowledgeProperties props = new KnowledgeProperties();
        props.getEmbedding().setChunkSize(20);
        props.getEmbedding().setChunkOverlap(5);
        service = new KnowledgeIndexService(entryRepo, chunkRepo, embeddingClient, props);

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
    void marksDisabledWhenEmbeddingUnavailable() {
        when(embeddingClient.available()).thenReturn(false);

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
    void emptyContentMarksReadyWithoutChunks() {
        entry.setContentMd("  ");

        service.indexEntry(7L);

        assertEquals(KnowledgeEntryEntity.INDEX_READY, entry.getIndexStatus());
        verify(chunkRepo).deleteByEntryId(7L);
        verify(chunkRepo, never()).saveAll(anyList());
    }

    @Test
    void sweepOnlyWhenEmbeddingAvailable() {
        List<KnowledgeEntryEntity> saved = new ArrayList<>();
        when(entryRepo.findByIndexStatusIn(anyList())).thenReturn(List.of(entry));
        when(entryRepo.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        when(embeddingClient.available()).thenReturn(false);
        service.sweepPending();
        verify(entryRepo, never()).findByIndexStatusIn(anyList());

        when(embeddingClient.available()).thenReturn(true);
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
