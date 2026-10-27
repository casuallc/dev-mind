package com.devmind.knowledge.index;

import com.devmind.knowledge.chunk.TextChunker;
import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.embedding.EmbeddingResolver;
import com.devmind.knowledge.governance.SimHash;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeChunkEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeChunkRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import com.devmind.task.api.TaskContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CAP-84 摄入管线：条目 → 结构化分块 → 按库解析端点 embedding（可选）→ 分块落 DB →
 * Lucene BM25 + 向量同索引。
 *
 * <p>embedding 不可用或失败只降级向量通道（index_status=disabled），文本仍进 BM25；
 * 只有分块/写库/Lucene 写失败才标 failed。本类<b>不带事务</b>：网络 IO 必须发生在
 * {@link KnowledgeIndexWriter} 的短事务之外。</p>
 */
@Service
public class KnowledgeIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);
    /** 启动清扫单批上限，防存量大爆炸拖垮启动 */
    static final int SWEEP_LIMIT = 500;

    private final KnowledgeEntryRepository entryRepo;
    private final KnowledgeBaseRepository kbRepo;
    private final KnowledgeChunkRepository chunkRepo;
    private final EmbeddingResolver resolver;
    private final KnowledgeIndexWriter writer;
    private final KnowledgeSearchIndex searchIndex;
    private final KnowledgeProperties props;

    public KnowledgeIndexService(KnowledgeEntryRepository entryRepo,
                                 KnowledgeBaseRepository kbRepo,
                                 KnowledgeChunkRepository chunkRepo,
                                 EmbeddingResolver resolver,
                                 KnowledgeIndexWriter writer,
                                 KnowledgeSearchIndex searchIndex,
                                 KnowledgeProperties props) {
        this.entryRepo = entryRepo;
        this.kbRepo = kbRepo;
        this.chunkRepo = chunkRepo;
        this.resolver = resolver;
        this.writer = writer;
        this.searchIndex = searchIndex;
        this.props = props;
    }

    /** 是否配了可用端点（启动清扫是否值得跑的判据） */
    public boolean embeddingAvailable() {
        return resolver.anyConfigured();
    }

    public void indexEntry(long entryId) {
        KnowledgeEntryEntity entry = entryRepo.findById(entryId).orElse(null);
        if (entry == null) {
            searchIndex.deleteEntry(entryId);
            return;
        }
        KnowledgeBaseEntity kb = entry.getKbId() == null ? null : kbRepo.findById(entry.getKbId()).orElse(null);
        // CAP-85 FR-29：索引时维护 SimHash（重复检测用；纯元数据，不改变 updatedAt）
        String simHash = SimHash.of(entry.getContentMd());
        if (simHash != null && !simHash.equals(entry.getSimHash())) {
            entry.setSimHash(simHash);
            entry = entryRepo.save(entry);
        }
        EmbeddingResolver.Resolution resolution = resolver.resolve(kb == null ? null : kb.getModelEndpointId());

        KnowledgeProperties.Embedding cfg = props.getEmbedding();
        List<TextChunker.Chunk> pieces = TextChunker.chunkStructured(
                entry.getContentMd(), cfg.getChunkSize(), cfg.getChunkOverlap());
        List<float[]> vectors = List.of();
        String degradedReason = null;
        if (resolution.available() && !pieces.isEmpty()) {
            try {
                String title = entry.getName() == null ? "" : entry.getName();
                vectors = resolution.client().embed(pieces.stream()
                        .map(p -> title + "\n" + p.text())
                        .toList());
                if (vectors == null || vectors.size() != pieces.size()) {
                    degradedReason = "向量生成结果数量与分块不一致，已降级文本索引";
                    vectors = List.of();
                }
            } catch (Exception e) {
                degradedReason = "向量生成失败，已降级文本索引: " + e.getMessage();
                vectors = List.of();
                log.warn("知识条目向量生成失败，降级 BM25: entry={} err={}", entryId, e.toString());
            }
        }
        try {
            List<KnowledgeChunkEntity> saved = writer.writeChunks(
                    entryId, pieces, vectors, resolution, degradedReason);
            searchIndex.upsert(meta(entry), toChunks(saved), vectors);
            log.info("知识条目索引完成: entry={} chunks={} vectors={} endpoint={}",
                    entryId, saved.size(), vectors.isEmpty() ? 0 : vectors.size(),
                    vectors.isEmpty() ? "-" : resolution.endpointId());
        } catch (Exception e) {
            writer.markFailed(entryId, String.valueOf(e.getMessage()));
            log.warn("知识条目索引失败: entry={} err={}", entryId, e.toString());
        }
    }

    /** 删除条目的检索侧数据（DB 删除前调用，Lucene 为派生数据）。 */
    public void removeFromIndex(long entryId) {
        searchIndex.deleteEntry(entryId);
    }

    /** 批量写入后提交并刷新 searcher（导入任务收尾）。 */
    public void flush() {
        searchIndex.flush();
    }

    /**
     * 重建索引（任务入口）：按键集分页逐条重跑，避免万级正文一次性入内存。
     * kbId 为空 = 全库；只重建该库条目时不动其它库的 Lucene 文档。
     */
    public void rebuildAll(Long kbId, TaskContext ctx) {
        long lastId = 0L;
        int done = 0;
        int failed = 0;
        while (true) {
            if (ctx != null && ctx.isCancelled()) {
                if (ctx != null) {
                    ctx.log("重建索引已取消，已完成 " + done + " 条");
                }
                break;
            }
            List<KnowledgeEntryEntity> batch = kbId == null
                    ? entryRepo.findTop100ByIdGreaterThanOrderByIdAsc(lastId)
                    : entryRepo.findTop100ByKbIdAndIdGreaterThanOrderByIdAsc(kbId, lastId);
            if (batch.isEmpty()) {
                break;
            }
            for (KnowledgeEntryEntity e : batch) {
                indexEntry(e.getId());
                KnowledgeEntryEntity after = entryRepo.findById(e.getId()).orElse(e);
                if (KnowledgeEntryEntity.INDEX_FAILED.equals(after.getIndexStatus())) {
                    failed++;
                    if (ctx != null) {
                        ctx.log("重建失败: entry=" + e.getId() + " " + e.getName() + " - " + after.getIndexError());
                    }
                }
                done++;
                lastId = e.getId();
            }
            if (ctx != null) {
                ctx.progress("重建索引 " + done + " 条");
            }
        }
        searchIndex.flush();
        log.info("知识索引重建完成: kb={} total={} failed={}", kbId, done, failed);
        if (ctx != null) {
            ctx.log("重建索引完成: " + done + " 条，失败 " + failed);
        }
    }

    /** 启动清扫：pending + disabled（曾无端点/端点停用的条目在端点补齐后自愈）；failed 留人工重试 */
    public void sweepPending() {
        if (!embeddingAvailable()) {
            return;
        }
        List<KnowledgeEntryEntity> stale = entryRepo.findByIndexStatusIn(List.of(
                KnowledgeEntryEntity.INDEX_PENDING, KnowledgeEntryEntity.INDEX_DISABLED));
        int done = 0;
        for (KnowledgeEntryEntity e : stale) {
            if (done >= SWEEP_LIMIT) {
                log.warn("知识索引清扫达单批上限 {}，剩余条目下次启动再处理", SWEEP_LIMIT);
                break;
            }
            indexEntry(e.getId());
            done++;
        }
        if (done > 0) {
            searchIndex.flush();
            log.info("知识索引启动清扫完成: {} 条", done);
        }
    }

    /** 索引健康度：Lucene 文档数 vs DB 条目数（不一致提示重建）。 */
    public IndexHealth health() {
        long dbEntries = entryRepo.count();
        long indexedDocs = searchIndex.numDocs();
        return new IndexHealth(dbEntries, indexedDocs, searchIndex.vectorDimensions());
    }

    /** CAP-84 M2：管理端索引统计（文档/分块、索引体积、schema 版本、维度分布）。 */
    public AdminIndexStats adminStats() {
        KnowledgeSearchIndex.IndexStats stats = searchIndex.stats();
        return new AdminIndexStats(entryRepo.count(), chunkRepo.count(), stats.docs(),
                stats.sizeBytes(), stats.schemaVersion(), stats.vectorDimensions());
    }

    public record AdminIndexStats(long entries, long chunks, long indexedDocs, long indexSizeBytes,
                                  String schemaVersion, java.util.Set<Integer> vectorDimensions) {
    }

    private KnowledgeSearchIndex.EntryMeta meta(KnowledgeEntryEntity entry) {
        String docType = entry.getDocType() == null || entry.getDocType().isBlank()
                ? KnowledgeEntryEntity.DOC_TECH : entry.getDocType();
        String status = entry.getStatus() == null || entry.getStatus().isBlank() ? "active" : entry.getStatus();
        return new KnowledgeSearchIndex.EntryMeta(entry.getId(),
                entry.getKbId() == null ? 0L : entry.getKbId(),
                docType, status, splitTags(entry.getTags()), entry.getName(),
                entry.getSourceUri(), entry.getValidFrom(), entry.getValidTo(),
                entry.getUpdatedAt() == null ? Instant.now() : entry.getUpdatedAt());
    }

    private static List<KnowledgeSearchIndex.Chunk> toChunks(List<KnowledgeChunkEntity> saved) {
        List<KnowledgeSearchIndex.Chunk> chunks = new ArrayList<>(saved.size());
        for (KnowledgeChunkEntity c : saved) {
            chunks.add(new KnowledgeSearchIndex.Chunk(c.getId(), c.getHeadingPath(), c.getContent()));
        }
        return chunks;
    }

    static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(t -> t.strip().toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty())
                .distinct()
                .toList();
    }

    public record IndexHealth(long dbEntries, long indexedDocs, java.util.Set<Integer> vectorDimensions) {
    }
}
