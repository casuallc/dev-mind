package com.devmind.knowledge;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.FeishuDocFetcher;
import com.devmind.common.integration.FeishuDocFetcher.FeishuDoc;
import com.devmind.common.integration.FeishuDocFetcher.FeishuIntegration;
import com.devmind.knowledge.dto.FeishuImportRequest;
import com.devmind.knowledge.dto.FeishuImportResult;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * CAP-45 飞书文档导入/重同步。经 common {@link FeishuDocFetcher} SPI 拉取
 * （ObjectProvider 探测注入，integration 模块未装配时按「未配置飞书集成」降级），
 * 判重键 externalId = "{integrationId}:{docToken}"，contentHash 做变更检测；
 * 建/更新走与手工保存相同的 EntryContentChangedEvent 链路 → 自动重建索引。
 * 整批不加事务（远程拉取在库事务外）；单条失败不阻断其余 URL。
 */
@Service
public class FeishuImportService {

    private static final Logger log = LoggerFactory.getLogger(FeishuImportService.class);

    private final KnowledgeBaseRepository kbRepo;
    private final KnowledgeEntryRepository entryRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<FeishuDocFetcher> fetcherProvider;

    public FeishuImportService(KnowledgeBaseRepository kbRepo,
                               KnowledgeEntryRepository entryRepo,
                               ApplicationEventPublisher eventPublisher,
                               ObjectProvider<FeishuDocFetcher> fetcherProvider) {
        this.kbRepo = kbRepo;
        this.entryRepo = entryRepo;
        this.eventPublisher = eventPublisher;
        this.fetcherProvider = fetcherProvider;
    }

    /** 可用飞书集成清单（前端下拉）；SPI 未装配返回空（页面提示去集成页配置） */
    public List<FeishuIntegration> listIntegrations() {
        FeishuDocFetcher fetcher = fetcherProvider.getIfAvailable();
        return fetcher == null ? List.of() : fetcher.listIntegrations();
    }

    /** 批量导入：逐条拉取转条目，结果逐条返回（created/updated/unchanged/failed）。 */
    public List<FeishuImportResult> importDocs(Long kbId, FeishuImportRequest req) {
        KnowledgeBaseEntity kb = requireBase(kbId);
        FeishuDocFetcher fetcher = requireFetcher();
        if (req.integrationId() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "integrationId 必填");
        }
        if (req.urls() == null || req.urls().isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "urls 至少一条");
        }
        List<FeishuImportResult> results = new ArrayList<>();
        for (String url : req.urls()) {
            if (url == null || url.isBlank()) {
                continue;
            }
            try {
                FeishuDoc doc = fetcher.fetch(req.integrationId(), url.trim());
                results.add(upsertEntry(kb, req.integrationId(), doc));
            } catch (Exception e) {
                log.warn("飞书文档导入失败: kb={} url={} err={}", kbId, url, e.getMessage());
                results.add(FeishuImportResult.failed(url, e.getMessage()));
            }
        }
        return results;
    }

    /** 手动重同步（FR-04）：按条目 path 存的来源 URL 重拉；内容未变 → unchanged；拉取失败 → failed 保留旧内容。 */
    public FeishuImportResult resync(Long entryId) {
        KnowledgeEntryEntity e = entryRepo.findById(entryId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "条目不存在: " + entryId));
        if (!KnowledgeEntryEntity.SOURCE_FEISHU.equals(e.getSource())
                || e.getExternalId() == null || e.getPath() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "仅飞书来源条目支持重同步");
        }
        long integrationId = integrationIdOf(e.getExternalId());
        FeishuDocFetcher fetcher = requireFetcher();
        try {
            FeishuDoc doc = fetcher.fetch(integrationId, e.getPath());
            KnowledgeBaseEntity kb = kbRepo.findById(e.getKbId()).orElseThrow(
                    () -> new DevMindException(ErrorCode.NOT_FOUND, "知识库不存在: " + e.getKbId()));
            return upsertEntry(kb, integrationId, doc);
        } catch (Exception ex) {
            // 飞书侧文档已删/无权限等：failed 并保留旧内容（FR-04）
            log.warn("飞书条目重同步失败: entry={} err={}", entryId, ex.getMessage());
            return FeishuImportResult.failed(e.getPath(), ex.getMessage());
        }
    }

    // ---------------- 内部 ----------------

    /** 判重 + 变更检测落库；返回 created/updated/unchanged。 */
    private FeishuImportResult upsertEntry(KnowledgeBaseEntity kb, long integrationId, FeishuDoc doc) {
        String externalId = integrationId + ":" + doc.externalId();
        String newHash = KnowledgeBaseService.sha256(doc.contentMd() == null ? "" : doc.contentMd());
        var existing = entryRepo.findByKbIdAndExternalId(kb.getId(), externalId);
        Instant now = Instant.now();
        if (existing.isPresent()) {
            KnowledgeEntryEntity e = existing.get();
            if (newHash.equals(e.getContentHash())) {
                return FeishuImportResult.of(doc.sourceUrl(), "unchanged", e.getId());
            }
            e.setName(doc.title());
            e.setContentMd(doc.contentMd());
            e.setContentHash(newHash);
            e.setPath(doc.sourceUrl());
            e.setUpdatedAt(now);
            entryRepo.save(e);
            publishContentChanged(e);
            return FeishuImportResult.of(doc.sourceUrl(), "updated", e.getId());
        }
        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        e.setKbId(kb.getId());
        // 历史列双写（派生自库，与手工保存同口径）
        e.setScope(kb.getScope());
        e.setProjectId(kb.getProjectId());
        e.setName(doc.title());
        e.setContentMd(doc.contentMd());
        e.setContentHash(newHash);
        e.setPath(doc.sourceUrl());
        e.setSource(KnowledgeEntryEntity.SOURCE_FEISHU);
        e.setExternalId(externalId);
        e.setStatus("active");
        e.setHitCount(0);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        entryRepo.save(e);
        publishContentChanged(e);
        return FeishuImportResult.of(doc.sourceUrl(), "created", e.getId());
    }

    /** externalId 形为 "{integrationId}:{docToken}"，取前缀 */
    private static long integrationIdOf(String externalId) {
        int idx = externalId.indexOf(':');
        if (idx <= 0) {
            throw new DevMindException(ErrorCode.INTERNAL, "飞书条目 externalId 形态异常: " + externalId);
        }
        try {
            return Long.parseLong(externalId.substring(0, idx));
        } catch (NumberFormatException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "飞书条目 externalId 形态异常: " + externalId);
        }
    }

    private void publishContentChanged(KnowledgeEntryEntity e) {
        eventPublisher.publishEvent(new EntryContentChangedEvent(e.getId(), e.getKbId()));
    }

    private FeishuDocFetcher requireFetcher() {
        FeishuDocFetcher fetcher = fetcherProvider.getIfAvailable();
        if (fetcher == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "飞书集成能力未装配（integration 模块缺失）");
        }
        return fetcher;
    }

    private KnowledgeBaseEntity requireBase(Long id) {
        return kbRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "知识库不存在: " + id));
    }
}
