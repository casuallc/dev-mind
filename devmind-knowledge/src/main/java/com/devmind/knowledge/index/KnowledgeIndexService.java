package com.devmind.knowledge.index;

import com.devmind.knowledge.chunk.TextChunker;
import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.embedding.EmbeddingResolver;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CAP-44 FR-04 摄入管线（CAP-48 改造）：条目 → 分块 → 按库解析出的端点 embedding →
 * 重写 knowledge_chunks → index_status + 索引血缘。
 *
 * <p>本类<b>不带事务</b>：embedding 是网络 IO，必须发生在事务外（落库交给
 * {@link KnowledgeIndexWriter} 的短事务）。触发方是 KnowledgeIndexListener 的异步单线程。</p>
 */
@Service
public class KnowledgeIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);
    /** 启动清扫单批上限，防存量大爆炸拖垮启动 */
    static final int SWEEP_LIMIT = 500;

    private final KnowledgeEntryRepository entryRepo;
    private final KnowledgeBaseRepository kbRepo;
    private final EmbeddingResolver resolver;
    private final KnowledgeIndexWriter writer;
    private final KnowledgeProperties props;

    public KnowledgeIndexService(KnowledgeEntryRepository entryRepo,
                                 KnowledgeBaseRepository kbRepo,
                                 EmbeddingResolver resolver,
                                 KnowledgeIndexWriter writer,
                                 KnowledgeProperties props) {
        this.entryRepo = entryRepo;
        this.kbRepo = kbRepo;
        this.resolver = resolver;
        this.writer = writer;
        this.props = props;
    }

    /** 是否配了可用端点（启动清扫是否值得跑的判据） */
    public boolean embeddingAvailable() {
        return resolver.anyConfigured();
    }

    public void indexEntry(long entryId) {
        KnowledgeEntryEntity entry = entryRepo.findById(entryId).orElse(null);
        if (entry == null) {
            return;
        }
        EmbeddingResolver.Resolution resolution = resolver.resolve(endpointIdOf(entry.getKbId()));
        if (!resolution.available()) {
            writer.markDisabled(entryId);
            return;
        }
        try {
            KnowledgeProperties.Embedding cfg = props.getEmbedding();
            List<String> pieces = TextChunker.chunk(entry.getContentMd(),
                    cfg.getChunkSize(), cfg.getChunkOverlap());
            List<float[]> vectors = pieces.isEmpty()
                    ? List.of()
                    : resolution.client().embed(pieces.stream()
                            .map(p -> entry.getName() + "\n" + p)
                            .toList());
            writer.writeChunks(entryId, pieces, vectors, resolution);
            log.info("知识条目索引完成: entry={} chunks={} endpoint={} dims={}",
                    entryId, pieces.size(), resolution.endpointId(),
                    vectors.isEmpty() ? "-" : vectors.get(0).length);
        } catch (Exception e) {
            writer.markFailed(entryId, String.valueOf(e.getMessage()));
            log.warn("知识条目索引失败: entry={} err={}", entryId, e.toString());
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
            log.info("知识索引启动清扫完成: {} 条", done);
        }
    }

    /** 条目所属库的库级覆盖端点（库不存在/无覆盖 → null = 平台默认） */
    private Long endpointIdOf(Long kbId) {
        if (kbId == null) {
            return null;
        }
        return kbRepo.findById(kbId).map(KnowledgeBaseEntity::getModelEndpointId).orElse(null);
    }
}
