package com.devmind.knowledge.retrieve;

import com.devmind.common.knowledge.KnowledgeRetriever.DegradedReason;
import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.dto.KnowledgeSearchV2Request;
import com.devmind.knowledge.dto.KnowledgeSearchV2Response;
import com.devmind.knowledge.embedding.EmbeddingResolver;
import com.devmind.knowledge.index.KnowledgeSearchIndex;
import com.devmind.knowledge.index.KnowledgeSearchIndex.Filter;
import com.devmind.knowledge.index.KnowledgeSearchIndex.RankedHit;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeChunkEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeChunkRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CAP-84 FR-11/FR-12/FR-13 混合检索：Lucene BM25 + 向量 kNN 各自召回，RRF（k=60）融合，
 * 再按文档聚合、生成高亮片段。BM25 不依赖 embedding，端点缺失时检索仍可用。
 *
 * <p>RRF 只看排名不看绝对分，天然兼容 BM25 与余弦两种量纲；同一块被两路同时召回会自然上浮。</p>
 */
@Service
public class KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchService.class);
    static final int RRF_K = 60;
    private static final int DEFAULT_TOP_K = 20;
    private static final int MAX_TOP_K = 100;
    private static final int SNIPPET_LEN = 320;
    private static final int MAX_TERMS = 8;

    private final KnowledgeSearchIndex searchIndex;
    private final KnowledgeChunkRepository chunkRepo;
    private final KnowledgeEntryRepository entryRepo;
    private final KnowledgeBaseRepository kbRepo;
    private final EmbeddingResolver resolver;
    private final KnowledgeProperties props;

    public KnowledgeSearchService(KnowledgeSearchIndex searchIndex,
                                  KnowledgeChunkRepository chunkRepo,
                                  KnowledgeEntryRepository entryRepo,
                                  KnowledgeBaseRepository kbRepo,
                                  EmbeddingResolver resolver,
                                  KnowledgeProperties props) {
        this.searchIndex = searchIndex;
        this.chunkRepo = chunkRepo;
        this.entryRepo = entryRepo;
        this.kbRepo = kbRepo;
        this.resolver = resolver;
        this.props = props;
    }

    /** 块级混合检索（chat 注入走这里）。tags 多选为 OR。 */
    public Fused fusedChunks(String query, List<Long> kbIds, List<String> docTypes,
                             String projectId, List<String> tags, int limit) {
        if (query == null || query.isBlank()) {
            return new Fused(List.of(), false, DegradedReason.NONE);
        }
        List<KnowledgeBaseEntity> kbs = resolveBases(kbIds);
        if (kbs.isEmpty()) {
            return new Fused(List.of(), false, DegradedReason.NO_EMBEDDING);
        }
        List<Long> effectiveKbIds = kbs.stream().map(KnowledgeBaseEntity::getId).toList();
        int candidate = Math.max(10, props.getIndex().getCandidateTopK());
        Map<Long, Double> scores = new LinkedHashMap<>();

        List<RankedHit> textHits = searchIndex.searchText(
                new Filter(effectiveKbIds, docTypes, projectId, tags), query, candidate);
        addRrf(scores, textHits);

        boolean vector = false;
        boolean mismatch = false;
        for (Map.Entry<Long, List<Long>> group : groupByEndpoint(kbs).entrySet()) {
            EmbeddingResolver.Resolution resolution = resolver.resolve(group.getKey());
            if (!resolution.available()) {
                continue;
            }
            try {
                float[] qv = resolution.client().embed(List.of(query)).get(0);
                var indexedDims = searchIndex.vectorDimensions();
                if (indexedDims.isEmpty() || !indexedDims.contains(qv.length)) {
                    if (!indexedDims.isEmpty()) {
                        mismatch = true;
                    }
                    continue;
                }
                List<RankedHit> vectorHits = searchIndex.searchVector(
                        new Filter(group.getValue(), docTypes, projectId, tags), qv, candidate);
                vector = true;
                addRrf(scores, vectorHits);
            } catch (Exception e) {
                log.warn("知识向量检索失败，仅保留 BM25 结果: endpoint={} err={}",
                        group.getKey(), e.toString());
            }
        }
        List<ChunkHit> chunks = hydrate(scores);
        double minScore = props.getIndex().getMinScore();
        if (minScore > 0) {
            chunks = chunks.stream().filter(c -> c.score() >= minScore).toList();
        }
        if (chunks.size() > limit) {
            chunks = chunks.subList(0, limit);
        }
        DegradedReason reason;
        if (vector) {
            reason = mismatch ? DegradedReason.DIMENSION_MISMATCH : DegradedReason.NONE;
        } else {
            reason = mismatch ? DegradedReason.DIMENSION_MISMATCH : DegradedReason.NO_EMBEDDING;
        }
        if (chunks.isEmpty() && indexEmpty()) {
            reason = DegradedReason.INDEX_EMPTY;
        }
        return new Fused(chunks, vector, reason);
    }

    /** 文档级检索：块级融合 → 按文档聚合（最高块分 + 多块命中微加成）→ 多片段/分块明细。 */
    public KnowledgeSearchV2Response searchDocuments(KnowledgeSearchV2Request req) {
        int topK = req.topK() == null || req.topK() <= 0
                ? DEFAULT_TOP_K : Math.min(req.topK(), MAX_TOP_K);
        List<KnowledgeBaseEntity> kbs = resolveBases(req.kbIds());
        if (req.query() == null || req.query().isBlank() || kbs.isEmpty()) {
            return new KnowledgeSearchV2Response(List.of(), 0, false, DegradedReason.NONE.name());
        }
        if (indexEmpty()) {
            return new KnowledgeSearchV2Response(List.of(), 0, false, DegradedReason.INDEX_EMPTY.name());
        }
        int candidateChunks = Math.min(200, Math.max(topK * 4, 40));
        Fused fused = fusedChunks(req.query(), req.kbIds(), req.docTypes(),
                req.projectId(), req.tags(), candidateChunks);

        Map<Long, KnowledgeBaseEntity> kbMap = kbs.stream()
                .collect(Collectors.toMap(KnowledgeBaseEntity::getId, Function.identity()));
        Map<Long, DocAcc> accs = new LinkedHashMap<>();
        for (ChunkHit hit : fused.chunks()) {
            DocAcc acc = accs.computeIfAbsent(hit.entryId(), k -> new DocAcc(hit));
            acc.chunks.add(hit);
            acc.chunkCount++;
            if (hit.score() > acc.best.score()) {
                acc.best = hit;
            }
        }
        for (DocAcc acc : accs.values()) {
            acc.finalizeScore();
        }
        List<DocAcc> ordered = new ArrayList<>(accs.values());
        ordered.sort(Comparator.comparingDouble((DocAcc a) -> a.score).reversed());
        List<KnowledgeSearchV2Response.Hit> hits = ordered.stream()
                .limit(topK)
                .map(acc -> toHit(acc, req.query(), kbMap))
                .filter(Objects::nonNull)
                .toList();
        return new KnowledgeSearchV2Response(hits, hits.size(), fused.vector(),
                fused.reason().name());
    }

    /** FR-14 检索口径统一：条目列表搜索按混合检索命中的文档顺序返回 entryId。 */
    public List<Long> searchEntryIds(String query, List<Long> kbIds, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        Fused fused = fusedChunks(query, kbIds, null, null, null, Math.max(limit * 3, 60));
        LinkedHashSet<Long> ordered = new LinkedHashSet<>();
        for (ChunkHit hit : fused.chunks()) {
            ordered.add(hit.entryId());
            if (ordered.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(ordered);
    }

    private KnowledgeSearchV2Response.Hit toHit(DocAcc acc, String query,
                                                Map<Long, KnowledgeBaseEntity> kbMap) {
        ChunkHit best = acc.best;
        KnowledgeBaseEntity kb = kbMap.get(best.kbId());
        List<KnowledgeSearchV2Response.Snippet> snippets = acc.chunks.stream()
                .limit(3)
                .map(c -> new KnowledgeSearchV2Response.Snippet(
                        snippet(c.content(), query, SNIPPET_LEN), c.chunkId(),
                        c.headingPath(), c.startOffset(), c.endOffset()))
                .toList();
        List<KnowledgeSearchV2Response.ChunkRef> chunkRefs = acc.chunks.stream()
                .limit(5)
                .map(c -> new KnowledgeSearchV2Response.ChunkRef(
                        c.chunkId(), c.chunkIndex(), c.headingPath(), c.startOffset(), c.endOffset(),
                        snippet(c.content(), query, 240), c.score()))
                .toList();
        String firstSnippet = snippets.isEmpty() ? "" : snippets.get(0).text();
        return new KnowledgeSearchV2Response.Hit(
                best.entryId(), best.kbId(), kb == null ? null : kb.getName(),
                best.entryName(), best.docType(), best.path(), best.sourceUri(),
                best.summary(), splitTags(best.tags()),
                firstSnippet,
                best.chunkId(), best.headingPath(), best.startOffset(), best.endOffset(),
                acc.score, acc.chunkCount, best.updatedAt(),
                snippets, chunkRefs);
    }

    private boolean indexEmpty() {
        return searchIndex.numDocs() == 0 && entryRepo.count() > 0;
    }

    private List<ChunkHit> hydrate(Map<Long, Double> scores) {
        if (scores.isEmpty()) {
            return List.of();
        }
        List<Long> chunkIds = scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
        Map<Long, KnowledgeChunkEntity> chunks = chunkRepo.findAllById(chunkIds).stream()
                .collect(Collectors.toMap(KnowledgeChunkEntity::getId, Function.identity()));
        List<Long> entryIds = chunks.values().stream()
                .map(KnowledgeChunkEntity::getEntryId).distinct().toList();
        Map<Long, KnowledgeEntryEntity> entries = entryRepo.findAllById(entryIds).stream()
                .collect(Collectors.toMap(KnowledgeEntryEntity::getId, Function.identity()));
        List<ChunkHit> hits = new ArrayList<>(chunks.size());
        for (Long chunkId : chunkIds) {
            KnowledgeChunkEntity chunk = chunks.get(chunkId);
            if (chunk == null) {
                continue;
            }
            KnowledgeEntryEntity entry = entries.get(chunk.getEntryId());
            if (entry == null || !"active".equals(entry.getStatus())) {
                continue;
            }
            if (entry.getValidTo() != null && entry.getValidTo().isBefore(Instant.now())) {
                continue;
            }
            if (entry.getValidFrom() != null && entry.getValidFrom().isAfter(Instant.now())) {
                continue;
            }
            hits.add(new ChunkHit(chunkId, entry.getId(), entry.getKbId(), scores.get(chunkId),
                    entry.getName(), chunk.getContent(), chunk.getHeadingPath(), chunk.getChunkIndex(),
                    chunk.getStartOffset(), chunk.getEndOffset(),
                    summary(entry), entry.getDocType(), entry.getPath(), entry.getSourceUri(),
                    entry.getTags(), entry.getUpdatedAt()));
        }
        return hits;
    }

    /**
     * 智能体工具检索范围：全部 global active 库 + 当前项目 active 库；
     * 未给项目时只查 global 库，避免把别的项目库喂给模型。
     */
    public List<Long> scopedKbIds(String projectId) {
        return kbRepo.findByStatusOrderByCreatedAtDesc(KnowledgeBaseEntity.STATUS_ACTIVE).stream()
                .filter(kb -> KnowledgeBaseEntity.SCOPE_GLOBAL.equals(kb.getScope())
                        || (projectId != null && !projectId.isBlank()
                        && projectId.equals(kb.getProjectId())))
                .map(KnowledgeBaseEntity::getId)
                .toList();
    }

    private List<KnowledgeBaseEntity> resolveBases(List<Long> kbIds) {
        List<KnowledgeBaseEntity> bases = kbIds == null || kbIds.isEmpty()
                ? kbRepo.findByStatusOrderByCreatedAtDesc(KnowledgeBaseEntity.STATUS_ACTIVE)
                : kbRepo.findAllById(kbIds);
        return bases.stream()
                .filter(kb -> kb.getId() != null
                        && (kb.getStatus() == null || KnowledgeBaseEntity.STATUS_ACTIVE.equals(kb.getStatus())))
                .toList();
    }

    private Map<Long, List<Long>> groupByEndpoint(List<KnowledgeBaseEntity> kbs) {
        Map<Long, List<Long>> groups = new LinkedHashMap<>();
        for (KnowledgeBaseEntity kb : kbs) {
            groups.computeIfAbsent(kb.getModelEndpointId(), k -> new ArrayList<>()).add(kb.getId());
        }
        return groups;
    }

    private static void addRrf(Map<Long, Double> scores, List<RankedHit> hits) {
        int rank = 0;
        for (RankedHit hit : hits) {
            rank++;
            scores.merge(hit.chunkId(), 1.0 / (RRF_K + rank), Double::sum);
        }
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return new LinkedHashSet<>(List.of(tags.split(","))).stream()
                .map(String::strip).filter(t -> !t.isEmpty()).toList();
    }

    private static String summary(KnowledgeEntryEntity entry) {
        String summary = entry.getSummary();
        if (summary != null && !summary.isBlank()) {
            return summary;
        }
        String content = entry.getContentMd() == null ? "" : entry.getContentMd().replaceAll("\\s+", " ").strip();
        return content.length() <= 160 ? content : content.substring(0, 160) + "…";
    }

    static String snippet(String content, String query, int max) {
        Window w = window(content, query, max);
        return (w.leading() ? "…" : "") + highlight(w.text(), terms(query))
                + (w.trailing() ? "…" : "");
    }

    /** 纯文本片段（引用快照/聊天前端用）：不插高亮标签，保持原文（前端按文本渲染）。 */
    static String plainSnippet(String content, String query, int max) {
        Window w = window(content, query, max);
        return (w.leading() ? "…" : "") + w.text() + (w.trailing() ? "…" : "");
    }

    private static Window window(String content, String query, int max) {
        String text = content == null ? "" : content.replace("```", " ").replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) {
            return new Window("", false, false);
        }
        int first = text.length();
        for (String term : terms(query)) {
            int idx = indexOfIgnoreCase(text, term, 0);
            if (idx >= 0 && idx < first) {
                first = idx;
            }
        }
        if (first == text.length()) {
            first = 0;
        }
        int start = Math.max(0, first - max / 3);
        if (start + max > text.length()) {
            start = Math.max(0, text.length() - max);
        }
        int end = Math.min(text.length(), start + max);
        return new Window(text.substring(start, end), start > 0, end < text.length());
    }

    private record Window(String text, boolean leading, boolean trailing) {
    }

    private static String highlight(String text, List<String> terms) {
        if (terms.isEmpty()) {
            return escape(text);
        }
        boolean[] mark = new boolean[text.length()];
        for (String term : terms) {
            int from = 0;
            while (from < text.length()) {
                int idx = indexOfIgnoreCase(text, term, from);
                if (idx < 0) {
                    break;
                }
                for (int i = idx; i < idx + term.length(); i++) {
                    mark[i] = true;
                }
                from = idx + Math.max(1, term.length());
            }
        }
        StringBuilder sb = new StringBuilder(text.length() + 32);
        boolean inMark = false;
        for (int i = 0; i < text.length(); i++) {
            if (mark[i] != inMark) {
                sb.append(inMark ? "</mark>" : "<mark>");
                inMark = mark[i];
            }
            appendEscaped(sb, text.charAt(i));
        }
        if (inMark) {
            sb.append("</mark>");
        }
        return sb.toString();
    }

    private static List<String> terms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return List.of(query.strip().split("\\s+")).stream()
                .map(t -> t.length() > 50 ? t.substring(0, 50) : t)
                .filter(t -> !t.isEmpty())
                .limit(MAX_TERMS)
                .toList();
    }

    private static int indexOfIgnoreCase(String text, String term, int from) {
        if (term.isEmpty()) {
            return -1;
        }
        int last = text.length() - term.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (text.regionMatches(true, i, term, 0, term.length())) {
                return i;
            }
        }
        return -1;
    }

    private static void appendEscaped(StringBuilder sb, char c) {
        switch (c) {
            case '&' -> sb.append("&amp;");
            case '<' -> sb.append("&lt;");
            case '>' -> sb.append("&gt;");
            case '"' -> sb.append("&quot;");
            case '\'' -> sb.append("&#39;");
            default -> sb.append(c);
        }
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            appendEscaped(sb, text.charAt(i));
        }
        return sb.toString();
    }

    private static final class DocAcc {
        private final long entryId;
        private double score;
        private int chunkCount;
        private ChunkHit best;
        private final List<ChunkHit> chunks = new ArrayList<>();

        private DocAcc(ChunkHit first) {
            this.entryId = first.entryId();
            this.best = first;
        }

        /** FR-12：文档分 = 最高块分 × (1 + 0.05 × 多块命中加成，最多 15%)。 */
        private void finalizeScore() {
            chunks.sort(Comparator.comparingDouble(ChunkHit::score).reversed());
            score = best.score() * (1.0 + 0.05 * Math.min(3, Math.max(0, chunkCount - 1)));
        }
    }

    public record ChunkHit(long chunkId, long entryId, Long kbId, double score, String entryName,
                           String content, String headingPath, int chunkIndex,
                           int startOffset, int endOffset,
                           String summary, String docType, String path, String sourceUri,
                           String tags, Instant updatedAt) {
    }

    public record Fused(List<ChunkHit> chunks, boolean vector, DegradedReason reason) {
    }
}
