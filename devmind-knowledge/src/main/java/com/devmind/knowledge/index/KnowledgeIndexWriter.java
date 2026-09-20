package com.devmind.knowledge.index;

import com.devmind.knowledge.embedding.EmbeddingResolver;
import com.devmind.knowledge.embedding.VectorJson;
import com.devmind.knowledge.model.KnowledgeChunkEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeChunkRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAP-48 索引落库（短事务）：把 embedding 结果与血缘写进 knowledge_chunks / knowledge_entries。
 *
 * <p>独立成 bean 是因为 embedding 是网络 IO——它必须发生在事务<b>之外</b>，
 * 不能一边占着数据库连接一边等远端返回（CAP-44 时期 {@code indexEntry} 自带
 * {@code @Transactional} 把 HTTP 包在事务里，索引慢时连接池跟着一起紧张）。
 * 事务边界收在这里：进方法时数据已齐，方法内只有纯写。</p>
 */
@Component
public class KnowledgeIndexWriter {

    private final KnowledgeEntryRepository entryRepo;
    private final KnowledgeChunkRepository chunkRepo;

    public KnowledgeIndexWriter(KnowledgeEntryRepository entryRepo, KnowledgeChunkRepository chunkRepo) {
        this.entryRepo = entryRepo;
        this.chunkRepo = chunkRepo;
    }

    /** 重写分块并标 ready；血缘记 actualDimensions（实测向量长度，不是端点记录里的声明值） */
    @Transactional
    public void writeChunks(long entryId, List<String> pieces, List<float[]> vectors,
                            EmbeddingResolver.Resolution resolution) {
        KnowledgeEntryEntity entry = entryRepo.findById(entryId).orElse(null);
        if (entry == null) {
            return;
        }
        chunkRepo.deleteByEntryId(entryId);
        if (!pieces.isEmpty()) {
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
            chunkRepo.saveAll(chunks);
        }
        entry.setIndexStatus(KnowledgeEntryEntity.INDEX_READY);
        entry.setIndexError(null);
        entry.setIndexedEndpointId(resolution.endpointId());
        entry.setIndexedModel(resolution.model());
        entry.setIndexedDimensions(vectors.isEmpty() ? null : vectors.get(0).length);
        entry.setUpdatedAt(Instant.now());
        entryRepo.save(entry);
    }

    /** embedding 不可用：标 disabled（不写 chunks，保留旧 chunks 不动——降级期间仍需 LIKE 兜底） */
    @Transactional
    public void markDisabled(long entryId) {
        entryRepo.findById(entryId).ifPresent(entry -> {
            entry.setIndexStatus(KnowledgeEntryEntity.INDEX_DISABLED);
            entry.setIndexError(null);
            entry.setUpdatedAt(Instant.now());
            entryRepo.save(entry);
        });
    }

    @Transactional
    public void markFailed(long entryId, String message) {
        entryRepo.findById(entryId).ifPresent(entry -> {
            entry.setIndexStatus(KnowledgeEntryEntity.INDEX_FAILED);
            entry.setIndexError(message == null ? null : message.substring(0, Math.min(message.length(), 1000)));
            entry.setUpdatedAt(Instant.now());
            entryRepo.save(entry);
        });
    }
}
