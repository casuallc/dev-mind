package com.devmind.knowledge.index;

import com.devmind.knowledge.chunk.TextChunker;
import com.devmind.knowledge.embedding.EmbeddingResolver;
import com.devmind.knowledge.model.KnowledgeChunkEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeChunkRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAP-84 索引落库（短事务）：把结构化分块与索引血缘写进 knowledge_chunks / knowledge_entries。
 * 向量不再落 DB（Lucene 承载），保留 embedding 列仅为回滚兼容。
 *
 * <p>embedding 是网络 IO，必须发生在事务<b>之外</b>；事务边界收在这里，方法内只有纯写。</p>
 */
@Component
public class KnowledgeIndexWriter {

    private final KnowledgeEntryRepository entryRepo;
    private final KnowledgeChunkRepository chunkRepo;

    public KnowledgeIndexWriter(KnowledgeEntryRepository entryRepo, KnowledgeChunkRepository chunkRepo) {
        this.entryRepo = entryRepo;
        this.chunkRepo = chunkRepo;
    }

    /**
     * 重写分块并更新索引状态：有向量 → ready；无向量（端点未配或生成失败）→ disabled，
     * 文本仍可被 BM25 检索。返回落库后的分块（含 id，供 Lucene chunkId 对齐）。
     *
     * @param degradedReason 无向量时的说明（端点未配为 null）
     */
    @Transactional
    public List<KnowledgeChunkEntity> writeChunks(long entryId, List<TextChunker.Chunk> pieces,
                                                 List<float[]> vectors, EmbeddingResolver.Resolution resolution,
                                                 String degradedReason) {
        KnowledgeEntryEntity entry = entryRepo.findById(entryId).orElse(null);
        if (entry == null) {
            return List.of();
        }
        chunkRepo.deleteByEntryId(entryId);
        List<KnowledgeChunkEntity> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            TextChunker.Chunk piece = pieces.get(i);
            KnowledgeChunkEntity c = new KnowledgeChunkEntity();
            c.setKbId(entry.getKbId());
            c.setEntryId(entryId);
            c.setChunkIndex(i);
            c.setContent(piece.text());
            c.setTokenCount(piece.text().length());
            c.setHeadingPath(truncate(piece.headingPath(), 512));
            c.setStartOffset(piece.startOffset());
            c.setEndOffset(piece.endOffset());
            c.setChunkHash(sha256(piece.text()));
            chunks.add(c);
        }
        if (!chunks.isEmpty()) {
            chunkRepo.saveAll(chunks);
        }
        boolean empty = pieces.isEmpty();
        boolean vectorized = !empty && vectors != null && vectors.size() == pieces.size()
                && vectors.stream().allMatch(v -> v != null && v.length > 0);
        entry.setUpdatedAt(Instant.now());
        entry.setIndexedDimensions(vectorized ? vectors.get(0).length : null);
        entry.setIndexedEndpointId(resolution.available() ? resolution.endpointId() : null);
        entry.setIndexedModel(resolution.available() ? resolution.model() : null);
        if (vectorized || empty) {
            entry.setIndexStatus(KnowledgeEntryEntity.INDEX_READY);
            entry.setIndexError(null);
        } else {
            entry.setIndexStatus(KnowledgeEntryEntity.INDEX_DISABLED);
            entry.setIndexError(degradedReason == null ? null : truncate(degradedReason, 1000));
        }
        entryRepo.save(entry);
        return chunks;
    }

    @Transactional
    public void markFailed(long entryId, String message) {
        entryRepo.findById(entryId).ifPresent(entry -> {
            entry.setIndexStatus(KnowledgeEntryEntity.INDEX_FAILED);
            entry.setIndexError(message == null ? null : truncate(message, 1000));
            entry.setUpdatedAt(Instant.now());
            entryRepo.save(entry);
        });
    }

    @Transactional
    public void markPending(long entryId) {
        entryRepo.findById(entryId).ifPresent(entry -> {
            entry.setIndexStatus(KnowledgeEntryEntity.INDEX_PENDING);
            entry.setIndexError(null);
            entry.setUpdatedAt(Instant.now());
            entryRepo.save(entry);
        });
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
