package com.devmind.knowledge;

import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.agent.exec.ContextContribution;
import com.devmind.common.agent.exec.ManifestItem;
import com.devmind.common.knowledge.KnowledgeRetriever;
import com.devmind.common.knowledge.KnowledgeRetriever.DegradedReason;
import com.devmind.common.knowledge.KnowledgeRetriever.Detailed;
import com.devmind.common.knowledge.KnowledgeRetriever.RetrievedChunk;
import com.devmind.knowledge.config.KnowledgeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-92：场景绑库检索注入。命中块按条目聚合渲染「相关知识」节；无绑库/无命中/检索异常
 * 均只产出 settings 白名单；dryRun 不累计 hitCount。
 */
class KnowledgeContextProviderTest {

    private KnowledgeBaseService service;
    private KnowledgeRetriever retriever;
    private KnowledgeContextProvider provider;

    @BeforeEach
    void setUp() {
        service = mock(KnowledgeBaseService.class);
        retriever = mock(KnowledgeRetriever.class);
        provider = new KnowledgeContextProvider(new KnowledgeProperties(), service, providerOf(retriever));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<KnowledgeRetriever> providerOf(KnowledgeRetriever retriever) {
        ObjectProvider<KnowledgeRetriever> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(retriever);
        return provider;
    }

    private static ContextAssemblyRequest req(List<Long> kbIds, String query, boolean dryRun) {
        return new ContextAssemblyRequest(null, List.of(), List.of(), List.of(), List.of(), List.of(),
                kbIds, query, false, dryRun);
    }

    private static RetrievedChunk chunk(long entryId, String entryName, long kbId, String content,
                                        String sourceUri, String headingPath, double score) {
        return new RetrievedChunk(entryId, entryName, kbId, 100 + entryId, sourceUri, headingPath,
                content, content, score);
    }

    private void stubHits(List<RetrievedChunk> chunks) {
        when(retriever.retrieveDetailed(List.of(10L), "修构建", 8))
                .thenReturn(new Detailed(chunks, true, DegradedReason.NONE));
    }

    @Test
    void rendersRetrievedChunksGroupedByEntry() {
        stubHits(List.of(
                chunk(1L, "发布手册", 10L, "回滚步骤：先摘流量", "maint/runbook.md", "部署 > 回滚", 0.03),
                chunk(1L, "发布手册", 10L, "灰度步骤：每批 10%", "maint/runbook.md", "部署 > 灰度", 0.02),
                chunk(2L, "编码规范", 10L, "异常必须带上下文", "dev/style.md", "Java > 异常", 0.01)));
        when(retriever.baseNames(List.of(10L))).thenReturn(Map.of(10L, "研发规范库"));

        ContextContribution c = provider.contribute(req(List.of(10L), "修构建", false));

        assertEquals(1, c.claudeMdSections().size());
        String md = c.claudeMdSections().get(0);
        assertTrue(md.contains("## 相关知识（检索命中）"), md);
        assertTrue(md.contains("### 发布手册"));
        assertTrue(md.contains("**片段 1**（部署 > 回滚）"));
        assertTrue(md.contains("maint/runbook.md > 部署 > 回滚（库：研发规范库）"));
        assertTrue(md.contains("### 编码规范"), "同一文档多块合成一节，不同条目分节");
        assertEquals(2, c.items().size(), "清单按条目去重");
        ManifestItem first = c.items().get(0);
        assertEquals(ManifestItem.KIND_KNOWLEDGE, first.kind());
        assertEquals("1", first.ref());
        assertEquals("发布手册", first.name());
        assertEquals("研发规范库", first.scope(), "CAP-94：manifest scope 字段输出库名");
        assertEquals(ManifestItem.SOURCE_SCENARIO, first.source());
        assertEquals(2, first.extra().get("chunks"));
        assertNotNull(c.settingsLocalJson(), "settings 白名单始终产出");
        verify(service).bumpHitsByEntryIds(anyCollection());
    }

    @Test
    void dryRunDoesNotCountHits() {
        stubHits(List.of(chunk(1L, "发布手册", 10L, "回滚", "maint/runbook.md", null, 0.03)));
        when(retriever.baseNames(any())).thenReturn(Map.of());

        ContextContribution c = provider.contribute(req(List.of(10L), "修构建", true));

        assertFalse(c.claudeMdSections().isEmpty());
        verify(service, never()).bumpHitsByEntryIds(any());
    }

    @Test
    void noBoundBasesYieldsSettingsOnly() {
        ContextContribution c = provider.contribute(req(List.of(), "修构建", false));

        assertTrue(c.claudeMdSections().isEmpty());
        assertTrue(c.items().isEmpty());
        assertNotNull(c.settingsLocalJson());
        verify(retriever, never()).retrieveDetailed(anyList(), anyString(), anyInt());
    }

    @Test
    void blankQueryYieldsSettingsOnly() {
        provider.contribute(req(List.of(10L), "  ", false));
        verify(retriever, never()).retrieveDetailed(anyList(), anyString(), anyInt());
    }

    @Test
    void noHitsYieldsSettingsOnly() {
        stubHits(List.of());

        ContextContribution c = provider.contribute(req(List.of(10L), "修构建", false));

        assertTrue(c.claudeMdSections().isEmpty());
        assertNotNull(c.settingsLocalJson());
        verify(service, never()).bumpHitsByEntryIds(any());
    }

    @Test
    void retrievalFailureDegradesToSettingsOnly() {
        when(retriever.retrieveDetailed(anyList(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("index down"));

        ContextContribution c = provider.contribute(req(List.of(10L), "修构建", false));

        assertTrue(c.claudeMdSections().isEmpty(), "检索异常不阻塞会话");
        assertNotNull(c.settingsLocalJson());
    }

    @Test
    void missingRetrieverYieldsSettingsOnly() {
        KnowledgeContextProvider bare = new KnowledgeContextProvider(
                new KnowledgeProperties(), service, providerOf(null));

        ContextContribution c = bare.contribute(req(List.of(10L), "修构建", false));

        assertTrue(c.claudeMdSections().isEmpty());
        assertNotNull(c.settingsLocalJson());
    }
}
