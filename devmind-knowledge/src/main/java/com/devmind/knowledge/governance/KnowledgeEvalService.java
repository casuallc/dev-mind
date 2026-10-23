package com.devmind.knowledge.governance;

import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.dto.KnowledgeSearchV2Request;
import com.devmind.knowledge.dto.KnowledgeSearchV2Response;
import com.devmind.knowledge.retrieve.KnowledgeSearchService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-85 FR-30：固定数据集检索评测。直接调用文档级检索，不生成答案——
 * 指标衡量"检索是否把正确证据排到前面"，是引用准确率与拒答行为的上界代理。
 *
 * <p>数据集在仓库内（默认 {@code tests/knowledge_eval/dataset.json}），
 * 改分块/换 embedding/调阈值前后同一份各跑一次即可比较。</p>
 */
@Service
public class KnowledgeEvalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEvalService.class);
    private static final int MAX_TOP_K = 100;

    private final KnowledgeSearchService searchService;
    private final KnowledgeProperties props;
    private final ObjectMapper mapper;

    public KnowledgeEvalService(KnowledgeSearchService searchService,
                                KnowledgeProperties props,
                                ObjectMapper mapper) {
        this.searchService = searchService;
        this.props = props;
        this.mapper = mapper;
    }

    public EvalReport report(Integer topK, Long kbId) {
        int requestedK = topK == null || topK <= 0 ? 0 : Math.min(topK, MAX_TOP_K);
        Path path = resolveDataset();
        String configuredPath = props.getEval().getDatasetPath();
        if (path == null || !Files.isRegularFile(path)) {
            return emptyReport(configuredPath,
                    requestedK > 0 ? requestedK : Math.max(1, props.getEval().getTopK()), kbId,
                    "DATASET_NOT_FOUND: " + configuredPath);
        }
        Dataset dataset;
        try {
            dataset = parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("评测数据集解析失败: path={} err={}", path, e.toString());
            return emptyReport(configuredPath,
                    requestedK > 0 ? requestedK : Math.max(1, props.getEval().getTopK()), kbId,
                    "DATASET_INVALID: " + oneLine(e.getMessage()));
        }
        if (dataset.cases().isEmpty()) {
            return emptyReport(configuredPath,
                    requestedK > 0 ? requestedK : Math.max(1, props.getEval().getTopK()), kbId,
                    "DATASET_EMPTY");
        }
        int k = requestedK > 0 ? requestedK : Math.min(MAX_TOP_K, Math.max(1, dataset.topK()));

        List<EvalReport.CaseResult> results = new ArrayList<>(dataset.cases().size());
        Set<String> degraded = new LinkedHashSet<>();
        for (Case testCase : dataset.cases()) {
            KnowledgeSearchV2Response response = searchService.searchDocuments(
                    new KnowledgeSearchV2Request(kbId == null ? List.of() : List.of(kbId),
                            testCase.question(), null, null, null, k));
            if (response.degradedReason() != null && !"NONE".equals(response.degradedReason())) {
                degraded.add(response.degradedReason());
            }
            results.add(evaluate(testCase, response.hits()));
        }

        int answerable = 0;
        int unanswerable = 0;
        int recalled = 0;
        int refused = 0;
        double rrSum = 0;
        double precisionSum = 0;
        for (EvalReport.CaseResult r : results) {
            if (r.answerable()) {
                answerable++;
                if (r.hit()) {
                    recalled++;
                }
                rrSum += r.reciprocalRank();
                precisionSum += r.citationPrecision();
            } else {
                unanswerable++;
                if (r.refused()) {
                    refused++;
                }
            }
        }
        return new EvalReport(dataset.name(), path.toString(), Instant.now(), k, kbId,
                results.size(), answerable, unanswerable,
                ratio(recalled, answerable), ratio(rrSum, answerable),
                ratio(precisionSum, answerable), ratio(refused, unanswerable),
                degraded.isEmpty() ? "NONE" : String.join(",", degraded), results);
    }

    private EvalReport.CaseResult evaluate(Case testCase, List<KnowledgeSearchV2Response.Hit> hits) {
        List<EvalReport.HitRef> refs = new ArrayList<>(hits.size());
        int firstRelevant = 0;
        int relevantCount = 0;
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeSearchV2Response.Hit hit = hits.get(i);
            boolean relevant = matches(hit, testCase);
            if (relevant) {
                relevantCount++;
                if (firstRelevant == 0) {
                    firstRelevant = i + 1;
                }
            }
            refs.add(new EvalReport.HitRef(i + 1, hit.entryId(), hit.title(), hit.docType(), relevant));
        }
        boolean hit = firstRelevant > 0;
        double reciprocalRank = firstRelevant == 0 ? 0 : 1.0 / firstRelevant;
        double precision = hits.isEmpty() ? 0 : (double) relevantCount / hits.size();
        boolean refused = !testCase.answerable() && hits.isEmpty();
        return new EvalReport.CaseResult(testCase.id(), testCase.question(), testCase.answerable(),
                hit, firstRelevant, reciprocalRank, precision, refused, refs,
                testCase.expectedEntryNames(), testCase.expectedChunkContains());
    }

    private boolean matches(KnowledgeSearchV2Response.Hit hit, Case testCase) {
        String title = hit.title() == null ? "" : hit.title().strip().toLowerCase(Locale.ROOT);
        for (String expected : testCase.expectedEntryNames()) {
            String e = expected.strip().toLowerCase(Locale.ROOT);
            if (!e.isEmpty() && (title.equals(e) || title.contains(e) || e.contains(title))) {
                return true;
            }
        }
        if (testCase.expectedChunkContains().isEmpty()) {
            return false;
        }
        StringBuilder text = new StringBuilder();
        if (hit.snippet() != null) {
            text.append(hit.snippet()).append(' ');
        }
        if (hit.chunks() != null) {
            hit.chunks().forEach(c -> text.append(c.snippet()).append(' '));
        }
        String plain = stripMarkup(text.toString()).toLowerCase(Locale.ROOT);
        for (String expected : testCase.expectedChunkContains()) {
            String e = expected.strip().toLowerCase(Locale.ROOT);
            if (!e.isEmpty() && plain.contains(e)) {
                return true;
            }
        }
        return false;
    }

    private Dataset parse(String json) {
        JsonNode root = mapper.readTree(json);
        String name = root.path("name").asText("unnamed");
        int topK = root.path("topK").asInt(props.getEval().getTopK());
        List<Case> cases = new ArrayList<>();
        JsonNode array = root.path("cases");
        if (!array.isArray()) {
            throw new IllegalArgumentException("cases 必须是数组");
        }
        int index = 0;
        for (JsonNode n : array) {
            index++;
            String question = n.path("question").asText("").strip();
            if (question.isEmpty()) {
                continue;
            }
            cases.add(new Case(
                    n.path("id").asText("q" + index),
                    question,
                    n.path("answerable").asBoolean(true),
                    strings(n.path("expectedEntryNames")),
                    strings(n.path("expectedChunkContains"))));
        }
        return new Dataset(name, topK, cases);
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(v -> {
                String s = v.asText("").strip();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            });
        }
        return out;
    }

    private Path resolveDataset() {
        String configured = props.getEval().getDatasetPath();
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path path = Paths.get(configured);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        // 开发态从仓库根启动；测试/分发包的 user.dir 可能更深，向上找三级
        Path base = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 4 && base != null; i++) {
            Path candidate = base.resolve(path).normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            base = base.getParent();
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().resolve(path).normalize();
    }

    private EvalReport emptyReport(String path, int topK, Long kbId, String reason) {
        return new EvalReport("n/a", path, Instant.now(), topK, kbId,
                0, 0, 0, 0, 0, 0, 0, reason, List.of());
    }

    private static double ratio(double numerator, int denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private static String stripMarkup(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        return html.replaceAll("<[^>]+>", " ")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    private record Dataset(String name, int topK, List<Case> cases) {
    }

    private record Case(String id, String question, boolean answerable,
                        List<String> expectedEntryNames, List<String> expectedChunkContains) {
    }
}
