package com.devmind.knowledge.index;

import com.devmind.knowledge.chunk.TextChunker;
import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.embedding.EmbeddingClient;
import com.devmind.knowledge.embedding.VectorJson;
import com.devmind.knowledge.model.KnowledgeChunkEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeChunkRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAP-44 FR-04 摄入管线：条目 → 分块 → embedding → 重写 knowledge_chunks → index_status。
 * 由 KnowledgeIndexListener 异步触发（事件 AFTER_COMMIT / 启动清扫 pending+disabled）。
 * 本方法自带事务（非异步触发方法，红线合规）；embedding 未配置标 disabled（检索降级 LIKE）。
 */
@Service
public class KnowledgeIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);
    /** 启动清扫单批上限，防存量大爆炸拖垮启动 */
    static final int SWEEP_LIMIT = 500;

    private final KnowledgeEntryRepository entryRepo;
    private final KnowledgeChunkRepository chunkRepo;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeProperties props;

    public KnowledgeIndexService(KnowledgeEntryRepository entryRepo,
                                 KnowledgeChunkRepository chunkRepo,
                                 EmbeddingClient embeddingClient,
                                 KnowledgeProperties props) {
        this.entryRepo = entryRepo;
        this.chunkRepo = chunkRepo;
        this.embeddingClient = embeddingClient;
        this.props = props;
    }

    public boolean embeddingAvailable() {
        return embeddingClient.available();
    }

    @Transactional
    public void indexEntry(long entryId) {
        KnowledgeEntryEntity entry = entryRepo.findById(entryId).orElse(null);
        if (entry == null) {
            return;
        }
        if (!embeddingClient.available()) {
            entry.setIndexStatus(KnowledgeEntryEntity.INDEX_DISABLED);
            entry.setIndexError(null);
            entryRepo.save(entry);
            return;
        }
        try {
            KnowledgeProperties.Embedding cfg = props.getEmbedding();
            List<String> pieces = TextChunker.chunk(entry.getContentMd(),
                    cfg.getChunkSize(), cfg.getChunkOverlap());
            List<float[]> vectors = pieces.isEmpty()
                    ? List.of()
                    : embeddingClient.embed(pieces.stream()
                            .map(p -> entry.getName() + "\n" + p)
                            .toList());
            chunkRepo.deleteByEntryId(entryId);
            List<KnowledgeChunkEntity> chunks = new ArrayList<>(pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                KnowledgeChunkEntity c = new KnowledgeChunkEntity();
                c.setKbId(entry.getKbId());
                c.setEntryId(entryId);
                c.setChunkIndex(i);
                c.setContent(pieces.get(i));
                c.setEmbedding(VectorJson.toJson(vectors.get(i)));
                c.setTokenCount(pieces.get(i).length());
                chunks.add(c);
            }
            if (!chunks.isEmpty()) {
                chunkRepo.saveAll(chunks);
            }
            entry.setIndexStatus(KnowledgeEntryEntity.INDEX_READY);
            entry.setIndexError(null);
            entryRepo.save(entry);
            log.info("知识条目索引完成: entry={} chunks={}", entryId, chunks.size());
        } catch (Exception e) {
            entry.setIndexStatus(KnowledgeEntryEntity.INDEX_FAILED);
            entry.setIndexError(truncate(String.valueOf(e.getMessage()), 1000));
            entryRepo.save(entry);
            log.warn("知识条目索引失败: entry={} err={}", entryId, e.toString());
        }
    }

    /** 启动清扫：pending + disabled（曾未配置 embedding 的条目在配置补齐后自愈）；failed 留人工重试 */
    public void sweepPending() {
        if (!embeddingClient.available()) {
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

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
