package com.devmind.test.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.docs.DocumentService;
import com.devmind.docs.dto.DocDetail;
import com.devmind.docs.dto.DocRequest;
import com.devmind.docs.dto.SaveVersionRequest;
import com.devmind.project.ProjectService;
import com.devmind.project.dto.ProjectView;
import com.devmind.test.dto.TestCaseInput;
import com.devmind.test.dto.TestCaseView;
import com.devmind.test.dto.TestSuiteCreateRequest;
import com.devmind.test.dto.TestSuiteView;
import com.devmind.test.model.TestCaseEntity;
import com.devmind.test.model.TestSuiteEntity;
import com.devmind.test.repo.TestCaseRepository;
import com.devmind.test.repo.TestSuiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * CAP-10 测试套件管理：套件 CRUD、用例整体保存（增删改）、从 OpenAPI 生成 API 套件（FR-02）、
 * 套件沉淀到 docs-repo（FR-03）。
 */
@Service
public class TestSuiteService {

    private static final Logger log = LoggerFactory.getLogger(TestSuiteService.class);
    private static final Set<String> KINDS = Set.of("api", "smoke");
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "delete", "patch");

    private final TestSuiteRepository suiteRepo;
    private final TestCaseRepository caseRepo;
    private final ProjectService projectService;
    private final DocumentService documentService;
    private final ObjectMapper mapper;

    public TestSuiteService(TestSuiteRepository suiteRepo,
                            TestCaseRepository caseRepo,
                            ProjectService projectService,
                            DocumentService documentService,
                            ObjectMapper mapper) {
        this.suiteRepo = suiteRepo;
        this.caseRepo = caseRepo;
        this.projectService = projectService;
        this.documentService = documentService;
        this.mapper = mapper;
    }

    // ---------------- 套件 CRUD ----------------

    public List<TestSuiteView> list(String projectId) {
        return suiteRepo.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(s -> new TestSuiteView(s.getId(), s.getProjectId(), s.getName(), s.getKind(), s.getSource(),
                        s.getDocId(), caseRepo.findBySuiteIdOrderBySortAsc(s.getId()).size(), List.of(), s.getCreatedAt()))
                .toList();
    }

    public TestSuiteView getSuite(Long id) {
        TestSuiteEntity s = require(id);
        List<TestCaseView> cases = caseRepo.findBySuiteIdOrderBySortAsc(id).stream().map(this::toCaseView).toList();
        return new TestSuiteView(s.getId(), s.getProjectId(), s.getName(), s.getKind(), s.getSource(),
                s.getDocId(), cases.size(), cases, s.getCreatedAt());
    }

    public TestSuiteView create(String projectId, TestSuiteCreateRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "套件名称必填");
        }
        String kind = req.kind() == null || req.kind().isBlank() ? "api" : req.kind().toLowerCase();
        if (!KINDS.contains(kind)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "套件类型必须是 api/smoke");
        }
        TestSuiteEntity e = new TestSuiteEntity();
        e.setProjectId(projectId);
        e.setName(req.name().strip());
        e.setKind(kind);
        e.setSource("manual");
        e.setCreatedAt(Instant.now());
        suiteRepo.save(e);
        return getSuite(e.getId());
    }

    @Transactional
    public void delete(Long id) {
        TestSuiteEntity s = require(id);
        caseRepo.deleteBySuiteId(id);
        suiteRepo.delete(s);
    }

    // ---------------- 用例整体保存（FR-02 人工编辑） ----------------

    @Transactional
    public TestSuiteView saveCases(Long suiteId, List<TestCaseInput> inputs) {
        require(suiteId);
        List<TestCaseEntity> existing = caseRepo.findBySuiteIdOrderBySortAsc(suiteId);
        Set<Long> keep = new HashSet<>();
        int sort = 1;
        for (TestCaseInput in : inputs) {
            if (in == null) {
                continue;
            }
            TestCaseEntity e = in.id() != null
                    ? existing.stream().filter(x -> x.getId().equals(in.id())).findFirst().orElse(null)
                    : null;
            if (e == null) {
                e = new TestCaseEntity();
            }
            e.setSuiteId(suiteId);
            e.setSort(sort++);
            e.setName(in.name());
            e.setKind(in.kind() == null || in.kind().isBlank() ? "http" : in.kind().toLowerCase());
            e.setMethod(in.method() == null || in.method().isBlank() ? "GET" : in.method().toUpperCase());
            e.setPath(in.path());
            e.setParamsJson(writeMap(in.params()));
            e.setHeadersJson(writeMap(in.headers()));
            e.setBodyJson(in.body());
            e.setExpectedJson(writeMap(in.expected()));
            e.setEnabled(in.enabled() == null || in.enabled());
            e.setUpdatedAt(Instant.now());
            if (in.id() != null) {
                keep.add(in.id());
            }
            caseRepo.save(e);
        }
        List<Long> remove = existing.stream().map(TestCaseEntity::getId).filter(id -> !keep.contains(id)).toList();
        if (!remove.isEmpty()) {
            caseRepo.deleteByIdIn(remove);
        }
        return getSuite(suiteId);
    }

    // ---------------- OpenAPI 生成（FR-02） ----------------

    /** 从项目 apiDocSource 读取 OpenAPI 生成 API 套件；返回生成结果。 */
    public TestSuiteView generate(String projectId) {
        ProjectView p = projectService.get(projectId);
        String source = p.apiDocSource();
        if (source == null || source.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "项目未配置 apiDocSource（OpenAPI 文件位置）");
        }
        String content = readApiDoc(p, source);
        JsonNode root = parseOpenApi(content);
        JsonNode paths = root.path("paths");
        if (!paths.isObject()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "OpenAPI 内容缺少 paths 定义");
        }

        List<TestCaseEntity> generated = new ArrayList<>();
        for (Map.Entry<String, JsonNode> pe : ((ObjectNode) paths).properties()) {
            String path = pe.getKey();
            JsonNode item = pe.getValue();
            if (!item.isObject()) {
                continue;
            }
            JsonNode pathParams = item.get("parameters");
            for (Map.Entry<String, JsonNode> me : ((ObjectNode) item).properties()) {
                String method = me.getKey().toLowerCase();
                if (!HTTP_METHODS.contains(method)) {
                    continue;
                }
                JsonNode op = me.getValue();
                if (!op.isObject()) {
                    continue;
                }
                generated.add(buildHttpCase(path, method.toUpperCase(), op, pathParams, false));
                // 声明了 security 的接口补一个「未鉴权」边界用例（FR-02 鉴权维度）
                if (op.get("security") != null && op.get("security").isArray() && !op.get("security").isEmpty()) {
                    generated.add(buildHttpCase(path, method.toUpperCase(), op, pathParams, true));
                }
            }
        }
        if (generated.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "OpenAPI 未解析出任何可执行用例");
        }

        TestSuiteEntity suite = new TestSuiteEntity();
        suite.setProjectId(projectId);
        suite.setName("API 套件 " + LocalDate.now());
        suite.setKind("api");
        suite.setSource("openapi");
        suite.setCreatedAt(Instant.now());
        suiteRepo.save(suite);

        int sort = 1;
        for (TestCaseEntity c : generated) {
            c.setSuiteId(suite.getId());
            c.setSort(sort++);
            c.setEnabled(true);
            c.setUpdatedAt(Instant.now());
            caseRepo.save(c);
        }
        log.info("CAP-10 从 OpenAPI 生成套件: id={} project={} 用例数={}", suite.getId(), projectId, generated.size());
        return getSuite(suite.getId());
    }

    /** 单个 OpenAPI operation → 用例（authCase=true 时生成未鉴权边界用例，期望 401）。 */
    private TestCaseEntity buildHttpCase(String rawPath, String method, JsonNode op, JsonNode pathParams, boolean authCase) {
        TestCaseEntity c = new TestCaseEntity();
        String operationId = text(op, "operationId");
        String summary = text(op, "summary");
        String baseName = operationId != null ? operationId : (summary != null ? summary : method + " " + rawPath);
        c.setName(authCase ? baseName + "（未鉴权）" : baseName);
        c.setKind("http");
        c.setMethod(method);
        c.setPath(resolvePath(rawPath, op, pathParams));

        Map<String, String> params = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        collectParameters(op, pathParams, params, headers);
        c.setParamsJson(writeMap(params));
        if (authCase) {
            headers.remove("Authorization");
            c.setHeadersJson("{}");
            c.setExpectedJson("{\"status\":401}");
        } else {
            c.setHeadersJson(writeMap(headers));
            int status = expectedStatus(op.get("responses"));
            c.setExpectedJson("{\"status\":" + status + "}");
        }
        c.setBodyJson(requestBodyJson(op));
        return c;
    }

    private void collectParameters(JsonNode op, JsonNode pathParams, Map<String, String> params, Map<String, String> headers) {
        List<JsonNode> all = new ArrayList<>();
        if (pathParams != null && pathParams.isArray()) {
            for (JsonNode n : pathParams) all.add(n);
        }
        JsonNode opParams = op.get("parameters");
        if (opParams != null && opParams.isArray()) {
            for (JsonNode n : opParams) all.add(n);
        }
        for (JsonNode p : all) {
            String name = text(p, "name");
            String in = text(p, "in");
            if (name == null || in == null) {
                continue;
            }
            String value = paramExample(p);
            switch (in) {
                case "query" -> params.put(name, value);
                case "header" -> headers.put(name, value);
                default -> { /* path 参数已由 resolvePath 内联 */ }
            }
        }
    }

    /** 替换 path 中的 {param}：优先参数示例值，否则按语义（id→1，其余 test）。 */
    private String resolvePath(String rawPath, JsonNode op, JsonNode pathParams) {
        String out = rawPath;
        for (JsonNode p : allParameters(op, pathParams)) {
            if (!"path".equals(text(p, "in"))) {
                continue;
            }
            String name = text(p, "name");
            if (name == null) {
                continue;
            }
            out = out.replace("{" + name + "}", paramExample(p));
        }
        // 兜底：未在 parameters 声明的内联占位符（按语义取值）
        java.util.regex.Pattern pathPat = java.util.regex.Pattern.compile("\\{(\\w+)\\}");
        java.util.regex.Matcher pathMatcher = pathPat.matcher(out);
        StringBuffer buf = new StringBuffer();
        while (pathMatcher.find()) {
            String g = pathMatcher.group(1).toLowerCase();
            pathMatcher.appendReplacement(buf, g.contains("id") ? "1" : "test");
        }
        pathMatcher.appendTail(buf);
        out = buf.toString();
        return out;
    }

    private List<JsonNode> allParameters(JsonNode op, JsonNode pathParams) {
        List<JsonNode> all = new ArrayList<>();
        if (pathParams != null && pathParams.isArray()) {
            for (JsonNode n : pathParams) all.add(n);
        }
        JsonNode opParams = op.get("parameters");
        if (opParams != null && opParams.isArray()) {
            for (JsonNode n : opParams) all.add(n);
        }
        return all;
    }

    private String paramExample(JsonNode p) {
        JsonNode ex = p.get("example");
        if (ex != null && !ex.isNull()) {
            return asText(ex);
        }
        JsonNode schema = p.get("schema");
        if (schema != null && schema.isObject()) {
            JsonNode def = schema.get("default");
            if (def != null && !def.isNull()) {
                return asText(def);
            }
            JsonNode sEx = schema.get("example");
            if (sEx != null && !sEx.isNull()) {
                return asText(sEx);
            }
            String type = text(schema, "type");
            return switch (type == null ? "" : type) {
                case "integer", "number" -> "1";
                case "boolean" -> "true";
                default -> "test";
            };
        }
        return "test";
    }

    /** 从 responses 选第一个 2xx 状态码；否则取第一个 3 位数字状态码；再退化为 200。 */
    private int expectedStatus(JsonNode responses) {
        int fallback = 0;
        if (responses != null && responses.isObject()) {
            for (Map.Entry<String, JsonNode> e : ((ObjectNode) responses).properties()) {
                String key = e.getKey().replace("'", "").trim();
                if (!key.matches("\\d{3}")) {
                    continue;
                }
                int code = Integer.parseInt(key);
                if (fallback == 0) {
                    fallback = code;
                }
                if (code >= 200 && code < 300) {
                    return code;
                }
            }
        }
        return fallback == 0 ? 200 : fallback;
    }

    /** requestBody 的 application/json schema.example / default，序列化为 JSON 字符串。 */
    private String requestBodyJson(JsonNode op) {
        JsonNode rb = op.get("requestBody");
        if (rb == null) {
            return null;
        }
        JsonNode content = rb.get("content");
        if (content == null || !content.isObject()) {
            return null;
        }
        JsonNode json = content.get("application/json");
        JsonNode schema = json == null ? null : json.get("schema");
        if (schema == null) {
            return null;
        }
        JsonNode ex = schema.get("example");
        if (ex == null || ex.isNull()) {
            ex = schema.get("default");
        }
        return ex == null || ex.isNull() ? null : ex.toString();
    }

    // ---------------- 沉淀到 docs-repo（FR-03） ----------------

    public TestSuiteView publishToDocs(Long suiteId) {
        TestSuiteEntity s = require(suiteId);
        List<TestCaseEntity> cases = caseRepo.findBySuiteIdOrderBySortAsc(suiteId);
        String md = renderMarkdown(s, cases);
        String title = "API 套件: " + s.getName();
        Long docId = s.getDocId();
        if (docId != null) {
            try {
                documentService.saveVersion(docId, new SaveVersionRequest(md, "套件更新（v" + cases.size() + " 用例）"));
                return getSuite(suiteId);
            } catch (DevMindException ex) {
                log.warn("套件 {} 原文档 {} 不可用，改新建: {}", suiteId, docId, ex.getMessage());
            }
        }
        DocDetail doc = documentService.create(new DocRequest(
                "api-suite", null, s.getProjectId(), title, List.of("api-suite"), null, md));
        s.setDocId(doc.id());
        s.setCreatedAt(s.getCreatedAt());
        suiteRepo.save(s);
        return getSuite(suiteId);
    }

    private String renderMarkdown(TestSuiteEntity s, List<TestCaseEntity> cases) {
        StringBuilder md = new StringBuilder();
        md.append("# API 测试套件: ").append(s.getName()).append("\n\n");
        md.append("项目: `").append(s.getProjectId()).append("` ｜ 类型: ").append(s.getKind())
                .append(" ｜ 来源: ").append(s.getSource()).append(" ｜ 用例数: ").append(cases.size()).append("\n\n");
        md.append("| # | 方法 | 路径 | 类型 | 期望 |\n|---|---|---|---|---|\n");
        for (int i = 0; i < cases.size(); i++) {
            TestCaseEntity c = cases.get(i);
            md.append("| ").append(i + 1)
                    .append(" | ").append(safe(c.getMethod()))
                    .append(" | `").append(safe(c.getPath())).append("`")
                    .append(" | ").append(safe(c.getKind()))
                    .append(" | `").append(safe(c.getExpectedJson())).append("` |\n");
        }
        for (TestCaseEntity c : cases) {
            md.append("\n### ").append(safe(c.getName())).append("\n\n");
            md.append("- 方法: ").append(safe(c.getMethod())).append("\n");
            md.append("- 路径: `").append(safe(c.getPath())).append("`\n");
            md.append("- 参数: `").append(safe(c.getParamsJson())).append("`\n");
            md.append("- 请求体: `").append(safe(c.getBodyJson())).append("`\n");
            md.append("- 期望: `").append(safe(c.getExpectedJson())).append("`\n");
        }
        return md.toString();
    }

    // ---------------- 内部 ----------------

    /** 读取 apiDocSource 内容：doc:<id> → 文档库；http(s):// → 拉取；否则项目仓库相对文件。 */
    private String readApiDoc(ProjectView p, String source) {
        String src = source.strip();
        if (src.startsWith("doc:")) {
            String docId = src.substring(4).strip();
            try {
                DocDetail doc = documentService.get(Long.parseLong(docId), null);
                return doc.contentMd();
            } catch (NumberFormatException ex) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "apiDocSource doc: 后需文档 id");
            }
        }
        if (src.startsWith("http://") || src.startsWith("https://")) {
            try {
                return RestClient.create().get().uri(src).retrieve().body(String.class);
            } catch (Exception e) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "拉取 OpenAPI 失败: " + rootMessage(e));
            }
        }
        Path file = Path.of(p.path(), src);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "无法读取 apiDocSource: " + file + "（" + rootMessage(e) + "）");
        }
    }

    /** JSON 或 YAML → JsonNode。 */
    private JsonNode parseOpenApi(String content) {
        String trimmed = content == null ? "" : content.strip();
        if (trimmed.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "OpenAPI 内容为空");
        }
        try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return mapper.readTree(trimmed);
            }
            Object yaml = new org.yaml.snakeyaml.Yaml().load(trimmed);
            return mapper.convertValue(yaml, JsonNode.class);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "OpenAPI 解析失败: " + rootMessage(e));
        }
    }

    public TestSuiteEntity require(Long id) {
        return suiteRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "测试套件不存在: " + id));
    }

    private TestCaseView toCaseView(TestCaseEntity c) {
        return new TestCaseView(c.getId(), c.getSuiteId(), c.getSort(), c.getName(), c.getKind(), c.getMethod(),
                c.getPath(), readMap(c.getParamsJson()), readMap(c.getHeadersJson()), c.getBodyJson(),
                readObjectMap(c.getExpectedJson()), c.getEnabled(), c.getUpdatedAt());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object v = mapper.readValue(json, Object.class);
            return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object v = mapper.readValue(json, Object.class);
            Map<String, String> out = new LinkedHashMap<>();
            if (v instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    out.put(String.valueOf(en.getKey()), en.getValue() == null ? "" : String.valueOf(en.getValue()));
                }
            }
            return out;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeMap(Map<?, ?> map) {
        try {
            if (map == null || map.isEmpty()) {
                return "{}";
            }
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private String asText(JsonNode n) {
        return n.isTextual() ? n.asText() : n.toString();
    }

    private String safe(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
