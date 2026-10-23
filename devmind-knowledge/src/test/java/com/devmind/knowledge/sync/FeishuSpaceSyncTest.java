package com.devmind.knowledge.sync;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.integration.FeishuDocFetcher;
import com.devmind.common.integration.FeishuDocFetcher.FeishuDoc;
import com.devmind.common.integration.FeishuDocFetcher.SpaceDoc;
import com.devmind.knowledge.KnowledgeVersionService;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.model.KnowledgeSyncSourceEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import com.devmind.task.api.TaskContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-85 FR-27 飞书空间同步：首轮全拉、按 watermark 跳过未变更、源端删除标记失效、
 * 单篇失败不阻断整批。
 */
class FeishuSpaceSyncTest {

    private final Map<Long, KnowledgeEntryEntity> byId = new HashMap<>();
    private final Map<String, KnowledgeEntryEntity> byExternal = new HashMap<>();
    private final AtomicLong idSeq = new AtomicLong();
    private KnowledgeEntryRepository entryRepo;
    private FeishuDocFetcher fetcher;
    private ObjectProvider<FeishuDocFetcher> fetcherProvider;
    private KnowledgeSyncSourceEntity source;
    private FeishuSpaceSync sync;
    private TaskContext ctx;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        entryRepo = mock(KnowledgeEntryRepository.class);
        KnowledgeBaseRepository kbRepo = mock(KnowledgeBaseRepository.class);
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(3L);
        kb.setName("飞书文档库");
        kb.setScope(KnowledgeBaseEntity.SCOPE_GLOBAL);
        when(kbRepo.findById(3L)).thenReturn(Optional.of(kb));

        when(entryRepo.save(any())).thenAnswer(inv -> {
            KnowledgeEntryEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(idSeq.incrementAndGet());
            }
            byId.put(e.getId(), e);
            if (e.getExternalId() != null) {
                byExternal.put(e.getKbId() + ":" + e.getExternalId(), e);
            }
            return e;
        });
        when(entryRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(byId.get(inv.getArgument(0))));
        when(entryRepo.findByKbIdAndExternalId(anyLong(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(byExternal.get(inv.getArgument(0) + ":" + inv.getArgument(1))));
        when(entryRepo.findRefsBySyncSourceId(anyLong())).thenAnswer(inv -> byId.values().stream()
                .filter(e -> Objects.equals(inv.getArgument(0), e.getSyncSourceId()))
                .map(e -> new SyncRef(e.getId(), e.getExternalId()))
                .toList());

        SyncedEntryWriter writer = new SyncedEntryWriter(entryRepo,
                mock(KnowledgeVersionService.class), mock(ApplicationEventPublisher.class));
        fetcher = mock(FeishuDocFetcher.class);
        fetcherProvider = mock(ObjectProvider.class);
        when(fetcherProvider.getIfAvailable()).thenReturn(fetcher);
        sync = new FeishuSpaceSync(kbRepo, entryRepo, writer, fetcherProvider, new ObjectMapper());

        source = new KnowledgeSyncSourceEntity();
        source.setId(7L);
        source.setKbId(3L);
        source.setKind(KnowledgeSyncSourceEntity.KIND_FEISHU);
        source.setConfigJson("{\"integrationId\":9,\"target\":\"wiki-space\"}");

        ctx = mock(TaskContext.class);
        when(ctx.isCancelled()).thenReturn(false);
    }

    @Test
    void firstFullThenSkipsUnchangedThenDeprecatesRemoved() {
        long t1Updated = 1_700_000_000_000L;
        long t2Updated = 1_700_000_100_000L;
        when(fetcher.listSpaceDocs(9L, "wiki-space")).thenReturn(List.of(
                new SpaceDoc("t1", "文档一", "docx", t1Updated),
                new SpaceDoc("t2", "文档二", "docx", t2Updated)));
        when(fetcher.fetchNode(9L, "docx", "t1")).thenReturn(
                new FeishuDoc("t1", "文档一（正文标题）", "# 文档一\n\n内容一", "docx",
                        "https://feishu/docx/t1"));
        when(fetcher.fetchNode(9L, "docx", "t2")).thenReturn(
                new FeishuDoc("t2", "文档二", "# 文档二\n\n内容二", "docx",
                        "https://feishu/docx/t2"));

        SyncOutcome first = sync.run(source, ctx);

        assertEquals(2, first.created());
        assertEquals(String.valueOf(t2Updated), first.watermark(), "watermark 取最大 updatedAt");
        KnowledgeEntryEntity doc1 = byExternal.get("3:sync:7:t1");
        assertEquals("文档一（正文标题）", doc1.getName());
        assertEquals(KnowledgeEntryEntity.SOURCE_FEISHU, doc1.getSource());
        assertEquals("feishu://docx/t1", doc1.getSourceUri());
        assertEquals(KnowledgeEntryEntity.DOC_TECH, doc1.getDocType(), "未配置 docType 默认 TECH");
        source.setWatermark(first.watermark());

        SyncOutcome second = sync.run(source, ctx);

        assertEquals(0, second.created() + second.updated());
        assertEquals(1, second.unchanged(), "仅 watermark 边界文档在 60s 重叠窗口内被复核");
        assertEquals(String.valueOf(t2Updated), second.watermark());
        verify(fetcher, times(1)).fetchNode(9L, "docx", "t1");
        verify(fetcher, times(2)).fetchNode(9L, "docx", "t2");
        verify(fetcher, times(2)).listSpaceDocs(9L, "wiki-space");
        source.setWatermark(second.watermark());

        when(fetcher.listSpaceDocs(9L, "wiki-space")).thenReturn(List.of(
                new SpaceDoc("t1", "文档一", "docx", t1Updated)));
        SyncOutcome third = sync.run(source, ctx);

        assertEquals(1, third.deprecated(), "源端不再返回的文档标记失效");
        assertEquals("deprecated", byExternal.get("3:sync:7:t2").getStatus());
        assertNotNull(byExternal.get("3:sync:7:t2").getValidTo());
        assertEquals("active", doc1.getStatus());
    }

    @Test
    void changedDocIsFetchedAndUpdated() {
        long firstUpdated = 1_700_000_000_000L;
        when(fetcher.listSpaceDocs(9L, "wiki-space")).thenReturn(List.of(
                new SpaceDoc("t1", "文档一", "docx", firstUpdated)));
        when(fetcher.fetchNode(9L, "docx", "t1")).thenReturn(
                new FeishuDoc("t1", "文档一", "旧内容", "docx", "x"));
        SyncOutcome first = sync.run(source, ctx);
        assertEquals(1, first.created());
        source.setWatermark(first.watermark());

        when(fetcher.listSpaceDocs(9L, "wiki-space")).thenReturn(List.of(
                new SpaceDoc("t1", "文档一", "docx", 1_700_009_000_000L)));
        when(fetcher.fetchNode(9L, "docx", "t1")).thenReturn(
                new FeishuDoc("t1", "文档一", "新内容", "docx", "x"));
        SyncOutcome second = sync.run(source, ctx);

        assertEquals(1, second.updated());
        assertEquals("1700009000000", second.watermark());
        assertEquals("新内容", byExternal.get("3:sync:7:t1").getContentMd());
    }

    @Test
    void singleFetchFailureDoesNotAbortBatch() {
        when(fetcher.listSpaceDocs(9L, "wiki-space")).thenReturn(List.of(
                new SpaceDoc("bad", "坏文档", "docx", 1_700_000_000_000L),
                new SpaceDoc("good", "好文档", "docx", 1_700_000_001_000L)));
        when(fetcher.fetchNode(9L, "docx", "bad")).thenThrow(new RuntimeException("飞书 500"));
        when(fetcher.fetchNode(9L, "docx", "good")).thenReturn(
                new FeishuDoc("good", "好文档", "正常内容", "docx", "x"));

        SyncOutcome outcome = sync.run(source, ctx);

        assertEquals(1, outcome.failed());
        assertEquals(1, outcome.created());
        assertEquals("1700000001000", outcome.watermark(),
                "失败文档的 updatedAt 仍推进 watermark，下轮会重试");
        assertTrue(byExternal.get("3:sync:7:bad") == null, "失败条目不落库");
    }

    @Test
    void missingFetcherIsReportedAsBadRequest() {
        when(fetcherProvider.getIfAvailable()).thenReturn(null);

        DevMindException ex = assertThrows(DevMindException.class, () -> sync.run(source, ctx));
        assertTrue(ex.getMessage().contains("飞书集成"), ex.getMessage());
    }

    @Test
    void unsupportedDirectorySyncIsReported() {
        when(fetcher.listSpaceDocs(9L, "wiki-space"))
                .thenThrow(new UnsupportedOperationException("no"));

        DevMindException ex = assertThrows(DevMindException.class, () -> sync.run(source, ctx));
        assertTrue(ex.getMessage().contains("不支持目录同步"), ex.getMessage());
    }

    @Test
    void invalidConfigIsRejectedBeforeAnyRemoteCall() {
        source.setConfigJson("{\"target\":\"wiki-space\"}");
        assertThrows(DevMindException.class, () -> sync.run(source, ctx));

        source.setConfigJson("{\"integrationId\":9}");
        assertThrows(DevMindException.class, () -> sync.run(source, ctx));

        source.setConfigJson("{\"integrationId\":9,\"target\":\"wiki-space\",\"docType\":\"BAD\"}");
        assertThrows(DevMindException.class, () -> sync.run(source, ctx));

        verify(fetcher, never()).listSpaceDocs(anyLong(), anyString());
    }

    private record SyncRef(Long id, String externalId)
            implements KnowledgeEntryRepository.SyncEntryRef {
        @Override public Long getId() { return id; }
        @Override public String getExternalId() { return externalId; }
    }
}
