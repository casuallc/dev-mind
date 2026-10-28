package com.devmind.knowledge;

import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
import com.devmind.knowledge.dto.EntryRequest;
import com.devmind.knowledge.dto.EntrySummaryView;
import com.devmind.knowledge.dto.EntryView;
import com.devmind.knowledge.dto.KnowledgeBaseRequest;
import com.devmind.knowledge.dto.ProposalRequest;
import com.devmind.knowledge.dto.ProposalView;
import com.devmind.knowledge.dto.ReindexResult;
import com.devmind.knowledge.embedding.EmbeddingClient;
import com.devmind.knowledge.embedding.EmbeddingResolver;
import com.devmind.knowledge.index.KnowledgeIndexService;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.model.KnowledgeProposalEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeChunkRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import com.devmind.knowledge.repo.KnowledgeProposalRepository;
import com.devmind.notification.NotificationPublisher;
import com.devmind.task.service.AsyncTaskService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

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
 * KnowledgeBaseService 库模型（CAP-44 库容器；CAP-94 去项目化：kbId 必填、采纳显式选库，
 * mock repo 不拉起 Spring）：CAP-48 库级端点绑定 / 失配重建；检索命中计数；提案采纳。
 */
class KnowledgeBaseServiceTest {

    private final List<KnowledgeBaseEntity> kbs = new ArrayList<>();
    private final List<KnowledgeEntryEntity> entries = new ArrayList<>();
    private KnowledgeBaseService service;
    private com.devmind.knowledge.access.KnowledgeAccessService access;
    private KnowledgeBaseRepository kbRepo;
    private KnowledgeEntryRepository entryRepo;
    private KnowledgeProposalRepository proposalRepo;
    private EmbeddingResolver resolver;
    private ObjectProvider<ModelEndpointProvider> endpointProviders;
    private ApplicationEventPublisher eventPublisher;
    private KnowledgeVersionService versionService;
    private KnowledgeIndexService indexService;
    private AsyncTaskService taskService;
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
        proposalRepo = mock(KnowledgeProposalRepository.class);
        NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        resolver = mock(EmbeddingResolver.class);
        // 默认无可用端点：库视图端点名 null、失配数 0（CAP-48 前的行为）
        lenient().when(resolver.resolve(any())).thenReturn(EmbeddingResolver.Resolution.unavailable());
        endpointProviders = mock(ObjectProvider.class);
        lenient().when(endpointProviders.getIfAvailable()).thenReturn(null);
        versionService = mock(KnowledgeVersionService.class);
        indexService = mock(KnowledgeIndexService.class);
        taskService = mock(AsyncTaskService.class);
        lenient().when(taskService.submit(anyString(), anyString(), any())).thenReturn("task-1");

        lenient().when(kbRepo.findByStatusOrderByCreatedAtDesc(anyString())).thenAnswer(inv ->
                kbs.stream().filter(k -> inv.getArgument(0).equals(k.getStatus())).toList());
        when(kbRepo.findAllById(any())).thenAnswer(inv -> {
            Iterable<Long> ids = inv.getArgument(0);
            List<Long> wanted = new ArrayList<>();
            ids.forEach(wanted::add);
            return kbs.stream().filter(k -> wanted.contains(k.getId())).toList();
        });
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
        when(entryRepo.findAllById(any())).thenAnswer(inv -> {
            Iterable<Long> ids = inv.getArgument(0);
            List<Long> wanted = new ArrayList<>();
            ids.forEach(wanted::add);
            return entries.stream().filter(e -> wanted.contains(e.getId())).toList();
        });
        access = mock(com.devmind.knowledge.access.KnowledgeAccessService.class);
        lenient().when(access.actor()).thenReturn("tester");
        lenient().when(access.readableBaseIds(any())).thenAnswer(inv -> kbs.stream().map(KnowledgeBaseEntity::getId).toList());
        lenient().when(access.readableEntryIds(any())).thenAnswer(inv -> entries.stream().map(KnowledgeEntryEntity::getId).toList());
        service = new KnowledgeBaseService(kbRepo, entryRepo, chunkRepo, proposalRepo,
                mock(com.devmind.knowledge.repo.KnowledgeImportFileRepository.class),
                notificationPublisher, eventPublisher, resolver, endpointProviders,
                versionService, indexService, mock(com.devmind.knowledge.retrieve.KnowledgeSearchService.class),
                taskService, access);
    }

    private KnowledgeBaseEntity addKb(String name) {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(++kbSeq);
        kb.setStatus(KnowledgeBaseEntity.STATUS_ACTIVE);
        kb.setName(name + "-" + kb.getId());
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
    void baseNamesMapsIdsForManifest() {
        KnowledgeBaseEntity a = addKb("研发规范");
        KnowledgeBaseEntity b = addKb("运维经验");

        assertEquals(java.util.Map.of(a.getId(), a.getName(), b.getId(), b.getName()),
                service.baseNames(List.of(a.getId(), b.getId(), 999L)), "不存在的 id 跳过");
        assertTrue(service.baseNames(List.of()).isEmpty());
    }

    @Test
    void bumpHitsCountsRetrievedEntries() {
        KnowledgeBaseEntity kb = addKb("kb");
        KnowledgeEntryEntity hit = addEntry(kb, "命中", null, "active");
        KnowledgeEntryEntity miss = addEntry(kb, "未命中", null, "active");

        service.bumpHitsByEntryIds(List.of(hit.getId()));

        assertEquals(1, hit.getHitCount());
        assertEquals(0, miss.getHitCount());
    }

    @Test
    void createEntryRequiresExplicitKbId() {
        DevMindException ex = assertThrows(DevMindException.class, () -> service.createEntry(
                new EntryRequest(null, "条目A", "# 内容", List.of("t"), null, null,
                        null, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("kbId"), ex.getMessage());

        KnowledgeBaseEntity kb = addKb("经验库");
        when(kbRepo.findById(kb.getId())).thenReturn(Optional.of(kb));
        EntryView created = service.createEntry(new EntryRequest(
                kb.getId(), "条目A", "# 内容", List.of("t"), "p9", null,
                null, null, null, null, null, null));

        assertEquals(kb.getId(), created.kbId());
        assertEquals("p9", created.sourceProject(), "项目只剩溯源标注");
        assertEquals(1, entries.size());
    }

    // ---------------- CAP-48 库级端点绑定 / 失配重建 ----------------

    @Test
    void createBaseBindsKbLevelEndpointOverride() {
        service.createBase(new KnowledgeBaseRequest("研发库", "d", 11L, null));
        assertEquals(11L, kbs.get(0).getModelEndpointId(), "显式传端点 → 库级覆盖");

        service.createBase(new KnowledgeBaseRequest("归档库", "d", 0L, null));
        assertNull(kbs.get(1).getModelEndpointId(), "0 = 清除覆盖，回落平台默认端点");
    }

    @Test
    void updateBaseWithoutEndpointKeepsExistingOverride() {
        service.createBase(new KnowledgeBaseRequest("研发库", "d", 11L, null));
        Long id = kbs.get(0).getId();
        when(kbRepo.findById(id)).thenAnswer(inv -> kbs.stream()
                .filter(k -> k.getId().equals(inv.getArgument(0))).findFirst());

        // 只改描述（modelEndpointId 缺省 null）：其他表单字段的"未传=不改"语义要一致
        service.updateBase(id, new KnowledgeBaseRequest(null, "新描述", null, null));

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
                new KnowledgeBaseRequest("研发库", "d", 7L, null)));

        assertTrue(ex.getMessage().contains("EMBEDDING"), ex.getMessage());
        assertTrue(ex.getMessage().contains("公司通用模型"), "错误消息要点名是哪个端点: " + ex.getMessage());
    }

    @Test
    void reindexBaseQueuesFullRebuildTask() {
        KnowledgeBaseEntity kb = addKb("kb");
        when(kbRepo.findById(kb.getId())).thenReturn(java.util.Optional.of(kb));
        when(entryRepo.countByKbId(kb.getId())).thenReturn(2L);
        KnowledgeEntryEntity ready = addEntry(kb, "已索引", null, "active");
        ready.setIndexStatus(KnowledgeEntryEntity.INDEX_READY);
        KnowledgeEntryEntity failed = addEntry(kb, "失败条目", null, "active");
        failed.setIndexStatus(KnowledgeEntryEntity.INDEX_FAILED);

        ReindexResult result = service.reindexBase(kb.getId(), false);

        assertEquals(2, result.queued(), "全量重建返回库内条目数");
        verify(taskService).submit(org.mockito.ArgumentMatchers.eq("knowledge.rebuild"),
                org.mockito.ArgumentMatchers.contains(String.valueOf(kb.getId())),
                any());
        assertEquals(KnowledgeEntryEntity.INDEX_READY, ready.getIndexStatus(), "任务模式不预先改状态");
    }

    @Test
    void createProposalStartsOpenWithoutTriage() {
        when(proposalRepo.save(any(KnowledgeProposalEntity.class))).thenAnswer(inv -> {
            KnowledgeProposalEntity p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(4711L);
        p.setCreatedBy("tester");
            }
            return p;
        });

        ProposalView view = service.createProposal(new ProposalRequest(
                "构建失败先看日志末尾", "九成环境类报错在日志最后 200 行", "s1", "p1"));

        assertEquals("open", view.status());
        assertNull(view.triage(), "CAP-57 解耦后不再有自动分诊：徽标只读历史数据");
    }

    // ---------------- 提案人工处置 ----------------

    private KnowledgeProposalEntity addOpenProposal() {
        KnowledgeProposalEntity p = new KnowledgeProposalEntity();
        p.setId(4711L);
        p.setCreatedBy("tester");
        p.setTitle("构建失败先看日志末尾");
        p.setContentMd("九成环境类报错在日志最后 200 行");
        p.setSourceProject("p1");
        p.setStatus("open");
        p.setCreatedAt(Instant.now());
        when(proposalRepo.findById(4711L)).thenReturn(Optional.of(p));
        when(proposalRepo.save(any(KnowledgeProposalEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        return p;
    }

    @Test
    void adoptRequiresExplicitTargetKb() {
        KnowledgeProposalEntity p = addOpenProposal();

        DevMindException ex = assertThrows(DevMindException.class, () -> service.adopt(p.getId(), null));
        assertTrue(ex.getMessage().contains("targetKbId"), ex.getMessage());
    }

    @Test
    void adoptIntoSelectedKbCreatesEntryThere() {
        KnowledgeProposalEntity p = addOpenProposal();
        KnowledgeBaseEntity kb = addKb("经验库");
        when(kbRepo.findById(kb.getId())).thenReturn(Optional.of(kb));

        ProposalView view = service.adopt(p.getId(), kb.getId());

        assertEquals("adopted", view.status());
        assertEquals(kb.getId(), view.adoptedKbId());
        assertEquals(kb.getName(), view.adoptedKbName());
        assertEquals(1, entries.size(), "采纳在目标库落一条知识条目");
        assertEquals(kb.getId(), entries.get(0).getKbId());
        assertEquals("p1", entries.get(0).getSourceProject(), "条目带来源项目溯源");
    }

    @Test
    void adoptIntoArchivedKbRejected() {
        KnowledgeProposalEntity p = addOpenProposal();
        KnowledgeBaseEntity kb = addKb("旧库");
        kb.setStatus(KnowledgeBaseEntity.STATUS_ARCHIVED);
        when(kbRepo.findById(kb.getId())).thenReturn(Optional.of(kb));

        DevMindException ex = assertThrows(DevMindException.class, () -> service.adopt(p.getId(), kb.getId()));
        assertTrue(ex.getMessage().contains("归档"), ex.getMessage());
        assertTrue(entries.isEmpty(), "归档库采纳不落条目");
    }

    @Test
    void rejectMarksProposalRejected() {
        KnowledgeProposalEntity p = addOpenProposal();

        assertEquals("rejected", service.reject(p.getId()).status());
    }

    @Test
    void deleteProposalRemovesRow() {
        KnowledgeProposalEntity p = addOpenProposal();

        service.deleteProposal(p.getId());

        verify(proposalRepo).delete(p);
    }

    @Test
    void reindexBaseOnlyMismatchedSkipsWithoutProbedDimensions() {
        KnowledgeBaseEntity kb = addKb("kb");
        when(kbRepo.findById(kb.getId())).thenReturn(java.util.Optional.of(kb));

        ReindexResult result = service.reindexBase(kb.getId(), true);

        assertEquals(0, result.queued(), "端点未探测过维度时算不出失配，宁可不重建");
        verify(entryRepo, never()).findMismatched(anyLong(), anyLong(), anyInt());
    }

    @Test
    void reindexBaseOnlyMismatchedUsesResolvedEndpointLineage() {
        KnowledgeBaseEntity kb = addKb("kb");
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

    @Test
    void pageByBaseReturnsLightweightSummariesAndClampsSize() {
        KnowledgeBaseEntity kb = addKb("kb");
        when(kbRepo.findById(kb.getId())).thenReturn(java.util.Optional.of(kb));
        KnowledgeEntryRepository.EntrySummaryRef ref = new KnowledgeEntryRepository.EntrySummaryRef() {
            @Override public Long getId() { return 7L; }
            @Override public Long getKbId() { return kb.getId(); }
            @Override public String getName() { return "RabbitMQ 集群部署"; }
            @Override public String getDocType() { return "TECH"; }
            @Override public String getTags() { return "ops, mq"; }
            @Override public String getSource() { return "file"; }
            @Override public String getExternalId() { return null; }
            @Override public String getIndexStatus() { return "ready"; }
            @Override public String getIndexError() { return null; }
            @Override public String getSummary() { return null; }
            @Override public String getOwner() { return null; }
            @Override public int getHitCount() { return 3; }
            @Override public String getStatus() { return "active"; }
            @Override public String getSourceUri() { return "ops/rabbitmq.md"; }
            @Override public int getContentVersion() { return 2; }
            @Override public Instant getUpdatedAt() { return Instant.parse("2026-10-24T00:00:00Z"); }
        };
        when(entryRepo.pageSummariesByBase(anyLong(), anyString(), anyString(), any(), any(), any(),
                any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(ref)));

        when(access.readableEntryIds(any())).thenReturn(List.of(ref.getId()));
        PageView<EntrySummaryView> page = service.pageByBase(
                kb.getId(), "Rabbit", "TECH", null, null, null, -1, 500);

        assertEquals(0, page.page(), "负页码回落 0");
        assertEquals(100, page.size(), "页大小上限 100");
        assertEquals(1, page.total());
        EntrySummaryView item = page.items().get(0);
        assertEquals("RabbitMQ 集群部署", item.name());
        assertEquals(List.of("ops", "mq"), item.tags(), "逗号标签拆成列表");
        assertEquals("file", item.source());
        assertEquals(2, item.contentVersion());
    }
}
