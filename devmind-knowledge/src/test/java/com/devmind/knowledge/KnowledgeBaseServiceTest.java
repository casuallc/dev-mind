package com.devmind.knowledge;

import com.devmind.knowledge.dto.EntryRequest;
import com.devmind.knowledge.dto.EntryView;
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
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseService 库模型重构（CAP-44 FR-03 注入口径零回归，mock repo 不拉起 Spring）：
 * selectEntries = global FULL 库按项目 tags 过滤 + 本项目 FULL 库全量；
 * legacy scope 创建条目 → 解析/兜底建经验库。
 */
class KnowledgeBaseServiceTest {

    private final List<KnowledgeBaseEntity> kbs = new ArrayList<>();
    private final List<KnowledgeEntryEntity> entries = new ArrayList<>();
    private KnowledgeBaseService service;
    private long kbSeq = 0;
    private long entrySeq = 0;

    @BeforeEach
    void setUp() {
        kbs.clear();
        entries.clear();
        KnowledgeBaseRepository kbRepo = mock(KnowledgeBaseRepository.class);
        KnowledgeEntryRepository entryRepo = mock(KnowledgeEntryRepository.class);
        KnowledgeChunkRepository chunkRepo = mock(KnowledgeChunkRepository.class);
        KnowledgeProposalRepository proposalRepo = mock(KnowledgeProposalRepository.class);
        ProjectService projectService = mock(ProjectService.class);
        NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

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
                projectService, notificationPublisher, eventPublisher);
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
}
