package com.devmind.knowledge.retrieve;

import com.devmind.common.knowledge.KnowledgeRetriever;
import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.embedding.EmbeddingResolver;
import com.devmind.knowledge.embedding.VectorJson;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeChunkEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeChunkRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CAP-44 FR-06 检索实现（CAP-48 改造）：embedding 可用 → 查询向量化 + 库内 chunks
 * Java 内存余弦 + 阈值/topK；无可用端点 → searchInBases LIKE 降级（score=0，内容截断）。
 * 检索异常按无命中降级不抛。
 *
 * <p>CAP-48 起端点按库解析，所以是<b>按端点分组</b>分别算分再合并：不同库可以挂不同端点
 * （不同模型、不同维度），混在一起算余弦只会得到一堆 0 分，且无法解释为什么。
 * 分组后还能顺手把 FR-06 要防的事故诊断出来——见 {@link #vectorRetrieveGroup}。</p>
 */
@Service
public class KnowledgeRetrieverImpl implements KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrieverImpl.class);
    /** 单库集合 chunk 加载上限，防内存膨胀（远超即告警截断） */
    static final int CHUNK_LOAD_LIMIT = 20_000;
    /** LIKE 降级命中内容截断长度 */
    static final int FALLBACK_CONTENT_LEN = 500;
    /** 库概览条目名清单上限（CAP-46 会话启动注入，防爆上下文） */
    static final int OVERVIEW_ENTRY_LIMIT = 50;

    private final KnowledgeChunkRepository chunkRepo;
    private final KnowledgeEntryRepository entryRepo;
    private final KnowledgeBaseRepository kbRepo;
    private final EmbeddingResolver resolver;
    private final KnowledgeProperties props;

    public KnowledgeRetrieverImpl(KnowledgeChunkRepository chunkRepo,
                                  KnowledgeEntryRepository entryRepo,
                                  KnowledgeBaseRepository kbRepo,
                                  EmbeddingResolver resolver,
                                  KnowledgeProperties props) {
        this.chunkRepo = chunkRepo;
        this.entryRepo = entryRepo;
        this.kbRepo = kbRepo;
        this.resolver = resolver;
        this.props = props;
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
        if (kbIds == null || kbIds.isEmpty() || query == null || query.isBlank()) {
            return new Detailed(List.of(), vectorAvailable(), DegradedReason.NONE);
        }
        int limit = topK > 0 ? topK : props.getEmbedding().getTopK();
        try {
            Map<Long, EmbeddingResolver.Resolution> byKb = resolveByKb(kbIds);
            List<RetrievedChunk> hits = new ArrayList<>();
            boolean vector = false;
            boolean mismatch = false;
            List<Long> degradedKbs = new ArrayList<>();
            for (Map.Entry<Long, EmbeddingResolver.Resolution> group : groupByEndpoint(byKb).entrySet()) {
                EmbeddingResolver.Resolution r = group.getValue();
                List<Long> groupKbs = kbsOf(group.getKey(), byKb);
                if (!r.available()) {
                    degradedKbs.addAll(groupKbs);
                    continue;
                }
                vector = true;
                GroupResult gr = vectorRetrieveGroup(groupKbs, query, r);
                mismatch |= gr.mismatch();
                hits.addAll(gr.hits());
            }
            if (!degradedKbs.isEmpty()) {
                // 部分库没端点：那部分退回 LIKE，不能让它们静默变成 0 命中
                hits.addAll(likeFallback(degradedKbs, query, limit));
            }
            hits.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
            List<RetrievedChunk> top = hits.size() <= limit ? hits : hits.subList(0, limit);
            DegradedReason reason = mismatch ? DegradedReason.DIMENSION_MISMATCH
                    : (degradedKbs.isEmpty() ? DegradedReason.NONE : DegradedReason.NO_EMBEDDING);
            if (mismatch) {
                log.warn("知识检索维度失配：库内索引维度与当前端点不一致，命中被阈值过滤: kbs={}", kbIds);
            }
            return new Detailed(top, vector, reason);
        } catch (Exception e) {
            log.warn("知识检索失败（按无命中降级）: kbIds={} err={}", kbIds, e.toString());
            return new Detailed(List.of(), false, DegradedReason.NO_EMBEDDING);
        }
    }

    @Override
    public Optional<KbOverview> overview(long kbId) {
        return kbRepo.findById(kbId)
                .filter(kb -> KnowledgeBaseEntity.STATUS_ACTIVE.equals(kb.getStatus()))
                .map(kb -> new KbOverview(kb.getName(), kb.getDescription(), kb.getInjectMode(),
                        entryRepo.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, "active").stream()
                                .limit(OVERVIEW_ENTRY_LIMIT)
                                .map(KnowledgeEntryEntity::getName)
                                .toList()));
    }

    /** 每个库各自解析端点（库级覆盖 → 平台默认 → 无） */
    private Map<Long, EmbeddingResolver.Resolution> resolveByKb(List<Long> kbIds) {
        Map<Long, EmbeddingResolver.Resolution> byKb = new LinkedHashMap<>();
        Map<Long, KnowledgeBaseEntity> bases = kbRepo.findAllById(kbIds).stream()
                .collect(Collectors.toMap(KnowledgeBaseEntity::getId, Function.identity()));
        for (Long kbId : kbIds) {
            KnowledgeBaseEntity kb = bases.get(kbId);
            byKb.put(kbId, resolver.resolve(kb == null ? null : kb.getModelEndpointId()));
        }
        return byKb;
    }

    /**
     * 把库按"解析出的端点"归组。键 = 端点 ID；null 键（CAP-44 旧全局客户端，无端点资源）
     * 统一归一组——同一进程里只有一个这样的客户端，没有混算风险。
     */
    private Map<Long, EmbeddingResolver.Resolution> groupByEndpoint(
            Map<Long, EmbeddingResolver.Resolution> byKb) {
        Map<Long, EmbeddingResolver.Resolution> groups = new LinkedHashMap<>();
        for (EmbeddingResolver.Resolution r : byKb.values()) {
            groups.putIfAbsent(r.endpointId(), r);
        }
        return groups;
    }

    private static List<Long> kbsOf(Long endpointId, Map<Long, EmbeddingResolver.Resolution> byKb) {
        List<Long> ids = new ArrayList<>();
        for (Map.Entry<Long, EmbeddingResolver.Resolution> e : byKb.entrySet()) {
            if (java.util.Objects.equals(e.getValue().endpointId(), endpointId)) {
                ids.add(e.getKey());
            }
        }
        return ids;
    }

    private GroupResult vectorRetrieveGroup(List<Long> kbIds, String query, EmbeddingResolver.Resolution r) {
        float[] qv = r.client().embed(List.of(query)).get(0);
        List<KnowledgeChunkEntity> chunks = chunkRepo.findByKbIdIn(kbIds);
        if (chunks.size() > CHUNK_LOAD_LIMIT) {
            log.warn("知识检索 chunk 量 {} 超上限 {}，截断参与计算", chunks.size(), CHUNK_LOAD_LIMIT);
            chunks = chunks.subList(0, CHUNK_LOAD_LIMIT);
        }
        Map<Long, KnowledgeEntryEntity> entries = entryRepo.findAllById(
                        chunks.stream().map(KnowledgeChunkEntity::getEntryId).distinct().toList())
                .stream().collect(Collectors.toMap(KnowledgeEntryEntity::getId, Function.identity()));
        double threshold = r.threshold();
        List<RetrievedChunk> hits = new ArrayList<>();
        boolean mismatch = false;
        for (KnowledgeChunkEntity c : chunks) {
            KnowledgeEntryEntity entry = entries.get(c.getEntryId());
            if (entry == null || !"active".equals(entry.getStatus())) {
                continue;
            }
            float[] cv = VectorJson.parse(c.getEmbedding());
            if (cv != null && cv.length != qv.length) {
                // 维度不等余弦必为 0（VectorJson.cosine 直接返回 0），旧行为是静默全空，
                // 用户只看到"搜不到"；这里把它显式记下来，检索响应带 DIMENSION_MISMATCH
                mismatch = true;
                continue;
            }
            double score = VectorJson.cosine(qv, cv);
            if (score >= threshold) {
                hits.add(new RetrievedChunk(c.getEntryId(), entry.getName(), c.getKbId(),
                        c.getContent(), score));
            }
        }
        return new GroupResult(hits, mismatch);
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

    private record GroupResult(List<RetrievedChunk> hits, boolean mismatch) {
    }
}
