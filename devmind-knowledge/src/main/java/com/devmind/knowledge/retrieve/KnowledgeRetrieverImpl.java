package com.devmind.knowledge.retrieve;

import com.devmind.common.knowledge.KnowledgeRetriever;
import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.embedding.EmbeddingClient;
import com.devmind.knowledge.embedding.VectorJson;
import com.devmind.knowledge.model.KnowledgeChunkEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeChunkRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CAP-44 FR-06 检索实现：embedding 可用 → 查询向量化 + 库内 chunks Java 内存余弦 + 阈值/topK；
 * 未配置 → searchInBases LIKE 降级（score=0，内容截断）。检索异常按无命中降级不抛。
 */
@Service
public class KnowledgeRetrieverImpl implements KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrieverImpl.class);
    /** 单库集合 chunk 加载上限，防内存膨胀（远超即告警截断） */
    static final int CHUNK_LOAD_LIMIT = 20_000;
    /** LIKE 降级命中内容截断长度 */
    static final int FALLBACK_CONTENT_LEN = 500;

    private final KnowledgeChunkRepository chunkRepo;
    private final KnowledgeEntryRepository entryRepo;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeProperties props;

    public KnowledgeRetrieverImpl(KnowledgeChunkRepository chunkRepo,
                                  KnowledgeEntryRepository entryRepo,
                                  EmbeddingClient embeddingClient,
                                  KnowledgeProperties props) {
        this.chunkRepo = chunkRepo;
        this.entryRepo = entryRepo;
        this.embeddingClient = embeddingClient;
        this.props = props;
    }

    @Override
    public boolean vectorAvailable() {
        return embeddingClient.available();
    }

    @Override
    public List<RetrievedChunk> retrieve(List<Long> kbIds, String query, int topK) {
        if (kbIds == null || kbIds.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        int limit = topK > 0 ? topK : props.getEmbedding().getTopK();
        try {
            return embeddingClient.available()
                    ? vectorRetrieve(kbIds, query, limit)
                    : likeFallback(kbIds, query, limit);
        } catch (Exception e) {
            log.warn("知识检索失败（按无命中降级）: kbIds={} err={}", kbIds, e.toString());
            return List.of();
        }
    }

    private List<RetrievedChunk> vectorRetrieve(List<Long> kbIds, String query, int limit) {
        float[] qv = embeddingClient.embed(List.of(query)).get(0);
        List<KnowledgeChunkEntity> chunks = chunkRepo.findByKbIdIn(kbIds);
        if (chunks.size() > CHUNK_LOAD_LIMIT) {
            log.warn("知识检索 chunk 量 {} 超上限 {}，截断参与计算", chunks.size(), CHUNK_LOAD_LIMIT);
            chunks = chunks.subList(0, CHUNK_LOAD_LIMIT);
        }
        Map<Long, KnowledgeEntryEntity> entries = entryRepo.findAllById(
                        chunks.stream().map(KnowledgeChunkEntity::getEntryId).distinct().toList())
                .stream().collect(Collectors.toMap(KnowledgeEntryEntity::getId, Function.identity()));
        double threshold = props.getEmbedding().getThreshold();
        List<RetrievedChunk> hits = new ArrayList<>();
        for (KnowledgeChunkEntity c : chunks) {
            KnowledgeEntryEntity entry = entries.get(c.getEntryId());
            if (entry == null || !"active".equals(entry.getStatus())) {
                continue;
            }
            double score = VectorJson.cosine(qv, VectorJson.parse(c.getEmbedding()));
            if (score >= threshold) {
                hits.add(new RetrievedChunk(c.getEntryId(), entry.getName(), c.getKbId(),
                        c.getContent(), score));
            }
        }
        hits.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        return hits.size() <= limit ? hits : hits.subList(0, limit);
    }

    private List<RetrievedChunk> likeFallback(List<Long> kbIds, String query, int limit) {
        return entryRepo.searchInBases(kbIds, query).stream()
                .limit(limit)
                .map(e -> new RetrievedChunk(e.getId(), e.getName(), e.getKbId(),
                        abbreviate(e.getContentMd()), 0.0))
                .toList();
    }

    private static String abbreviate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= FALLBACK_CONTENT_LEN
                ? content
                : content.substring(0, FALLBACK_CONTENT_LEN) + "…";
    }
}
