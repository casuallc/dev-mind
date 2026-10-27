package com.devmind.knowledge.sync;

import com.devmind.knowledge.EntryContentChangedEvent;
import com.devmind.knowledge.KnowledgeVersionService;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * CAP-85 FR-27：外部同步源 → 知识条目的统一写入。负责哈希/变更检测/版本记录/索引事件，
 * 并保留人工修正：`auto_enrich=false` 的条目不被同步覆盖分类/摘要/标签。
 *
 * <p>不加事务：调用方在任务内逐条写；单条失败由同步服务计数后继续。</p>
 */
@Service
public class SyncedEntryWriter {

    private final KnowledgeEntryRepository entryRepo;
    private final KnowledgeVersionService versionService;
    private final ApplicationEventPublisher eventPublisher;

    public SyncedEntryWriter(KnowledgeEntryRepository entryRepo,
                             KnowledgeVersionService versionService,
                             ApplicationEventPublisher eventPublisher) {
        this.entryRepo = entryRepo;
        this.versionService = versionService;
        this.eventPublisher = eventPublisher;
    }

    public enum Outcome { CREATED, UPDATED, UNCHANGED }

    public Outcome upsert(KnowledgeBaseEntity kb,
                          String source,
                          Long syncSourceId,
                          String externalId,
                          String sourceUri,
                          String title,
                          String contentMd,
                          String sourceVersion,
                          String docType,
                          List<String> tags,
                          String actor) {
        String content = contentMd == null ? "" : contentMd;
        String hash = sha256(content);
        Instant now = Instant.now();
        Optional<KnowledgeEntryEntity> existing = entryRepo.findByKbIdAndExternalId(kb.getId(), externalId);
        if (existing.isPresent()) {
            KnowledgeEntryEntity e = existing.get();
            boolean manualMetadata = Boolean.FALSE.equals(e.getAutoEnrich());
            boolean changed = !hash.equals(e.getContentHash());
            boolean metadataChanged = false;
            if (changed) {
                e.setContentMd(content);
                e.setContentHash(hash);
                e.setContentVersion(Math.max(1, e.getContentVersion()) + 1);
                if (!manualMetadata || e.getSummary() == null || e.getSummary().isBlank()) {
                    e.setSummary(summarize(content));
                }
                e.setEnrichedAt(null);
                e.setEnrichError(null);
                e.setSimHash(null);
            }
            if (title != null && !title.isBlank() && !title.equals(e.getName())) {
                e.setName(title);
                metadataChanged = true;
            }
            if (!manualMetadata && docType != null && !docType.isBlank() && !docType.equals(e.getDocType())) {
                e.setDocType(docType);
                metadataChanged = true;
            }
            List<String> mergedTags = mergeTags(e.getTags(), tags);
            if (!mergedTags.equals(splitTags(e.getTags()))) {
                e.setTags(String.join(",", mergedTags));
                metadataChanged = true;
            }
            e.setSource(source);
            e.setSyncSourceId(syncSourceId);
            e.setSourceUri(sourceUri);
            e.setSourceVersion(sourceVersion);
            e.setUpdatedAt(now);
            e = entryRepo.save(e);
            if (changed) {
                versionService.record(e, actor, "外部同步更新");
            }
            if (changed || metadataChanged) {
                publish(e);
            }
            return changed ? Outcome.UPDATED : Outcome.UNCHANGED;
        }
        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        e.setKbId(kb.getId());
        // CAP-94：历史 scope/projectId 列停写
        e.setName(title == null || title.isBlank() ? externalId : title);
        e.setContentMd(content);
        e.setContentHash(hash);
        e.setSource(source);
        e.setExternalId(externalId);
        e.setSyncSourceId(syncSourceId);
        e.setSourceUri(sourceUri);
        e.setSourceVersion(sourceVersion);
        e.setDocType(docType == null || docType.isBlank() ? KnowledgeEntryEntity.DOC_TECH : docType);
        e.setTags(tags == null || tags.isEmpty() ? null : String.join(",", tags));
        e.setSummary(summarize(content));
        e.setContentVersion(1);
        e.setStatus("active");
        e.setHitCount(0);
        e.setAutoEnrich(Boolean.TRUE);
        e.setOwner(actor);
        e.setPath(kb.getScope() + "/" + slug(e.getName()) + ".md");
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        e = entryRepo.save(e);
        versionService.record(e, actor, "外部同步创建");
        publish(e);
        return Outcome.CREATED;
    }

    /** 外部源已删除：标记 deprecated + 失效时间，不物理删；返回是否发生状态变化。 */
    public boolean deprecate(KnowledgeEntryEntity e, String actor) {
        if ("deprecated".equals(e.getStatus()) && e.getValidTo() != null) {
            return false;
        }
        Instant now = Instant.now();
        e.setStatus("deprecated");
        e.setValidTo(now);
        e.setUpdatedAt(now);
        entryRepo.save(e);
        versionService.record(e, actor, "外部源删除（标记失效）");
        publish(e);
        return true;
    }

    private void publish(KnowledgeEntryEntity e) {
        if (e.getKbId() != null) {
            eventPublisher.publishEvent(new EntryContentChangedEvent(e.getId(), e.getKbId()));
        }
    }

    private static List<String> mergeTags(String existingCsv, List<String> incoming) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(splitTags(existingCsv));
        if (incoming != null) {
            for (String t : incoming) {
                if (t != null && !t.isBlank()) {
                    merged.add(t.strip());
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (String t : merged) {
            out.add(t.length() > 32 ? t.substring(0, 32) : t);
            if (out.size() >= 10) {
                break;
            }
        }
        return out;
    }

    private static List<String> splitTags(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            if (!s.isBlank()) {
                out.add(s.strip());
            }
        }
        return out;
    }

    private static String summarize(String content) {
        String oneLine = content.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300);
    }

    private static String slug(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        return s.isBlank() ? "entry" : (s.length() > 100 ? s.substring(0, 100) : s);
    }

    static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
