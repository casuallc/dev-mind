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
import java.util.Collection;
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
 * 知识库核心（CAP-04 条目/提案 + CAP-44 库容器）。CAP-94 起知识库去项目化：库是完全
 * 独立的平台资产（无 scope/project 归属），条目归属只看 kbId；消费统一为显式绑库检索
 * （CAP-92 下线 FULL 全量注入，CAP-94 删除一切按项目自动圈范围逻辑）。
 * 会话层不直接依赖本服务——CAP-33 起经 {@link KnowledgeContextProvider}（common ContextProvider SPI）
 * 被装配管线收集。
 */
@Service
public class KnowledgeBaseService {
    private final com.devmind.knowledge.access.KnowledgeAccessService access;

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final KnowledgeBaseRepository kbRepo;
    private final KnowledgeEntryRepository entryRepo;
    private final KnowledgeChunkRepository chunkRepo;
    private final KnowledgeProposalRepository proposalRepo;
    private final KnowledgeImportFileRepository importFileRepo;
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
                                NotificationPublisher notificationPublisher,
                                ApplicationEventPublisher eventPublisher,
                                EmbeddingResolver resolver,
                                ObjectProvider<ModelEndpointProvider> endpointProviders,
                                KnowledgeVersionService versionService,
                                KnowledgeIndexService indexService,
                                com.devmind.knowledge.retrieve.KnowledgeSearchService searchService,
                                @Lazy AsyncTaskService taskService,
                                com.devmind.knowledge.access.KnowledgeAccessService access) {
        this.access = access;
        this.kbRepo = kbRepo;
        this.entryRepo = entryRepo;
        this.chunkRepo = chunkRepo;
        this.proposalRepo = proposalRepo;
        this.importFileRepo = importFileRepo;
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
        var readable = new java.util.HashSet<>(access.readableBaseIds(null));
        return kbRepo.findAll().stream()
                .filter(k -> readable.contains(k.getId()))
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
        access.initializeBase(kb);
        applyBase(kb, req, true);
        Instant now = Instant.now();
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        return baseView(kbRepo.save(kb));
    }

    @Transactional
    public KnowledgeBaseView updateBase(Long id, KnowledgeBaseRequest req) {
        access.requireBase(id, com.devmind.common.knowledge.KnowledgeAccessPolicy.Action.EDIT);
        KnowledgeBaseEntity kb = requireBase(id);
        validateBase(req, false);
        applyBase(kb, req, false);
        kb.setUpdatedAt(Instant.now());
        return baseView(kbRepo.save(kb));
    }

    /** 删除库：非空库需 force=true（级联删条目与分块）。 */
    @Transactional
    public void deleteBase(Long id, boolean force) {
        access.requireBase(id, com.devmind.common.knowledge.KnowledgeAccessPolicy.Action.MANAGE_ACL);
        KnowledgeBaseEntity kb = requireBase(id);
        access.requireEntries(access.actor(), entryRepo.findAccessRefsByKbIdIn(List.of(id)).stream()
                .map(KnowledgeEntryRepository.AccessRef::getId).toList());
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
        // CAP-48 FR-04 库级端点覆盖：显式传 null 或缺省 = 平台默认端点
        if (req.modelEndpointId() != null) {
            kb.setModelEndpointId(req.modelEndpointId() > 0 ? req.modelEndpointId() : null);
        }
        if (req.status() != null && !req.status().isBlank()) {
            kb.setStatus(req.status());
        }
    }

    private KnowledgeBaseView baseView(KnowledgeBaseEntity kb) {
        List<Long> ids = access.readableEntryIds(List.of(kb.getId()));
        return EntryViews.base(kb, ids.size(),
                ids.isEmpty() ? 0 : chunkRepo.countByEntryIdIn(ids), endpointNameOf(kb),
                ids.size() == entryRepo.countByKbId(kb.getId()) ? indexStatsOf(kb)
                        : new KnowledgeBaseView.IndexStats(
                            ids.isEmpty() ? 0 : entryRepo.countByIdInAndIndexStatus(ids, "ready"),
                            ids.isEmpty() ? 0 : entryRepo.countByIdInAndIndexStatus(ids, "pending"),
                            ids.isEmpty() ? 0 : entryRepo.countByIdInAndIndexStatus(ids, "failed"),
                            ids.isEmpty() ? 0 : entryRepo.countByIdInAndIndexStatus(ids, "disabled"), 0),
                access.effectiveActions(kb.getId()));
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
        access.requireBase(id, com.devmind.common.knowledge.KnowledgeAccessPolicy.Action.EDIT);
        access.requireEntries(access.actor(), entryRepo.findAccessRefsByKbIdIn(List.of(id)).stream()
                .map(KnowledgeEntryRepository.AccessRef::getId).toList());
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
        access.requireBase(id, com.devmind.common.knowledge.KnowledgeAccessPolicy.Action.READ);
        return kbRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "知识库不存在: " + id));
    }

    // ---------------- 条目（FR-02/FR-03，CAP-44 按库归属） ----------------

    public List<EntryView> list(String status) {
        List<Long> readable = access.readableEntryIds(null);
        List<KnowledgeEntryEntity> list = readable.isEmpty() ? List.of() : entryRepo.findAllById(readable);
        Map<Long, KnowledgeBaseEntity> kbMap = kbMap();
        return list.stream()
                .filter(e -> status == null || status.isBlank() || status.equals(e.getStatus()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(e -> EntryViews.entry(e, kbMap.get(e.getKbId())))
                .toList();
    }

    /** 库内条目列表（CAP-44 FR-07）。保留给全量导出/脚本，新界面用 {@link #pageByBase}。 */
    public List<EntryView> listByBase(Long kbId) {
        KnowledgeBaseEntity kb = requireBase(kbId);
        List<Long> readable = access.readableEntryIds(List.of(kbId));
        return (readable.isEmpty() ? List.<KnowledgeEntryEntity>of() : entryRepo.findAllById(readable)).stream()
                .map(e -> EntryViews.entry(e, kb)).toList();
    }

    /** CAP-86 FR-38 库内条目分页摘要：只查列表字段，过滤与排序下推数据库。 */
    public PageView<EntrySummaryView> pageByBase(Long kbId, String keyword, String docType,
                                                 String source, String indexStatus, String status,
                                                 int page, int size) {
        requireBase(kbId);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<Long> readable = access.readableEntryIds(List.of(kbId));
        if (readable.isEmpty()) return new PageView<>(List.of(), 0, safePage, safeSize);
        Page<KnowledgeEntryRepository.EntrySummaryRef> result = entryRepo.pageSummariesByBase(
                kbId, blankToNull(keyword), blankToNull(docType), blankToNull(source),
                blankToNull(indexStatus), blankToNull(status),
                readable, PageRequest.of(safePage, safeSize));
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
        access.requireBase(kb.getId(), com.devmind.common.knowledge.KnowledgeAccessPolicy.Action.EDIT);
        validateEntry(req, kb);
        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        e.setKbId(kb.getId());
        applyEntry(e, req, kb);
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("active"); // 未显式传状态默认 active，否则永不参与检索
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
        access.requireEntry(id, com.devmind.common.knowledge.KnowledgeAccessPolicy.Action.EDIT);
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
        access.requireEntry(id, com.devmind.common.knowledge.KnowledgeAccessPolicy.Action.EDIT);
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
        access.requireEntry(id, com.devmind.common.knowledge.KnowledgeAccessPolicy.Action.EDIT);
        KnowledgeEntryEntity e = requireEntry(id);
        e.setLastReviewedAt(Instant.now());
        e = entryRepo.save(e);
        return EntryViews.entry(e, e.getKbId() == null ? null : kbRepo.findById(e.getKbId()).orElse(null));
    }

    /** 索引重试（CAP-44 FR-04）：重置 pending 并触发异步重索引。 */
    @Transactional
    public EntryView reindex(Long id) {
        access.requireEntry(id, com.devmind.common.knowledge.KnowledgeAccessPolicy.Action.EDIT);
        KnowledgeEntryEntity e = requireEntry(id);
        e.setIndexStatus(KnowledgeEntryEntity.INDEX_PENDING);
        e.setIndexError(null);
        e.setUpdatedAt(Instant.now());
        EntryView view = EntryViews.entry(entryRepo.save(e),
                e.getKbId() == null ? null : kbRepo.findById(e.getKbId()).orElse(null));
        publishContentChanged(e);
        return view;
    }

    /** 解析目标库（CAP-94：kbId 必填，不再按 scope/projectId 兜底找/建经验库）。 */
    private KnowledgeBaseEntity resolveTargetKb(EntryRequest req) {
        if (req.kbId() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "必须指定所属知识库 kbId");
        }
        return requireBase(req.kbId());
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
        if (e.getPath() == null || e.getPath().isBlank()) {
            String name = e.getName() == null ? "entry" : e.getName().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
            e.setPath("kb-" + kb.getId() + "/" + name + ".md");
        }
    }

    private static final Set<String> DOC_TYPES = Set.of(
            KnowledgeEntryEntity.DOC_TECH, KnowledgeEntryEntity.DOC_MAINT,
            KnowledgeEntryEntity.DOC_FAQ, KnowledgeEntryEntity.DOC_ARTIFACT, "ARCHITECTURE", "TEST", "BID");

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

    /** 全文检索（FR-08/FR-14）：范围 = 全部 active 库，统一走 Lucene 混合检索（不再 LIKE 全表）。 */
    public List<EntryView> search(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        List<Long> kbIds = access.readableBaseIds(null);
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
            if (!access.readableEntryIds(List.of(e.getKbId())).contains(e.getId())) continue;
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

    // ---------------- 检索辅助（CAP-92 场景绑库检索） ----------------

    /** 库 id → 库名（装配清单/提案视图展示用；不存在的 id 跳过）。 */
    public Map<Long, String> baseNames(List<Long> kbIds) {
        Map<Long, String> names = new HashMap<>();
        if (kbIds == null || kbIds.isEmpty()) {
            return names;
        }
        for (KnowledgeBaseEntity kb : kbRepo.findAllById(access.readableBaseIds(kbIds))) {
            names.put(kb.getId(), kb.getName());
        }
        return names;
    }

    /** 对本次检索命中的条目计数（FR-07 清理依据；dryRun 不调用）。 */
    @Transactional
    public void bumpHitsByEntryIds(Collection<Long> entryIds) {
        if (entryIds == null || entryIds.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (KnowledgeEntryEntity e : entryRepo.findAllById(entryIds)) {
            e.setHitCount(e.getHitCount() + 1);
            e.setUpdatedAt(now);
            entryRepo.save(e);
        }
    }

    public boolean canMaterializeKnowledge(Collection<Long> entryIds) {
        return access.canMaterializeForRunner(entryIds);
    }

    // ---------------- 提案（FR-05/FR-06） ----------------

    public List<ProposalView> listProposals(String status) {
        List<KnowledgeProposalEntity> list = (status == null || status.isBlank())
                ? proposalRepo.findByOrderByCreatedAtDesc()
                : proposalRepo.findByStatusOrderByCreatedAtDesc(status);
        Map<Long, String> kbNames = baseNames(list.stream()
                .map(KnowledgeProposalEntity::getAdoptedKbId).filter(Objects::nonNull).toList());
        String actor = access.actor();
        return list.stream().filter(p -> actor.equals(p.getCreatedBy()))
                .map(p -> EntryViews.proposal(p, kbNames)).toList();
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
        p.setCreatedBy(access.actor());
        p.setTitle(req.title());
        p.setContentMd(req.contentMd());
        p.setSourceSessionId(req.sourceSessionId());
        p.setSourceProject(blankToNull(req.sourceProject()));
        p.setStatus("open");
        p.setCreatedAt(Instant.now());
        p = proposalRepo.save(p);
        // CAP-95: no broadcast of a private proposal title to unspecified recipients.
        return EntryViews.proposal(p, null);
    }

    /** 采纳（CAP-94）：显式指定目标库 targetKbId（必选；不存在 404 / 已归档 409）。 */
    @Transactional
    public ProposalView adopt(Long id, Long targetKbId) {
        access.requireBase(targetKbId, com.devmind.common.knowledge.KnowledgeAccessPolicy.Action.EDIT);
        KnowledgeProposalEntity p = requireProposal(id);
        if(p.getSourceSessionId()!=null && !p.getSourceSessionId().isBlank())
            throw new DevMindException(ErrorCode.CONFLICT,"此生成提案的会话来源尚未完成核验，不能作为独立本地资料采纳");
        if (!"open".equals(p.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "提案已处理（" + p.getStatus() + "）");
        }
        if (targetKbId == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "采纳必须指定目标知识库 targetKbId");
        }
        KnowledgeBaseEntity kb = requireBase(targetKbId);
        if (!KnowledgeBaseEntity.STATUS_ACTIVE.equals(kb.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "目标知识库已归档，不能采纳: " + kb.getName());
        }

        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        e.setKbId(kb.getId());
        e.setName(p.getTitle());
        e.setContentMd(p.getContentMd());
        e.setContentHash(sha256(p.getContentMd()));
        e.setSourceProject(p.getSourceProject());
        e.setStatus("active");
        e.setHitCount(0);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        e.setPath(defaultPath(e, kb));
        entryRepo.save(e);
        publishContentChanged(e);

        p.setStatus("adopted");
        p.setAdoptedKbId(kb.getId());
        p.setAdoptedAt(now);
        return EntryViews.proposal(proposalRepo.save(p), Map.of(kb.getId(), kb.getName()));
    }

    @Transactional
    public ProposalView reject(Long id) {
        KnowledgeProposalEntity p = requireProposal(id);
        if (!"open".equals(p.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "提案已处理（" + p.getStatus() + "）");
        }
        p.setStatus("rejected");
        p.setAdoptedAt(Instant.now());
        return EntryViews.proposal(proposalRepo.save(p), null);
    }

    /** 删除提案（硬删除，不限状态；已采纳生成的知识条目不受影响）。 */
    @Transactional
    public void deleteProposal(Long id) {
        proposalRepo.delete(requireProposal(id));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private String defaultPath(KnowledgeEntryEntity e, KnowledgeBaseEntity kb) {
        String name = e.getName() == null ? "entry" : e.getName().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        return "kb-" + kb.getId() + "/" + name + ".md";
    }

    private Map<Long, KnowledgeBaseEntity> kbMap() {
        Map<Long, KnowledgeBaseEntity> map = new HashMap<>();
        for (KnowledgeBaseEntity kb : kbRepo.findAll()) {
            map.put(kb.getId(), kb);
        }
        return map;
    }

    private KnowledgeEntryEntity requireEntry(Long id) {
        access.requireEntry(id, com.devmind.common.knowledge.KnowledgeAccessPolicy.Action.READ);
        return entryRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "条目不存在: " + id));
    }

    private KnowledgeProposalEntity requireProposal(Long id) {
        String actor = access.actor();
        return proposalRepo.findById(id).filter(p -> actor.equals(p.getCreatedBy()))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "提案不存在: " + id));
    }
}
