package com.devmind.knowledge;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
import com.devmind.knowledge.dto.EntryRequest;
import com.devmind.knowledge.dto.EntryView;
import com.devmind.knowledge.dto.KnowledgeBaseRequest;
import com.devmind.knowledge.dto.ReindexResult;
import com.devmind.knowledge.embedding.EmbeddingClient;
import com.devmind.knowledge.embedding.EmbeddingResolver;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeChunkRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import com.devmind.knowledge.repo.KnowledgeProposalRepository;
import com.devmind.notification.NotificationPublisher;
import com.devmind.project.ProjectService;
import com.devmind.project.model.Project;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseService 库模型重构（CAP-44 FR-03 注入口径零回归，mock repo 不拉起 Spring）：
 * selectEntries = global FULL 库按项目 tags 过滤 + 本项目 FULL 库全量；
 * legacy scope 创建条目 → 解析/兜底建经验库；CAP-48 库级端点绑定 / 失配重建。
 */
class KnowledgeBaseServiceTest {

    private final List<KnowledgeBaseEntity> kbs = new ArrayList<>();
    private final List<KnowledgeEntryEntity> entries = new ArrayList<>();
    private KnowledgeBaseService service;
    private KnowledgeBaseRepository kbRepo;
    private KnowledgeEntryRepository entryRepo;
    private EmbeddingResolver resolver;
    private ObjectProvider<ModelEndpointProvider> endpointProviders;
    private ApplicationEventPublisher eventPublisher;
    private long kbSeq = 0;
    private long entrySeq = 0;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kbs.clear();
        entries.clear();
        kbRepo = mock(KnowledgeBaseRepository.class);
        entryRepo = mock(KnowledgeEntryRepository.class);
        KnowledgeChunkRepository chunkRepo = mock(KnowledgeChunkRepository.class);
        KnowledgeProposalRepository proposalRepo = mock(KnowledgeProposalRepository.class);
        ProjectService projectService = mock(ProjectService.class);
        NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        resolver = mock(EmbeddingResolver.class);
        // 默认无可用端点：库视图端点名 null、失配数 0（CAP-48 前的行为）
        lenient().when(resolver.resolve(any())).thenReturn(EmbeddingResolver.Resolution.unavailable());
        endpointProviders = mock(ObjectProvider.class);
        lenient().when(endpointProviders.getIfAvailable()).thenReturn(null);

        when(kbRepo.findByScopeAndInjectModeAndStatus(anyString(), anyString(), anyString())).thenAnswer(inv ->
                kbs.stream().filter(k -> inv.getArgument(0).equals(k.getScope())
                        && inv.getArgument(1).equals(k.getInjectMode())
                        && inv.getArgument(2).equals(k.getStatus())).toList());
        when(kbRepo.findByScopeAndProjectIdAndInjectModeAndStatus(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> kbs.stream().filter(k -> inv.getArgument(0).equals(k.getScope())
                        && inv.getArgument(1).equals(k.getProjectId())
                        && inv.getArgument(2).equals(k.getInjectMode())
                        && inv.getArgument(3).equals(k.getStatus())).toList());
        when(kbRepo.save(any())).thenAnswer(inv -> {
            KnowledgeBaseEntity kb = inv.getArgument(0);
            if (kb.getId() == null) {
                kb.setId(++kbSeq);
                kbs.add(kb);
            }
            return kb;
        });
        when(entryRepo.findByKbIdAndStatusOrderByCreatedAtDesc(any(), anyString())).thenAnswer(inv ->
                entries.stream().filter(e -> inv.getArgument(0).equals(e.getKbId())
                        && inv.getArgument(1).equals(e.getStatus())).toList());
        when(entryRepo.save(any())).thenAnswer(inv -> {
            KnowledgeEntryEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(++entrySeq);
                entries.add(e);
            }
            return e;
        });
        when(projectService.requireProject(anyString())).thenAnswer(inv ->
                new Project(inv.getArgument(0), "项目" + inv.getArgument(0), null, null, List.of(),
                        null, null, null));

        service = new KnowledgeBaseService(kbRepo, entryRepo, chunkRepo, proposalRepo,
                projectService, notificationPublisher, eventPublisher, resolver, endpointProviders);
    }

    private KnowledgeBaseEntity addKb(String scope, String projectId, String injectMode) {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(++kbSeq);
        kb.setScope(scope);
        kb.setProjectId(projectId);
        kb.setInjectMode(injectMode);
        kb.setName(scope + "-kb-" + kb.getId());
        kb.setCreatedAt(Instant.now());
        kbs.add(kb);
        return kb;
    }

    private KnowledgeEntryEntity addEntry(KnowledgeBaseEntity kb, String name, String tags, String status) {
        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        e.setId(++entrySeq);
        e.setKbId(kb.getId());
        e.setName(name);
        e.setTags(tags);
        e.setStatus(status);
        e.setContentMd("content-" + name);
        e.setCreatedAt(Instant.now());
        entries.add(e);
        return e;
    }

    @Test
    void selectEntriesKeepsCap04Semantics() {
        KnowledgeBaseEntity globalFull = addKb("global", null, "FULL");
        KnowledgeBaseEntity globalRag = addKb("global", null, "RAG");
        KnowledgeBaseEntity projectFull = addKb("project", "p1", "FULL");
        KnowledgeBaseEntity otherProjectFull = addKb("project", "p2", "FULL");
        addEntry(globalFull, "无标签全局", null, "active");
        addEntry(globalFull, "前端规范", "frontend,spring", "active");
        addEntry(globalFull, "废弃条目", null, "deprecated");
        addEntry(globalRag, "RAG 库条目", null, "active");
        addEntry(projectFull, "项目条目", null, "active");
        addEntry(otherProjectFull, "他项目条目", null, "active");

        List<EntryView> noTags = service.selectEntries("p1", List.of());
        assertEquals(List.of("无标签全局", "项目条目"), noTags.stream().map(EntryView::name).toList(),
                "无项目 tags：仅无标签全局 + 本项目 FULL；RAG 库/deprecated/他项目排除");

        List<EntryView> withTags = service.selectEntries("p1", List.of("frontend"));
        assertEquals(List.of("无标签全局", "前端规范", "项目条目"),
                withTags.stream().map(EntryView::name).toList(), "项目 tags 命中带标签全局条目");
        assertEquals("global", withTags.get(0).scope(), "视图 scope 由 KB 派生");
        assertEquals("p1", withTags.get(2).projectId(), "视图 projectId 由 KB 派生");
    }

    @Test
    void selectByTagsPoolsFullBasesOnly() {
        KnowledgeBaseEntity globalFull = addKb("global", null, "FULL");
        KnowledgeBaseEntity globalRag = addKb("global", null, "RAG");
        addEntry(globalFull, "spring 经验", "spring", "active");
        addEntry(globalRag, "rag 条目", "spring", "active");

        List<EntryView> hits = service.selectByTags(List.of("spring"), null);
        assertEquals(List.of("spring 经验"), hits.stream().map(EntryView::name).toList(),
                "selectByTags 只从 FULL 库取");
    }

    @Test
    void legacyScopeCreateResolvesExperienceKb() {
        EntryView created = service.createEntry(new EntryRequest(
                null, "project", "p9", "条目A", "# 内容", List.of("t"), null, null));

        assertEquals(1, kbs.size(), "无经验库时按 legacy scope 兜底建库");
        KnowledgeBaseEntity kb = kbs.get(0);
        assertEquals("project", kb.getScope());
        assertEquals("p9", kb.getProjectId());
        assertEquals("FULL", kb.getInjectMode(), "兜底建的是 FULL 经验库");
        assertEquals(kb.getId(), created.kbId());
        assertEquals("project", created.scope(), "视图 scope 派生自库");
        assertEquals("p9", created.projectId());

        service.createEntry(new EntryRequest(null, "project", "p9", "条目B", "x", null, null, null));
        assertEquals(1, kbs.size(), "同项目经验库复用不重复建");
        assertEquals(2, entries.size());
    }

    // ---------------- CAP-48 库级端点绑定 / 失配重建 ----------------

    @Test
    void createBaseBindsKbLevelEndpointOverride() {
        service.createBase(new KnowledgeBaseRequest("研发库", "d", "global", null, "RAG", 11L, null));
        assertEquals(11L, kbs.get(0).getModelEndpointId(), "显式传端点 → 库级覆盖");

        service.createBase(new KnowledgeBaseRequest("归档库", "d", "global", null, "RAG", 0L, null));
        assertNull(kbs.get(1).getModelEndpointId(), "0 = 清除覆盖，回落平台默认端点");
    }

    @Test
    void updateBaseWithoutEndpointKeepsExistingOverride() {
        service.createBase(new KnowledgeBaseRequest("研发库", "d", "global", null, "RAG", 11L, null));
        Long id = kbs.get(0).getId();
        when(kbRepo.findById(id)).thenAnswer(inv -> kbs.stream()
                .filter(k -> k.getId().equals(inv.getArgument(0))).findFirst());

        // 只改描述（modelEndpointId 缺省 null）：其他表单字段的"未传=不改"语义要一致
        service.updateBase(id, new KnowledgeBaseRequest(null, "新描述", null, null, null, null, null));

        assertEquals(11L, kbs.get(0).getModelEndpointId(), "缺省不传端点不得清掉已有覆盖");
        assertEquals("新描述", kbs.get(0).getDescription());
    }

    /** FR-11 守卫：端点表里还有对话端点，知识库只能绑向量端点——绑定时就拒绝，别等索引阶段才炸 */
    @Test
    void rejectsBindingChatEndpointAsVectorEndpoint() {
        ModelEndpointProvider provider = mock(ModelEndpointProvider.class);
        when(endpointProviders.getIfAvailable()).thenReturn(provider);
        when(provider.activeEndpoint(7L)).thenReturn(java.util.Optional.of(new ModelEndpointView(
                7L, "CHAT", ModelEndpointView.PROVIDER_OPENAI, "公司通用模型", "https://api.example.com/v1",
                null, "gpt-4o-mini", null, 30, 32, null, null)));

        DevMindException ex = assertThrows(DevMindException.class, () -> service.createBase(
                new KnowledgeBaseRequest("研发库", "d", "global", null, "RAG", 7L, null)));

        assertTrue(ex.getMessage().contains("EMBEDDING"), ex.getMessage());
        assertTrue(ex.getMessage().contains("公司通用模型"), "错误消息要点名是哪个端点: " + ex.getMessage());
    }

    @Test
    void reindexBaseQueuesAllEntries() {
        KnowledgeBaseEntity kb = addKb("global", null, "RAG");
        when(kbRepo.findById(kb.getId())).thenReturn(java.util.Optional.of(kb));
        KnowledgeEntryEntity ready = addEntry(kb, "已索引", null, "active");
        ready.setIndexStatus(KnowledgeEntryEntity.INDEX_READY);
        KnowledgeEntryEntity failed = addEntry(kb, "失败条目", null, "active");
        failed.setIndexStatus(KnowledgeEntryEntity.INDEX_FAILED);
        failed.setIndexError("boom");
        when(entryRepo.findByKbIdOrderByCreatedAtDesc(kb.getId())).thenReturn(List.of(ready, failed));

        ReindexResult result = service.reindexBase(kb.getId(), false);

        assertEquals(2, result.queued(), "全量重建把库里所有条目重新入队");
        for (KnowledgeEntryEntity e : List.of(ready, failed)) {
            assertEquals(KnowledgeEntryEntity.INDEX_PENDING, e.getIndexStatus());
            assertNull(e.getIndexError(), "重建要清掉上一次的错误信息");
        }
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(any(Object.class));
    }

    @Test
    void reindexBaseOnlyMismatchedSkipsWithoutProbedDimensions() {
        KnowledgeBaseEntity kb = addKb("global", null, "RAG");
        when(kbRepo.findById(kb.getId())).thenReturn(java.util.Optional.of(kb));

        ReindexResult result = service.reindexBase(kb.getId(), true);

        assertEquals(0, result.queued(), "端点未探测过维度时算不出失配，宁可不重建");
        verify(entryRepo, never()).findMismatched(anyLong(), anyLong(), anyInt());
    }

    @Test
    void reindexBaseOnlyMismatchedUsesResolvedEndpointLineage() {
        KnowledgeBaseEntity kb = addKb("global", null, "RAG");
        when(kbRepo.findById(kb.getId())).thenReturn(java.util.Optional.of(kb));
        when(resolver.resolve(any())).thenReturn(new EmbeddingResolver.Resolution(
                11L, "bge-m3", 1024, 0.15, 8, mock(EmbeddingClient.class)));
        KnowledgeEntryEntity stale = addEntry(kb, "旧端点建的", null, "active");
        stale.setIndexStatus(KnowledgeEntryEntity.INDEX_READY);
        KnowledgeEntryEntity current = addEntry(kb, "当前端点建的", null, "active");
        current.setIndexStatus(KnowledgeEntryEntity.INDEX_READY);
        when(entryRepo.findMismatched(kb.getId(), 11L, 1024)).thenReturn(List.of(stale));

        ReindexResult result = service.reindexBase(kb.getId(), true);

        assertEquals(1, result.queued(), "只重建当前端点+维度对不上的条目");
        assertEquals(KnowledgeEntryEntity.INDEX_PENDING, stale.getIndexStatus());
        assertEquals(KnowledgeEntryEntity.INDEX_READY, current.getIndexStatus(), "未失配的条目不动");
    }
}
