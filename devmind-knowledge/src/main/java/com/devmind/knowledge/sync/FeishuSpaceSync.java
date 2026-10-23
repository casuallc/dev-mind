package com.devmind.knowledge.sync;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.FeishuDocFetcher;
import com.devmind.common.integration.FeishuDocFetcher.FeishuDoc;
import com.devmind.common.integration.FeishuDocFetcher.SpaceDoc;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.model.KnowledgeSyncSourceEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import com.devmind.task.api.TaskContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-85 FR-27：飞书 wiki 空间 / 云盘目录增量同步。
 *
 * <p>每次完整列举元数据（发现删除），只对 waterMark（最大 updatedAt）之后变更的文档拉正文；
 * 60s 重叠窗口防边界漏拉。单篇失败计数后继续；源端删除只标 deprecated。</p>
 */
@Service
public class FeishuSpaceSync {

    private static final Logger log = LoggerFactory.getLogger(FeishuSpaceSync.class);
    private static final long OVERLAP_MS = 60_000L;
    private static final Set<String> DOC_TYPES = Set.of(
            KnowledgeEntryEntity.DOC_TECH, KnowledgeEntryEntity.DOC_MAINT,
            KnowledgeEntryEntity.DOC_FAQ, KnowledgeEntryEntity.DOC_ARTIFACT);

    private final KnowledgeBaseRepository kbRepo;
    private final KnowledgeEntryRepository entryRepo;
    private final SyncedEntryWriter writer;
    private final ObjectProvider<FeishuDocFetcher> fetcherProvider;
    private final ObjectMapper mapper;

    public FeishuSpaceSync(KnowledgeBaseRepository kbRepo,
                           KnowledgeEntryRepository entryRepo,
                           SyncedEntryWriter writer,
                           ObjectProvider<FeishuDocFetcher> fetcherProvider,
                           ObjectMapper mapper) {
        this.kbRepo = kbRepo;
        this.entryRepo = entryRepo;
        this.writer = writer;
        this.fetcherProvider = fetcherProvider;
        this.mapper = mapper;
    }

    public SyncOutcome run(KnowledgeSyncSourceEntity source, TaskContext ctx) {
        FeishuConfig cfg = FeishuConfig.parse(source.getConfigJson(), mapper);
        FeishuDocFetcher fetcher = fetcherProvider.getIfAvailable();
        if (fetcher == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "飞书集成能力未装配（integration 模块缺失）");
        }
        KnowledgeBaseEntity kb = kbRepo.findById(source.getKbId())
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "知识库不存在: " + source.getKbId()));
        if (ctx != null) {
            ctx.log("飞书同步开始：integration=" + cfg.integrationId() + " target=" + cfg.target());
        }
        List<SpaceDoc> docs;
        try {
            docs = fetcher.listSpaceDocs(cfg.integrationId(), cfg.target());
        } catch (UnsupportedOperationException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "当前飞书集成不支持目录同步");
        }
        long oldWatermark = parseWatermark(source.getWatermark());
        boolean first = oldWatermark <= 0;
        long maxUpdatedAt = oldWatermark;
        Set<String> seen = new HashSet<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int deprecated = 0;
        int failed = 0;
        boolean cancelled = false;

        for (SpaceDoc doc : docs) {
            if (ctx != null && ctx.isCancelled()) {
                cancelled = true;
                break;
            }
            String externalId = externalId(source.getId(), doc.externalId());
            seen.add(externalId);
            maxUpdatedAt = Math.max(maxUpdatedAt, doc.updatedAt());
            if (!first && doc.updatedAt() > 0 && doc.updatedAt() < oldWatermark - OVERLAP_MS) {
                continue;
            }
            try {
                FeishuDoc fetched = fetcher.fetchNode(cfg.integrationId(), doc.objType(), doc.externalId());
                String title = fetched.title() == null || fetched.title().isBlank()
                        ? doc.title() : fetched.title();
                String sourceUri = "feishu://" + doc.objType() + "/" + doc.externalId();
                var outcome = writer.upsert(kb, KnowledgeEntryEntity.SOURCE_FEISHU, source.getId(),
                        externalId, sourceUri, title, fetched.contentMd(),
                        doc.updatedAt() > 0 ? String.valueOf(doc.updatedAt()) : "feishu",
                        cfg.docType() == null ? KnowledgeEntryEntity.DOC_TECH : cfg.docType(),
                        cfg.tags(), "feishu-sync");
                switch (outcome) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case UNCHANGED -> unchanged++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("飞书文档同步失败: source={} doc={} err={}",
                        source.getId(), doc.externalId(), e.toString());
                if (ctx != null) {
                    ctx.log("失败 " + doc.title() + "：" + e.getMessage());
                }
            }
        }

        if (!cancelled) {
            for (KnowledgeEntryRepository.SyncEntryRef ref : entryRepo.findRefsBySyncSourceId(source.getId())) {
                if (ref.getExternalId() != null && !seen.contains(ref.getExternalId())) {
                    KnowledgeEntryEntity e = entryRepo.findById(ref.getId()).orElse(null);
                    if (e != null && writer.deprecate(e, "feishu-sync")) {
                        deprecated++;
                    }
                }
            }
        }
        String watermark = cancelled ? source.getWatermark() : String.valueOf(maxUpdatedAt);
        if (ctx != null) {
            ctx.log("飞书同步完成：docs=" + docs.size() + " created=" + created + " updated=" + updated
                    + " unchanged=" + unchanged + " deprecated=" + deprecated + " failed=" + failed);
        }
        return new SyncOutcome(watermark, created, updated, unchanged, deprecated, failed);
    }

    private static long parseWatermark(String watermark) {
        if (watermark == null || watermark.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(watermark.strip());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String externalId(Long sourceId, String docToken) {
        return "sync:" + sourceId + ":" + docToken;
    }

    private record FeishuConfig(long integrationId, String target, String docType, List<String> tags) {
        static FeishuConfig parse(String json, ObjectMapper mapper) {
            JsonNode n;
            try {
                n = json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json);
            } catch (Exception e) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "同步源配置不是合法 JSON");
            }
            long integrationId = n.path("integrationId").asLong(0);
            if (integrationId <= 0) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "integrationId 必填");
            }
            String target = n.path("target").asText("");
            if (target.isBlank()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "同步目标（wiki 空间/云盘文件夹）必填");
            }
            String docType = n.path("docType").asText("");
            if (!docType.isBlank()) {
                docType = docType.strip().toUpperCase(Locale.ROOT);
                if (!DOC_TYPES.contains(docType)) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST,
                            "docType 必须是 TECH/MAINT/FAQ/ARTIFACT");
                }
            } else {
                docType = null;
            }
            List<String> tags = new ArrayList<>();
            JsonNode tagsNode = n.path("tags");
            if (tagsNode.isArray()) {
                tagsNode.forEach(t -> {
                    if (!t.asText("").isBlank()) {
                        tags.add(t.asText().strip());
                    }
                });
            } else if (tagsNode.isTextual() && !tagsNode.asText().isBlank()) {
                for (String t : tagsNode.asText().split(",")) {
                    if (!t.isBlank()) {
                        tags.add(t.strip());
                    }
                }
            }
            return new FeishuConfig(integrationId, target.strip(), docType,
                    List.copyOf(new LinkedHashSet<>(tags)));
        }
    }
}
