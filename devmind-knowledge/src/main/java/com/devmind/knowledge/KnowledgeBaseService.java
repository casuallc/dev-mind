package com.devmind.knowledge;

import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
import com.devmind.common.notification.NotificationEvent;
import com.devmind.knowledge.dto.EntryRequest;
import com.devmind.knowledge.dto.EntrySummaryView;
import com.devmind.knowledge.dto.EntryView;
import com.devmind.knowledge.dto.EntryViews;
import com.devmind.knowledge.dto.KnowledgeBaseRequest;
import com.devmind.knowledge.dto.KnowledgeBaseView;
import com.devmind.knowledge.dto.KnowledgeChunkView;
import com.devmind.knowledge.dto.KnowledgeVersionView;
import com.devmind.knowledge.dto.PreviewResult;
import com.devmind.knowledge.dto.ProposalRequest;
import com.devmind.knowledge.dto.ProposalView;
import com.devmind.knowledge.dto.ReindexResult;
import com.devmind.knowledge.embedding.EmbeddingResolver;
import com.devmind.knowledge.index.KnowledgeIndexService;
import com.devmind.knowledge.index.KnowledgeRebuildTaskHandler;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.model.KnowledgeImportFileEntity;
import com.devmind.knowledge.model.KnowledgeProposalEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeChunkRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import com.devmind.knowledge.repo.KnowledgeImportFileRepository;
import com.devmind.knowledge.repo.KnowledgeProposalRepository;
import com.devmind.notification.NotificationPublisher;
import com.devmind.project.ProjectService;
import com.devmind.project.model.Project;
import com.devmind.task.api.TaskOptions;
import com.devmind.task.service.AsyncTaskService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库核心（CAP-04 条目/提案/注入选择 + CAP-44 库容器）。
 * 库为一等归属：条目的 scope/projectId 语义由所属库派生；FULL 库参与 CLAUDE.md 注入
 * （口径同 CAP-04：global FULL 库按项目 tags 过滤 + 本项目 FULL 库全量），RAG 库仅检索。
 * 会话层不直接依赖本服务——CAP-33 起经 {@link KnowledgeContextProvider}（common ContextProvider SPI）
 * 被装配管线收集。
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final KnowledgeBaseRepository kbRepo;
    private final KnowledgeEntryRepository entryRepo;
    private final KnowledgeChunkRepository chunkRepo;
    private final KnowledgeProposalRepository proposalRepo;
    private final KnowledgeImportFileRepository importFileRepo;
    private final ProjectService projectService;
    private final NotificationPublisher notificationPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final EmbeddingResolver resolver;
    private final ObjectProvider<ModelEndpointProvider> endpointProviders;
    private final KnowledgeVersionService versionService;
    private final KnowledgeIndexService indexService;
    private final com.devmind.knowledge.retrieve.KnowledgeSearchService searchService;
    private final AsyncTaskService taskService;

    public KnowledgeBaseService(KnowledgeBaseRepository kbRepo,
                                KnowledgeEntryRepository entryRepo,
                                KnowledgeChunkRepository chunkRepo,
                                KnowledgeProposalRepository proposalRepo,
                                KnowledgeImportFileRepository importFileRepo,
                                ProjectService projectService,
                                NotificationPublisher notificationPublisher,
                                ApplicationEventPublisher eventPublisher,
                                EmbeddingResolver resolver,
                                ObjectProvider<ModelEndpointProvider> endpointProviders,
                                KnowledgeVersionService versionService,
                                KnowledgeIndexService indexService,
                                com.devmind.knowledge.retrieve.KnowledgeSearchService searchService,
                                @Lazy AsyncTaskService taskService) {
        this.kbRepo = kbRepo;
        this.entryRepo = entryRepo;
        this.chunkRepo = chunkRepo;
        this.proposalRepo = proposalRepo;
        this.importFileRepo = importFileRepo;
        this.projectService = projectService;
        this.notificationPublisher = notificationPublisher;
        this.eventPublisher = eventPublisher;
        this.resolver = resolver;
        this.endpointProviders = endpointProviders;
        this.versionService = versionService;
        this.indexService = indexService;
        this.searchService = searchService;
        this.taskService = taskService;
    }

    // ---------------- 知识库（CAP-44 FR-01/FR-07） ----------------

    public List<KnowledgeBaseView> listBases() {
        return kbRepo.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::baseView).toList();
    }

    public KnowledgeBaseView getBase(Long id) {
        return baseView(requireBase(id));
    }

    @Transactional
    public KnowledgeBaseView createBase(KnowledgeBaseRequest req) {
        validateBase(req, true);
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        applyBase(kb, req, true);
        Instant now = Instant.now();
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        return baseView(kbRepo.save(kb));
    }

    @Transactional
    public KnowledgeBaseView updateBase(Long id, KnowledgeBaseRequest req) {
        KnowledgeBaseEntity kb = requireBase(id);
        validateBase(req, false);
        applyBase(kb, req, false);
        kb.setUpdatedAt(Instant.now());
        return baseView(kbRepo.save(kb));
    }

    /** 删除库：非空库需 force=true（级联删条目与分块）。 */
    @Transactional
    public void deleteBase(Long id, boolean force) {
        KnowledgeBaseEntity kb = requireBase(id);
        List<KnowledgeEntryEntity> entries = entryRepo.findByKbIdOrderByCreatedAtDesc(id);
        if (!entries.isEmpty() && !force) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "知识库内还有 " + entries.size() + " 个条目，force=true 才会级联删除");
        }
        for (KnowledgeEntryEntity e : entries) {
            chunkRepo.deleteByEntryId(e.getId());
            versionService.deleteAll(e.getId());
            indexService.removeFromIndex(e.getId());
            releaseStoredFiles(e.getId());
            entryRepo.delete(e);
        }
        kbRepo.delete(kb);
    }

    private void validateBase(KnowledgeBaseRequest req, boolean create) {
        if (create && (req.name() == null || req.name().isBlank())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "知识库名称必填");
        }
        String scope = req.scope() == null || req.scope().isBlank()
                ? KnowledgeBaseEntity.SCOPE_GLOBAL : req.scope();
        if (!KnowledgeBaseEntity.SCOPE_GLOBAL.equals(scope) && !KnowledgeBaseEntity.SCOPE_PROJECT.equals(scope)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "scope 必须是 global 或 project");
        }
        if (KnowledgeBaseEntity.SCOPE_PROJECT.equals(scope)
                && (req.projectId() == null || req.projectId().isBlank()) && create) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "project 范围必须指定项目");
        }
        if (req.injectMode() != null && !req.injectMode().isBlank()
                && !KnowledgeBaseEntity.INJECT_FULL.equals(req.injectMode())
                && !KnowledgeBaseEntity.INJECT_RAG.equals(req.injectMode())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "injectMode 必须是 FULL 或 RAG");
        }
        if (req.modelEndpointId() != null && req.modelEndpointId() > 0) {
            requireEmbeddingEndpoint(req.modelEndpointId());
        }
    }

    /**
     * CAP-48 FR-11 守卫：向量解析链只认 EMBEDDING 端点。绑定时就拒绝，别等索引阶段拿对话模型名
     * 去打 {@code /embeddings} 才以 {@code index_error} 的形式暴露（那时血缘里已经落进脏模型名）。
     *
     * <p>端点为"不存在/已停用"时不在此判断——那是既有的「解析时回落平台默认端点」路径，不新增失败面。</p>
     */
    private void requireEmbeddingEndpoint(long endpointId) {
        ModelEndpointProvider provider = endpointProviders.getIfAvailable();
        if (provider == null) {
            return; // devmind-model 未装配：无端点资源可校验（走 CAP-44 配置化路径）
        }
        provider.activeEndpoint(endpointId).filter(v -> !v.embedding()).ifPresent(v -> {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "知识库只能绑定向量（EMBEDDING）端点，「" + v.name() + "」是 " + v.kind() + " 类型");
        });
    }

    private void applyBase(KnowledgeBaseEntity kb, KnowledgeBaseRequest req, boolean create) {
        if (req.name() != null && !req.name().isBlank()) {
            kb.setName(req.name());
        }
        if (req.description() != null) {
            kb.setDescription(req.description());
        }
        if (req.scope() != null && !req.scope().isBlank()) {
            kb.setScope(req.scope());
        }
        if (req.projectId() != null) {
            kb.setProjectId(req.projectId().isBlank() ? null : req.projectId());
        }
        if (KnowledgeBaseEntity.SCOPE_GLOBAL.equals(kb.getScope())) {
            kb.setProjectId(null);
        }
        if (req.injectMode() != null && !req.injectMode().isBlank()) {
            kb.setInjectMode(req.injectMode());
        }
        // CAP-48 FR-04 库级端点覆盖：显式传 null 或缺省 = 平台默认端点
        if (req.modelEndpointId() != null) {
            kb.setModelEndpointId(req.modelEndpointId() > 0 ? req.modelEndpointId() : null);
        }
        if (req.status() != null && !req.status().isBlank()) {
            kb.setStatus(req.status());
        }
    }

    private KnowledgeBaseView baseView(KnowledgeBaseEntity kb) {
        String projectName = null;
        if (kb.getProjectId() != null && !kb.getProjectId().isBlank()) {
            try {
                projectName = projectService.requireProject(kb.getProjectId()).name();
            } catch (Exception e) {
                log.debug("知识库项目名解析失败: {}", e.getMessage());
            }
        }
        return EntryViews.base(kb, projectName, entryRepo.countByKbId(kb.getId()),
                chunkRepo.countByKbId(kb.getId()), endpointNameOf(kb), indexStatsOf(kb));
    }

    /** 实际生效的端点名（含回落平台默认的结果）；无可用端点 → null，UI 据此显示"未配置向量端点" */
    private String endpointNameOf(KnowledgeBaseEntity kb) {
        ModelEndpointProvider provider = endpointProviders.getIfAvailable();
        if (provider == null) {
            // CAP-44 旧全局配置路径：没有端点资源，展示配置来源即可
            return resolver.resolve(kb.getModelEndpointId()).available()
                    ? "devmind.knowledge.embedding.*（配置）" : null;
        }
        // FR-11：只认向量端点——否则知识库页会把一个对话端点显示成"当前生效向量端点"，
        // 与实际解析结果（后端拿的是平台默认向量端点）不符
        return provider.resolve(kb.getModelEndpointId())
                .filter(ModelEndpointView::embedding)
                .map(ModelEndpointView::name).orElse(null);
    }

    /**
     * CAP-48 FR-06 索引健康度：状态分布 + 失配数。
     * 失配判定需要"端点声明的维度"，所以只有端点存在、且探测过维度时才算得出来；
     * 算不出来时返回 0（宁可不报警，也不要误报把用户引向无意义的重建）。
     */
    private KnowledgeBaseView.IndexStats indexStatsOf(KnowledgeBaseEntity kb) {
        long ready = 0;
        long pending = 0;
        long failed = 0;
        long disabled = 0;
        for (Object[] row : entryRepo.countByIndexStatus(kb.getId())) {
            String status = row[0] == null ? "" : String.valueOf(row[0]);
            long n = row[1] instanceof Number num ? num.longValue() : 0L;
            switch (status) {
                case KnowledgeEntryEntity.INDEX_READY -> ready = n;
                case KnowledgeEntryEntity.INDEX_PENDING -> pending = n;
                case KnowledgeEntryEntity.INDEX_FAILED -> failed = n;
                case KnowledgeEntryEntity.INDEX_DISABLED -> disabled = n;
                default -> { }
            }
        }
        EmbeddingResolver.Resolution r = resolver.resolve(kb.getModelEndpointId());
        long mismatched = 0;
        if (r.available() && r.endpointId() != null && r.dimensions() != null) {
            mismatched = entryRepo.countMismatched(kb.getId(), r.endpointId(), r.dimensions());
        }
        return new KnowledgeBaseView.IndexStats(ready, pending, failed, disabled, mismatched);
    }

    /**
     * CAP-48 FR-08 重建索引：把库里条目重新置 pending 并投递索引事件（异步）。
     *
     * @param onlyMismatched true = 只重建血缘失配的条目（换端点/换模型后的修复入口，
     *                       避免为了修几十条失配把全库几千条重跑一遍）
     */
    @Transactional
    public ReindexResult reindexBase(Long id, boolean onlyMismatched) {
        KnowledgeBaseEntity kb = requireBase(id);
        if (!onlyMismatched) {
            long total = entryRepo.countByKbId(id);
            String taskId = taskService.submit(KnowledgeRebuildTaskHandler.TASK_TYPE,
                    "{\"kbId\":" + id + "}",
                    TaskOptions.builder()
                            .name("重建知识库索引 " + kb.getName())
                            .retryMaxAttempts(1)
                            .timeoutMs(6 * 60 * 60_000L)
                            .build());
            log.info("知识库 {} 全量重建已提交任务: task={} entries={}", id, taskId, total);
            return new ReindexResult(total);
        }
        EmbeddingResolver.Resolution r = resolver.resolve(kb.getModelEndpointId());
        if (!r.available() || r.endpointId() == null || r.dimensions() == null) {
            log.info("全库失配重建跳过：知识库 {} 无可用端点或端点未探测过维度", id);
            return new ReindexResult(0);
        }
        List<KnowledgeEntryEntity> targets = entryRepo.findMismatched(id, r.endpointId(), r.dimensions());
        Instant now = Instant.now();
        for (KnowledgeEntryEntity e : targets) {
            e.setIndexStatus(KnowledgeEntryEntity.INDEX_PENDING);
            e.setIndexError(null);
            e.setUpdatedAt(now);
            entryRepo.save(e);
        }
        // 事件在事务提交后投递（KnowledgeIndexListener 是 AFTER_COMMIT），异步线程才读得到上面这些写
        targets.forEach(this::publishContentChanged);
        log.info("知识库 {} 失配重建已入队: {} 条", id, targets.size());
        return new ReindexResult(targets.size());
    }

    /** CAP-84 M2：管理端全量重建所有知识库索引（异步任务，万级条目可取消）。 */
    public RebuildSubmit submitRebuildAll() {
        long total = entryRepo.count();
        String taskId = taskService.submit(KnowledgeRebuildTaskHandler.TASK_TYPE, "{}",
                TaskOptions.builder()
                        .name("重建全部知识库索引")
                        .retryMaxAttempts(1)
                        .timeoutMs(12 * 60 * 60_000L)
                        .build());
        log.info("全部知识库重建已提交任务: task={} entries={}", taskId, total);
        return new RebuildSubmit(taskId, total);
    }

    public record RebuildSubmit(String taskId, long entries) {
    }

    private KnowledgeBaseEntity requireBase(Long id) {
        return kbRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "知识库不存在: " + id));
    }

    /** 按 legacy scope/projectId 解析目标经验库（FULL）；不存在则创建（兼容旧调用与提案采纳）。 */
    private KnowledgeBaseEntity resolveExperienceKb(String scope, String projectId) {
        String s = scope == null || scope.isBlank() ? KnowledgeBaseEntity.SCOPE_GLOBAL : scope;
        List<KnowledgeBaseEntity> candidates = KnowledgeBaseEntity.SCOPE_PROJECT.equals(s)
                ? kbRepo.findByScopeAndProjectIdAndInjectModeAndStatus(
                        s, projectId, KnowledgeBaseEntity.INJECT_FULL, KnowledgeBaseEntity.STATUS_ACTIVE)
                : kbRepo.findByScopeAndInjectModeAndStatus(
                        s, KnowledgeBaseEntity.INJECT_FULL, KnowledgeBaseEntity.STATUS_ACTIVE);
        if (!candidates.isEmpty()) {
            return candidates.get(0);
        }
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setScope(s);
        if (KnowledgeBaseEntity.SCOPE_PROJECT.equals(s)) {
            kb.setProjectId(projectId);
            kb.setName(projectKbName(projectId));
        } else {
            kb.setName(KnowledgeBaseMigration.GLOBAL_KB_NAME);
        }
        kb.setInjectMode(KnowledgeBaseEntity.INJECT_FULL);
        kb.setDescription("经验库（自动创建）");
        Instant now = Instant.now();
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        return kbRepo.save(kb);
    }

    private String projectKbName(String projectId) {
        try {
            return projectService.requireProject(projectId).name() + "经验库";
        } catch (Exception e) {
            return "项目经验库(" + projectId + ")";
        }
    }

    // ---------------- 条目（FR-02/FR-03，CAP-44 按库归属） ----------------

    public List<EntryView> list(String scope, String projectId, String status) {
        List<KnowledgeEntryEntity> list = (status != null && !status.isBlank())
                ? entryRepo.findByStatusOrderByCreatedAtDesc(status)
                : entryRepo.findAll();
        Map<Long, KnowledgeBaseEntity> kbMap = kbMap();
        return list.stream()
                .filter(e -> scope == null || scope.isBlank() || scope.equals(derivedScope(e, kbMap)))
                .filter(e -> projectId == null || projectId.isBlank()
                        || projectId.equals(derivedProjectId(e, kbMap)))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(e -> EntryViews.entry(e, kbMap.get(e.getKbId())))
                .toList();
    }

    /** 库内条目列表（CAP-44 FR-07）。保留给全量导出/脚本，新界面用 {@link #pageByBase}。 */
    public List<EntryView> listByBase(Long kbId) {
        KnowledgeBaseEntity kb = requireBase(kbId);
        return entryRepo.findByKbIdOrderByCreatedAtDesc(kbId).stream()
                .map(e -> EntryViews.entry(e, kb)).toList();
    }

    /** CAP-86 FR-38 库内条目分页摘要：只查列表字段，过滤与排序下推数据库。 */
    public PageView<EntrySummaryView> pageByBase(Long kbId, String keyword, String docType,
                                                 String source, String indexStatus, String status,
                                                 int page, int size) {
        requireBase(kbId);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Page<KnowledgeEntryRepository.EntrySummaryRef> result = entryRepo.pageSummariesByBase(
                kbId, blankToNull(keyword), blankToNull(docType), blankToNull(source),
                blankToNull(indexStatus), blankToNull(status),
                PageRequest.of(safePage, safeSize));
        List<EntrySummaryView> items = result.getContent().stream()
                .map(r -> new EntrySummaryView(
                        r.getId(), r.getKbId(), r.getName(), r.getDocType(),
                        EntryViews.splitTags(r.getTags()), r.getSource(), r.getExternalId(),
                        r.getIndexStatus(), r.getIndexError(), r.getSummary(), r.getOwner(),
                        r.getHitCount(), r.getStatus(), r.getSourceUri(),
                        r.getContentVersion(), r.getUpdatedAt()))
                .toList();
        return new PageView<>(items, result.getTotalElements(), safePage, safeSize);
    }

    public EntryView getEntry(Long id) {
        KnowledgeEntryEntity e = requireEntry(id);
        return EntryViews.entry(e, e.getKbId() == null ? null : kbRepo.findById(e.getKbId()).orElse(null));
    }

    @Transactional
    public EntryView createEntry(EntryRequest req) {
        KnowledgeBaseEntity kb = resolveTargetKb(req);
        validateEntry(req, kb);
        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        e.setKbId(kb.getId());
        applyEntry(e, req, kb);
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("active"); // 未显式传状态默认 active，否则永不注入
        e.setHitCount(0);
        e.setContentVersion(1);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(e.getCreatedAt());
        e = entryRepo.save(e);
        versionService.record(e, actorOrDefault(req.owner()), "手工创建");
        EntryView view = EntryViews.entry(e, kb);
        publishContentChanged(e);
        return view;
    }

    @Transactional
    public EntryView updateEntry(Long id, EntryRequest req) {
        KnowledgeEntryEntity e = requireEntry(id);
        KnowledgeBaseEntity kb = e.getKbId() == null ? resolveTargetKb(req) : requireBase(e.getKbId());
        boolean contentChanged = req.contentMd() != null && !req.contentMd().equals(e.getContentMd());
        boolean metadataChanged = metadataChanged(req, e);
        applyEntry(e, req, kb);
        if (contentChanged) {
            e.setContentVersion(e.getContentVersion() + 1);
            // CAP-85 FR-28：正文变了，上一次模型增强结果不再对应当前内容，下次批量增强重跑
            e.setEnrichedAt(null);
            e.setEnrichError(null);
            e.setSimHash(null);
        }
        if (metadataChanged) {
            // 人工改动分类/摘要/标签才锁定自动增强；表单回传未变化的值不算修正
            e.setAutoEnrich(false);
        }
        e.setUpdatedAt(Instant.now());
        e = entryRepo.save(e);
        if (contentChanged) {
            versionService.record(e, actorOrDefault(req.owner()), "手工更新");
        }
        // 名称/标签/状态/有效期都会进 Lucene 过滤与排序字段，因此不只在正文变化时重建
        publishContentChanged(e);
        return EntryViews.entry(e, kb);
    }

    @Transactional
    public void deleteEntry(Long id) {
        KnowledgeEntryEntity e = requireEntry(id);
        chunkRepo.deleteByEntryId(id);
        versionService.deleteAll(id);
        indexService.removeFromIndex(id);
        releaseStoredFiles(id);
        entryRepo.delete(e);
    }

    /**
     * 删除本地上传的原始文件（FR-06）。同一份文件可能被多个条目引用（内容寻址去重），
     * 仍有其它条目引用时保留；文件缺失不影响条目删除。
     */
    private void releaseStoredFiles(Long entryId) {
        for (KnowledgeImportFileEntity f : importFileRepo.findByEntryId(entryId)) {
            String stored = f.getStoredPath();
            if (stored == null || stored.isBlank() || importFileRepo.countByStoredPath(stored) > 1) {
                continue;
            }
            try {
                Files.deleteIfExists(Paths.get(stored));
            } catch (IOException ex) {
                log.warn("原始文件删除失败: entry={} path={} err={}", entryId, stored, ex.getMessage());
            }
        }
    }

    public List<KnowledgeVersionView> listVersions(Long entryId) {
        requireEntry(entryId);
        return versionService.listViews(entryId);
    }

    public KnowledgeVersionView.Detail getVersion(Long entryId, int contentVersion) {
        requireEntry(entryId);
        return versionService.get(entryId, contentVersion);
    }

    /** FR-15：文档当前索引分块（按 chunkIndex 升序），供详情「分块」页签与引用定位。 */
    public List<KnowledgeChunkView> listChunks(Long entryId) {
        requireEntry(entryId);
        return chunkRepo.findByEntryIdOrderByChunkIndexAsc(entryId).stream()
                .map(c -> new KnowledgeChunkView(c.getId(), c.getChunkIndex(), c.getHeadingPath(),
                        c.getStartOffset(), c.getEndOffset(), c.getTokenCount(), c.getContent()))
                .toList();
    }

    /** CAP-85 FR-29：人工复核。只写复核时间，不动 updatedAt（避免把老化计时重置成"刚更新"）。 */
    @Transactional
    public EntryView review(Long id) {
        KnowledgeEntryEntity e = requireEntry(id);
        e.setLastReviewedAt(Instant.now());
        e = entryRepo.save(e);
        return EntryViews.entry(e, e.getKbId() == null ? null : kbRepo.findById(e.getKbId()).orElse(null));
    }

    /** 索引重试（CAP-44 FR-04）：重置 pending 并触发异步重索引。 */
    @Transactional
    public EntryView reindex(Long id) {
        KnowledgeEntryEntity e = requireEntry(id);
        e.setIndexStatus(KnowledgeEntryEntity.INDEX_PENDING);
        e.setIndexError(null);
        e.setUpdatedAt(Instant.now());
        EntryView view = EntryViews.entry(entryRepo.save(e),
                e.getKbId() == null ? null : kbRepo.findById(e.getKbId()).orElse(null));
        publishContentChanged(e);
        return view;
    }

    /**
     * 解析目标库：kbId 优先；否则按 legacy scope/projectId 找/建经验库（FULL）。
     */
    private KnowledgeBaseEntity resolveTargetKb(EntryRequest req) {
        if (req.kbId() != null) {
            return requireBase(req.kbId());
        }
        String scope = req.scope() == null || req.scope().isBlank()
                ? KnowledgeBaseEntity.SCOPE_GLOBAL : req.scope();
        if (KnowledgeBaseEntity.SCOPE_PROJECT.equals(scope)
                && (req.projectId() == null || req.projectId().isBlank())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "project 范围必须指定项目");
        }
        return resolveExperienceKb(scope, req.projectId());
    }

    private void applyEntry(KnowledgeEntryEntity e, EntryRequest req, KnowledgeBaseEntity kb) {
        if (req.name() != null && !req.name().isBlank()) e.setName(req.name());
        if (req.contentMd() != null) {
            e.setContentMd(req.contentMd());
            e.setContentHash(sha256(req.contentMd()));
            if (req.summary() == null) {
                e.setSummary(summarize(req.contentMd()));
            }
        }
        if (req.tags() != null) e.setTags(EntryViews.joinTags(req.tags()));
        if (req.sourceProject() != null) e.setSourceProject(req.sourceProject().isBlank() ? null : req.sourceProject());
        if (req.status() != null && !req.status().isBlank()) e.setStatus(req.status());
        if (req.docType() != null && !req.docType().isBlank()) {
            e.setDocType(normalizeDocType(req.docType()));
        }
        if (req.sourceUri() != null) {
            e.setSourceUri(req.sourceUri().isBlank() ? null : req.sourceUri());
        }
        if (req.summary() != null) {
            e.setSummary(req.summary().isBlank() ? null : req.summary());
        }
        if (req.owner() != null) {
            e.setOwner(req.owner().isBlank() ? null : req.owner());
        }
        if (req.validFrom() != null) e.setValidFrom(req.validFrom());
        if (req.validTo() != null) e.setValidTo(req.validTo());
        // 历史列双写（值派生自 KB，单一事实源仍是库；旧 SQL/回滚期代码可读）
        e.setScope(kb.getScope());
        e.setProjectId(kb.getProjectId());
        if (e.getPath() == null || e.getPath().isBlank()) {
            String name = e.getName() == null ? "entry" : e.getName().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
            e.setPath(kb.getScope() + "/" + name + ".md");
        }
    }

    private static final Set<String> DOC_TYPES = Set.of(
            KnowledgeEntryEntity.DOC_TECH, KnowledgeEntryEntity.DOC_MAINT,
            KnowledgeEntryEntity.DOC_FAQ, KnowledgeEntryEntity.DOC_ARTIFACT);

    /** CAP-85 FR-28：显式传入且值确有变化 = 人工修正（空串/空列表也算显式清除）。 */
    private static boolean metadataChanged(EntryRequest req, KnowledgeEntryEntity e) {
        if (req.docType() != null && !req.docType().isBlank()
                && !normalizeDocType(req.docType()).equals(e.getDocType())) {
            return true;
        }
        if (req.summary() != null
                && !Objects.equals(req.summary().isBlank() ? null : req.summary(), e.getSummary())) {
            return true;
        }
        return req.tags() != null && !Objects.equals(EntryViews.joinTags(req.tags()), e.getTags());
    }

    private static String normalizeDocType(String docType) {
        String upper = docType.strip().toUpperCase(Locale.ROOT);
        if (!DOC_TYPES.contains(upper)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "docType 必须是 TECH/MAINT/FAQ/ARTIFACT");
        }
        return upper;
    }

    private static String summarize(String content) {
        if (content == null) {
            return null;
        }
        String oneLine = content.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300);
    }

    private static String actorOrDefault(String actor) {
        return actor == null || actor.isBlank() ? "manual" : actor;
    }

    private void validateEntry(EntryRequest req, KnowledgeBaseEntity kb) {
        if (req.name() == null || req.name().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "条目名称必填");
        }
        if (KnowledgeBaseEntity.SCOPE_PROJECT.equals(kb.getScope())
                && (kb.getProjectId() == null || kb.getProjectId().isBlank())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "project 范围知识库必须绑定项目");
        }
    }

    private void publishContentChanged(KnowledgeEntryEntity e) {
        if (e.getKbId() == null) {
            return;
        }
        eventPublisher.publishEvent(new EntryContentChangedEvent(e.getId(), e.getKbId()));
    }

    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 全文检索（FR-08/FR-14）：跨 global + 本项目库，统一走 Lucene 混合检索（不再 LIKE 全表）。 */
    public List<EntryView> search(String q, String projectId) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        List<Long> kbIds = searchScopeKbIds(projectId);
        if (kbIds.isEmpty()) {
            return List.of();
        }
        List<Long> orderedIds = searchService.searchEntryIds(q, kbIds, 50);
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        Map<Long, KnowledgeBaseEntity> kbMap = kbMap();
        Map<Long, EntryView> views = new HashMap<>();
        for (KnowledgeEntryEntity e : entryRepo.findAllById(orderedIds)) {
            views.put(e.getId(), EntryViews.entry(e, kbMap.get(e.getKbId())));
        }
        List<EntryView> out = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            EntryView v = views.get(id);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    /** 检索范围：全部 global 库 + 指定项目的 project 库（不限 injectMode，UI 检索语义）。 */
    private List<Long> searchScopeKbIds(String projectId) {
        List<Long> kbIds = new ArrayList<>();
        for (KnowledgeBaseEntity kb : kbRepo.findAll()) {
            if (KnowledgeBaseEntity.SCOPE_GLOBAL.equals(kb.getScope())
                    || (projectId != null && !projectId.isBlank() && projectId.equals(kb.getProjectId()))) {
                kbIds.add(kb.getId());
            }
        }
        return kbIds;
    }

    // ---------------- 注入选择（FR-03 标签过滤，CAP-44 经 FULL 库） ----------------

    /** 按项目选出将被注入的条目：全局 FULL 库按项目 tags 匹配 + 本项目 FULL 库全量。 */
    public List<EntryView> selectEntries(Project project) {
        return selectEntries(project == null ? null : project.id(),
                project == null ? null : project.tags());
    }

    /**
     * {@link #selectEntries(Project)} 的无 Project 对象变体（CAP-33 装配管线按
     * projectId+tags 入参调用，免回查项目表）。
     */
    public List<EntryView> selectEntries(String projectId, List<String> projectTags) {
        List<EntryView> used = new ArrayList<>();
        List<KnowledgeBaseEntity> globalKbs = kbRepo.findByScopeAndInjectModeAndStatus(
                KnowledgeBaseEntity.SCOPE_GLOBAL, KnowledgeBaseEntity.INJECT_FULL,
                KnowledgeBaseEntity.STATUS_ACTIVE);
        for (KnowledgeBaseEntity kb : globalKbs) {
            for (KnowledgeEntryEntity e : entryRepo.findByKbIdAndStatusOrderByCreatedAtDesc(kb.getId(), "active")) {
                List<String> tags = EntryViews.splitTags(e.getTags());
                if (!tags.isEmpty()) {
                    if (projectTags == null || projectTags.isEmpty()
                            || projectTags.stream().noneMatch(tags::contains)) {
                        continue; // 带标签但项目无匹配 → 不注入（防上下文膨胀）
                    }
                }
                used.add(EntryViews.entry(e, kb));
            }
        }
        if (projectId != null && !projectId.isBlank()) {
            List<KnowledgeBaseEntity> projectKbs = kbRepo.findByScopeAndProjectIdAndInjectModeAndStatus(
                    KnowledgeBaseEntity.SCOPE_PROJECT, projectId, KnowledgeBaseEntity.INJECT_FULL,
                    KnowledgeBaseEntity.STATUS_ACTIVE);
            for (KnowledgeBaseEntity kb : projectKbs) {
                for (KnowledgeEntryEntity e : entryRepo.findByKbIdAndStatusOrderByCreatedAtDesc(kb.getId(), "active")) {
                    used.add(EntryViews.entry(e, kb));
                }
            }
        }
        return used;
    }

    /**
     * 按 tags 显式选条目（CAP-33 FR-02 ①③层：场景绑定/请求追加的 knowledgeTags 命中）：
     * active 且条目 tags 与给定 tags 有交集；范围 = global FULL 库 + 指定项目的 project FULL 库。
     */
    public List<EntryView> selectByTags(List<String> tags, String projectId) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<KnowledgeBaseEntity> pool = new ArrayList<>(kbRepo.findByScopeAndInjectModeAndStatus(
                KnowledgeBaseEntity.SCOPE_GLOBAL, KnowledgeBaseEntity.INJECT_FULL,
                KnowledgeBaseEntity.STATUS_ACTIVE));
        if (projectId != null && !projectId.isBlank()) {
            pool.addAll(kbRepo.findByScopeAndProjectIdAndInjectModeAndStatus(
                    KnowledgeBaseEntity.SCOPE_PROJECT, projectId, KnowledgeBaseEntity.INJECT_FULL,
                    KnowledgeBaseEntity.STATUS_ACTIVE));
        }
        List<EntryView> used = new ArrayList<>();
        for (KnowledgeBaseEntity kb : pool) {
            for (KnowledgeEntryEntity e : entryRepo.findByKbIdAndStatusOrderByCreatedAtDesc(kb.getId(), "active")) {
                if (EntryViews.splitTags(e.getTags()).stream().anyMatch(tags::contains)) {
                    used.add(EntryViews.entry(e, kb));
                }
            }
        }
        return used;
    }

    /** 注入预览（FR-04）：同真实注入的组装结果，但不写盘、不加 hitCount。 */
    public PreviewResult preview(String projectId, String taskSpec) {
        Project project = projectId == null || projectId.isBlank()
                ? null : projectService.requireProject(projectId);
        List<EntryView> used = selectEntries(project);
        return new PreviewResult(ClaudeMd.assemble(used, taskSpec, null), used);
    }

    /** 对本次注入用到的条目计数（FR-07 清理依据）。 */
    @Transactional
    public void bumpHits(List<EntryView> used) {
        for (EntryView v : used) {
            entryRepo.findById(v.id()).ifPresent(e -> {
                e.setHitCount(e.getHitCount() + 1);
                e.setUpdatedAt(Instant.now());
                entryRepo.save(e);
            });
        }
    }

    // ---------------- 提案（FR-05/FR-06） ----------------

    public List<ProposalView> listProposals(String status) {
        List<KnowledgeProposalEntity> list = (status == null || status.isBlank())
                ? proposalRepo.findByOrderByCreatedAtDesc()
                : proposalRepo.findByStatusOrderByCreatedAtDesc(status);
        return list.stream().map(EntryViews::proposal).toList();
    }

    @Transactional
    public ProposalView createProposal(ProposalRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "提案标题必填");
        }
        if (req.contentMd() == null || req.contentMd().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "提案内容必填");
        }
        KnowledgeProposalEntity p = new KnowledgeProposalEntity();
        p.setTitle(req.title());
        p.setContentMd(req.contentMd());
        p.setTargetScope(req.targetScope() == null || req.targetScope().isBlank() ? "project" : req.targetScope());
        p.setTargetProjectId(req.targetProjectId());
        p.setSourceSessionId(req.sourceSessionId());
        p.setStatus("open");
        p.setCreatedAt(Instant.now());
        p = proposalRepo.save(p);
        // P2 通知：静默进中心（FR-05 不打扰）
        try {
            notificationPublisher.publish(NotificationEvent.of(
                    "KNOWLEDGE_PROPOSAL",
                    req.sourceSessionId() == null ? "-" : req.sourceSessionId(),
                    "有新的经验提案", req.title()));
        } catch (Exception e) {
            log.warn("提案通知发送失败: {}", e.getMessage());
        }
        return EntryViews.proposal(p);
    }

    /** 采纳：target=project → 项目经验库；target=global → 全局经验库（FR-06）。 */
    @Transactional
    public ProposalView adopt(Long id, String target, String projectId) {
        KnowledgeProposalEntity p = requireProposal(id);
        if (!"open".equals(p.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "提案已处理（" + p.getStatus() + "）");
        }
        if (target == null || (!"project".equals(target) && !"global".equals(target))) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "target 必须是 project 或 global");
        }
        String pid = "project".equals(target)
                ? (projectId != null && !projectId.isBlank() ? projectId : p.getTargetProjectId()) : null;
        if ("project".equals(target) && (pid == null || pid.isBlank())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "采纳到项目必须指定项目");
        }
        KnowledgeBaseEntity kb = resolveExperienceKb(target, pid);

        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        e.setKbId(kb.getId());
        e.setScope(kb.getScope());
        e.setProjectId(kb.getProjectId());
        e.setName(p.getTitle());
        e.setContentMd(p.getContentMd());
        e.setContentHash(sha256(p.getContentMd()));
        if ("global".equals(target)) {
            e.setSourceProject(p.getTargetProjectId());
        }
        e.setStatus("active");
        e.setHitCount(0);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        e.setPath(defaultPath(e));
        entryRepo.save(e);
        publishContentChanged(e);

        p.setStatus("adopted");
        p.setAdoptedTo(target);
        p.setAdoptedProjectId("project".equals(target) ? kb.getProjectId() : null);
        p.setAdoptedAt(now);
        ProposalView view = EntryViews.proposal(proposalRepo.save(p));
        return view;
    }

    @Transactional
    public ProposalView reject(Long id) {
        KnowledgeProposalEntity p = requireProposal(id);
        if (!"open".equals(p.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "提案已处理（" + p.getStatus() + "）");
        }
        p.setStatus("rejected");
        p.setAdoptedAt(Instant.now());
        ProposalView view = EntryViews.proposal(proposalRepo.save(p));
        return view;
    }

    /** 删除提案（硬删除，不限状态；已采纳生成的知识条目不受影响）。 */
    @Transactional
    public void deleteProposal(Long id) {
        proposalRepo.delete(requireProposal(id));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private String defaultPath(KnowledgeEntryEntity e) {
        String name = e.getName() == null ? "entry" : e.getName().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        return e.getScope() + "/" + name + ".md";
    }

    private Map<Long, KnowledgeBaseEntity> kbMap() {
        Map<Long, KnowledgeBaseEntity> map = new HashMap<>();
        for (KnowledgeBaseEntity kb : kbRepo.findAll()) {
            map.put(kb.getId(), kb);
        }
        return map;
    }

    private String derivedScope(KnowledgeEntryEntity e, Map<Long, KnowledgeBaseEntity> kbMap) {
        KnowledgeBaseEntity kb = e.getKbId() == null ? null : kbMap.get(e.getKbId());
        return kb != null ? kb.getScope() : e.getScope();
    }

    private String derivedProjectId(KnowledgeEntryEntity e, Map<Long, KnowledgeBaseEntity> kbMap) {
        KnowledgeBaseEntity kb = e.getKbId() == null ? null : kbMap.get(e.getKbId());
        return kb != null ? kb.getProjectId() : e.getProjectId();
    }

    private KnowledgeEntryEntity requireEntry(Long id) {
        return entryRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "条目不存在: " + id));
    }

    private KnowledgeProposalEntity requireProposal(Long id) {
        return proposalRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "提案不存在: " + id));
    }
}
