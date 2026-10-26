package com.devmind.knowledge.retrieve;

import com.devmind.common.knowledge.KnowledgeRetriever;
import com.devmind.knowledge.embedding.EmbeddingResolver;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * CAP-84 检索 SPI 实现：委托 {@link KnowledgeSearchService} 的 Lucene BM25 + 向量 RRF；
 * 未配 embedding 时 BM25 仍可用，不再降级 SQL LIKE。
 *
 * <p>对外保留 CAP-44 的块级返回形状（chat 注入无需改协议），文档级聚合与高亮走
 * {@code /api/knowledge/search/v2}。</p>
 */
@Service
public class KnowledgeRetrieverImpl implements KnowledgeRetriever {

    /** 库概览条目名清单上限（CAP-46 会话启动注入，防爆上下文） */
    static final int OVERVIEW_ENTRY_LIMIT = 50;

    private final KnowledgeSearchService searchService;
    private final KnowledgeEntryRepository entryRepo;
    private final KnowledgeBaseRepository kbRepo;
    private final EmbeddingResolver resolver;

    public KnowledgeRetrieverImpl(KnowledgeSearchService searchService,
                                  KnowledgeEntryRepository entryRepo,
                                  KnowledgeBaseRepository kbRepo,
                                  EmbeddingResolver resolver) {
        this.searchService = searchService;
        this.entryRepo = entryRepo;
        this.kbRepo = kbRepo;
        this.resolver = resolver;
    }

    @Override
    public boolean vectorAvailable() {
        return resolver.anyConfigured();
    }

    @Override
    public List<RetrievedChunk> retrieve(List<Long> kbIds, String query, int topK) {
        return retrieveDetailed(kbIds, query, topK).chunks();
    }

    @Override
    public Detailed retrieveDetailed(List<Long> kbIds, String query, int topK) {
        return retrieveDetailed(kbIds, query, topK, null, null);
    }

    @Override
    public Detailed retrieveDetailed(List<Long> kbIds, String query, int topK,
                                     List<String> docTypes, List<String> tags) {
        if (kbIds == null || kbIds.isEmpty() || query == null || query.isBlank()) {
            return new Detailed(List.of(), vectorAvailable(),
                    vectorAvailable() ? DegradedReason.NONE : DegradedReason.NO_EMBEDDING);
        }
        int limit = topK > 0 ? topK : 8;
        KnowledgeSearchService.Fused fused =
                searchService.fusedChunks(query, kbIds, docTypes, null, tags, limit);
        List<RetrievedChunk> chunks = fused.chunks().stream()
                .map(h -> new RetrievedChunk(h.entryId(), h.entryName(), h.kbId(), h.chunkId(),
                        h.sourceUri(), h.headingPath(), h.content(),
                        KnowledgeSearchService.plainSnippet(h.content(), query, 240), h.score()))
                .toList();
        return new Detailed(chunks, fused.vector(), fused.reason());
    }

    @Override
    public Map<Long, String> baseNames(List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (KnowledgeBaseEntity kb : kbRepo.findAllById(kbIds)) {
            names.put(kb.getId(), kb.getName());
        }
        return names;
    }

    @Override
    public Optional<KbOverview> overview(long kbId) {
        return kbRepo.findById(kbId)
                .filter(kb -> KnowledgeBaseEntity.STATUS_ACTIVE.equals(kb.getStatus()))
                .map(kb -> new KbOverview(kb.getName(), kb.getDescription(),
                        entryRepo.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, "active").stream()
                                .limit(OVERVIEW_ENTRY_LIMIT)
                                .map(KnowledgeEntryEntity::getName)
                                .toList()));
    }
}
