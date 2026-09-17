package com.devmind.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.integration.FeishuDocFetcher;
import com.devmind.common.integration.FeishuDocFetcher.FeishuDoc;
import com.devmind.knowledge.dto.FeishuImportRequest;
import com.devmind.knowledge.dto.FeishuImportResult;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * CAP-45 飞书导入服务（mock repo + 假 FeishuDocFetcher，不拉起 Spring）：
 * 判重跳过/变更更新+事件、单条失败隔离、重同步仅飞书条目、拉取失败保留旧内容。
 */
class FeishuImportServiceTest {

    private final List<KnowledgeEntryEntity> entries = new ArrayList<>();
    private final List<Long> publishedEvents = new ArrayList<>();
    private FeishuImportService service;
    private FakeFetcher fetcher;
    private long entrySeq = 0;

    /** 可控假拉取器：按 URL 注册文档或异常 */
    private static final class FakeFetcher implements FeishuDocFetcher {
        private final java.util.Map<String, FeishuDoc> docs = new java.util.HashMap<>();
        private final java.util.Set<String> failing = new java.util.HashSet<>();

        @Override
        public List<FeishuIntegration> listIntegrations() {
            return List.of(new FeishuIntegration(7L, "飞书"));
        }

        @Override
        public FeishuDoc fetch(long integrationId, String url) {
            if (failing.contains(url)) {
                throw new DevMindException(com.devmind.common.exception.ErrorCode.INTERNAL, "飞书侧错误");
            }
            FeishuDoc doc = docs.get(url);
            if (doc == null) {
                throw new DevMindException(com.devmind.common.exception.ErrorCode.BAD_REQUEST, "无法识别");
            }
            return doc;
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        entries.clear();
        publishedEvents.clear();
        fetcher = new FakeFetcher();

        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(1L);
        kb.setName("规范库");
        kb.setScope(KnowledgeBaseEntity.SCOPE_GLOBAL);
        KnowledgeBaseRepository kbRepo = mock(KnowledgeBaseRepository.class);
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb));

        KnowledgeEntryRepository entryRepo = mock(KnowledgeEntryRepository.class);
        when(entryRepo.findByKbIdAndExternalId(any(), any())).thenAnswer(inv ->
                entries.stream()
                        .filter(e -> inv.getArgument(0).equals(e.getKbId())
                                && inv.getArgument(1).equals(e.getExternalId()))
                        .findFirst());
        when(entryRepo.findById(any())).thenAnswer(inv ->
                entries.stream().filter(e -> inv.getArgument(0).equals(e.getId())).findFirst());
        when(entryRepo.save(any())).thenAnswer(inv -> {
            KnowledgeEntryEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(++entrySeq);
                entries.add(e);
            }
            return e;
        });

        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        org.mockito.Mockito.doAnswer(inv -> {
            publishedEvents.add(inv.getArgument(0, EntryContentChangedEvent.class).entryId());
            return null;
        }).when(eventPublisher).publishEvent(any(EntryContentChangedEvent.class));

        ObjectProvider<FeishuDocFetcher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fetcher);
        service = new FeishuImportService(kbRepo, entryRepo, eventPublisher, provider);
    }

    private static FeishuDoc doc(String token, String title, String content) {
        return new FeishuDoc(token, title, content, "docx",
                "https://x.feishu.cn/docx/" + token);
    }

    @Test
    void importCreatesThenUnchangedThenUpdated() {
        String url = "https://x.feishu.cn/docx/tok1";
        fetcher.docs.put(url, doc("tok1", "规范文档", "# 内容 v1"));

        List<FeishuImportResult> r1 = service.importDocs(1L, new FeishuImportRequest(7L, List.of(url)));
        assertEquals("created", r1.get(0).status());
        assertEquals(1, entries.size());
        KnowledgeEntryEntity e = entries.get(0);
        assertEquals("feishu", e.getSource());
        assertEquals("7:tok1", e.getExternalId());
        assertEquals(url, e.getPath());
        assertEquals(1, publishedEvents.size());

        // 同内容再导 → unchanged，无新事件
        List<FeishuImportResult> r2 = service.importDocs(1L, new FeishuImportRequest(7L, List.of(url)));
        assertEquals("unchanged", r2.get(0).status());
        assertEquals(1, entries.size());
        assertEquals(1, publishedEvents.size());

        // 内容变更 → updated + 重索引事件
        fetcher.docs.put(url, doc("tok1", "规范文档", "# 内容 v2"));
        List<FeishuImportResult> r3 = service.importDocs(1L, new FeishuImportRequest(7L, List.of(url)));
        assertEquals("updated", r3.get(0).status());
        assertEquals("# 内容 v2", e.getContentMd());
        assertEquals(2, publishedEvents.size());
    }

    @Test
    void importFailureIsolatedPerUrl() {
        String okUrl = "https://x.feishu.cn/docx/good";
        String badUrl = "https://x.feishu.cn/wiki/broken";
        fetcher.docs.put(okUrl, doc("good", "好文档", "内容"));
        fetcher.failing.add(badUrl);

        List<FeishuImportResult> results = service.importDocs(1L,
                new FeishuImportRequest(7L, List.of(okUrl, badUrl)));

        assertEquals(2, results.size());
        assertEquals("created", results.get(0).status());
        assertEquals("failed", results.get(1).status());
        assertTrue(results.get(1).error().contains("飞书侧错误"));
        assertEquals(1, entries.size());
    }

    @Test
    void resyncRejectsManualEntry() {
        KnowledgeEntryEntity manual = new KnowledgeEntryEntity();
        manual.setId(++entrySeq);
        manual.setKbId(1L);
        manual.setSource(KnowledgeEntryEntity.SOURCE_MANUAL);
        entries.add(manual);

        assertThrows(DevMindException.class, () -> service.resync(manual.getId()));
    }

    @Test
    void resyncFailureKeepsOldContent() {
        String url = "https://x.feishu.cn/docx/tok9";
        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        e.setId(++entrySeq);
        e.setKbId(1L);
        e.setSource(KnowledgeEntryEntity.SOURCE_FEISHU);
        e.setExternalId("7:tok9");
        e.setPath(url);
        e.setName("旧标题");
        e.setContentMd("旧内容");
        e.setContentHash(KnowledgeBaseService.sha256("旧内容"));
        entries.add(e);
        fetcher.failing.add(url);

        FeishuImportResult result = service.resync(e.getId());

        assertEquals("failed", result.status());
        assertEquals("旧内容", e.getContentMd());
        assertEquals(0, publishedEvents.size());
    }

    @Test
    void resyncAppliesChangesAndRenames() {
        String url = "https://x.feishu.cn/docx/tok8";
        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        e.setId(++entrySeq);
        e.setKbId(1L);
        e.setSource(KnowledgeEntryEntity.SOURCE_FEISHU);
        e.setExternalId("7:tok8");
        e.setPath(url);
        e.setName("旧标题");
        e.setContentMd("旧内容");
        e.setContentHash(KnowledgeBaseService.sha256("旧内容"));
        entries.add(e);
        fetcher.docs.put(url, doc("tok8", "新标题", "新内容"));

        FeishuImportResult result = service.resync(e.getId());

        assertEquals("updated", result.status());
        assertEquals("新标题", e.getName());
        assertEquals("新内容", e.getContentMd());
        assertNotEquals(KnowledgeBaseService.sha256("旧内容"), e.getContentHash());
        assertEquals(1, publishedEvents.size());
    }

    @Test
    void listIntegrationsDegradesWithoutFetcher() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FeishuDocFetcher> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        FeishuImportService noFetcher = new FeishuImportService(
                mock(KnowledgeBaseRepository.class), mock(KnowledgeEntryRepository.class),
                mock(ApplicationEventPublisher.class), empty);

        assertTrue(noFetcher.listIntegrations().isEmpty());
        assertEquals(List.of(new FeishuDocFetcher.FeishuIntegration(7L, "飞书")), service.listIntegrations());
    }
}
