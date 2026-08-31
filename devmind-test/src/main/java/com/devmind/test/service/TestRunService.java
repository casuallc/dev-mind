package com.devmind.test.service;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.deploy.event.DeploymentCompletedEvent;
import com.devmind.deploy.repo.DeploymentRepository;
import com.devmind.docs.DocumentService;
import com.devmind.docs.dto.DocDetail;
import com.devmind.docs.dto.DocRequest;
import com.devmind.notification.dto.NotificationDraft;
import com.devmind.notification.model.NotificationLevel;
import com.devmind.notification.service.NotificationService;
import com.devmind.project.ProjectService;
import com.devmind.project.RequirementService;
import com.devmind.project.dto.ProjectView;
import com.devmind.project.model.ProjectServerEntity;
import com.devmind.project.repo.ProjectServerRepository;
import com.devmind.serveradapter.config.CredentialCrypto;
import com.devmind.serveradapter.service.ServerOperationService;
import com.devmind.serveradapter.spi.HealthCheckConfig;
import com.devmind.serveradapter.spi.HealthResult;
import com.devmind.test.dto.CaseResultView;
import com.devmind.test.dto.CreateTestRunRequest;
import com.devmind.test.dto.IssueDraftView;
import com.devmind.test.dto.RunSummary;
import com.devmind.test.dto.TestRunView;
import com.devmind.test.model.TestCaseEntity;
import com.devmind.test.model.TestCaseResultEntity;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.model.TestSuiteEntity;
import com.devmind.test.repo.TestCaseRepository;
import com.devmind.test.repo.TestCaseResultRepository;
import com.devmind.test.repo.TestRunRepository;
import com.devmind.test.repo.TestSuiteRepository;
import com.devmind.execution.ws.ExecutionLogHub;

/**
 * CAP-10 测试执行：创建并异步执行 test_run（http 用例直请求 baseUrl 匹配 expected；health 用例走 CAP-07
 * 健康检查）→ 用例级结果落库 + WS 实时 → 汇总 + 报告文档（FR-04）→ 失败转缺陷线索（FR-06）。
 * 监听部署完成事件按项目 autoRegressionOnDeploy 自动回归（FR-05）。
 * 关键陷阱同构建/部署：create() 不加 @Transactional，save 自身事务即时提交后异步 run()。
 */
@Service
public class TestRunService {

    private static final Logger log = LoggerFactory.getLogger(TestRunService.class);

    private final ExecutorService testExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final TestRunRepository repo;
    private final TestCaseResultRepository resultRepo;
    private final TestCaseRepository caseRepo;
    private final TestSuiteRepository suiteRepo;
    private final ProjectService projectService;
    private final RequirementService requirementService;
    private final ProjectServerRepository serverRepo;
    private final DeploymentRepository deploymentRepo;
    private final ServerOperationService serverOpService;
    private final DocumentService documentService;
    private final NotificationService notificationService;
    private final CredentialCrypto crypto;
    private final ExecutionLogHub hub;
    private final ObjectMapper mapper;

    public TestRunService(TestRunRepository repo,
                          TestCaseResultRepository resultRepo,
                          TestCaseRepository caseRepo,
                          TestSuiteRepository suiteRepo,
                          ProjectService projectService,
                          RequirementService requirementService,
                          ProjectServerRepository serverRepo,
                          DeploymentRepository deploymentRepo,
                          ServerOperationService serverOpService,
                          DocumentService documentService,
                          NotificationService notificationService,
                          CredentialCrypto crypto,
                          ExecutionLogHub hub,
                          ObjectMapper mapper) {
        this.repo = repo;
        this.resultRepo = resultRepo;
        this.caseRepo = caseRepo;
        this.suiteRepo = suiteRepo;
        this.projectService = projectService;
        this.requirementService = requirementService;
        this.serverRepo = serverRepo;
        this.deploymentRepo = deploymentRepo;
        this.serverOpService = serverOpService;
        this.documentService = documentService;
        this.notificationService = notificationService;
        this.crypto = crypto;
        this.hub = hub;
        this.mapper = mapper;
    }

    @PreDestroy
    public void shutdown() {
        testExecutor.shutdownNow();
    }

    // ---------------- 创建 / 执行 ----------------

    public TestRunView create(CreateTestRunRequest req) {
        return createInternal(req.projectId(), req.requirementId(), req.suiteIds(), req.deploymentId(),
                req.serverId(), req.baseUrl(), "user");
    }

    private TestRunView createInternal(String projectId, String requirementId, List<Long> suiteIds,
                                       Long deploymentId, Long serverId, String baseUrl, String triggeredBy) {
        projectService.requireProject(projectId);
        if (requirementId != null && !requirementId.isBlank()) {
            // P0-6 关联约定：需求须属于该项目
            requirementService.requireEntity(projectId, requirementId);
        }
        if (suiteIds == null || suiteIds.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "至少选择 1 个测试套件");
        }
        for (Long sid : suiteIds) {
            TestSuiteEntity s = suiteRepo.findById(sid)
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "测试套件不存在: " + sid));
            if (!s.getProjectId().equals(projectId)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "套件 " + sid + " 不属于该项目");
            }
        }
        String resolvedBaseUrl = resolveBaseUrl(baseUrl, serverId, deploymentId);

        TestRunEntity r = new TestRunEntity();
        r.setProjectId(projectId);
        r.setRequirementId(requirementId == null || requirementId.isBlank() ? null : requirementId);
        r.setSuiteIdsJson(writeIds(suiteIds));
        r.setDeploymentId(deploymentId);
        r.setServerId(serverId);
        r.setBaseUrl(resolvedBaseUrl);
        r.setStatus(TestRunEntity.RUNNING);
        r.setTriggeredBy(triggeredBy);
        Instant now = Instant.now();
        r.setStartedAt(now);
        r.setCreatedAt(now);
        TestRunEntity saved = repo.save(r);
        testExecutor.submit(() -> run(saved.getId()));
        return toView(saved);
    }

    private void run(Long runId) {
        TestRunEntity r = repo.findById(runId).orElse(null);
        if (r == null) {
            return;
        }
        List<Long> suiteIds = parseIds(r.getSuiteIdsJson());
        StringBuilder logs = new StringBuilder();
        int total = 0, passed = 0, failed = 0, skipped = 0;
        int sort = 1;
        try {
            for (Long sid : suiteIds) {
                List<TestCaseEntity> cases = caseRepo.findBySuiteIdOrderBySortAsc(sid);
                for (TestCaseEntity c : cases) {
                    if (!Boolean.TRUE.equals(c.getEnabled())) {
                        continue;
                    }
                    total++;
                    CaseOutcome out = "health".equalsIgnoreCase(c.getKind())
                            ? runHealth(c, r.getServerId(), r.getBaseUrl())
                            : runHttp(c, r.getBaseUrl());
                    long dur = out.duration();

                    TestCaseResultEntity re = new TestCaseResultEntity();
                    re.setRunId(runId);
                    re.setCaseId(c.getId());
                    re.setSuiteId(sid);
                    re.setSort(sort++);
                    re.setName(c.getName() == null ? (c.getMethod() + " " + c.getPath()) : c.getName());
                    re.setStatus(out.status());
                    re.setRequestSummary(truncate(out.requestSummary(), 1000));
                    re.setResponseSummary(truncate(out.responseSummary(), 1000));
                    re.setError(truncate(out.error(), 1000));
                    re.setDuration(dur);
                    re.setCreatedAt(Instant.now());
                    resultRepo.save(re);
                    hub.publishEvent(topic(runId), "result", toResultView(re));
                    logs.append("[").append(out.status().toUpperCase()).append("] ")
                            .append(re.getName()).append(" (").append(dur).append("ms)");
                    if (out.error() != null && !out.error().isBlank()) {
                        logs.append(" — ").append(out.error());
                    }
                    logs.append('\n');

                    if ("pass".equals(out.status())) {
                        passed++;
                    } else if ("fail".equals(out.status())) {
                        failed++;
                    } else {
                        skipped++;
                    }
                }
            }
            if (total == 0) {
                logs.append("[运行结束] 选中的套件无可执行用例（全部禁用或套件为空）\n");
            }
        } catch (Exception e) {
            failed++;
            logs.append("[运行异常] ").append(rootMessage(e)).append('\n');
            r.setErrorSummary(truncate(rootMessage(e), 2000));
        }

        r.setStatus(failed > 0 ? TestRunEntity.FAILED : TestRunEntity.SUCCESS);
        r.setSummaryJson(writeSummary(total, passed, failed, skipped));
        r.setLogsText(logs.toString());
        r.setFinishedAt(Instant.now());
        repo.save(r);

        // FR-04 报告沉淀为 docs-repo 的 report 文档
        try {
            Long docId = createReportDoc(r);
            r.setReportDocId(docId);
            repo.save(r);
        } catch (Exception e) {
            log.warn("测试报告文档创建失败: {}", e.getMessage());
        }

        hub.done(topic(runId), r.getStatus());
        notify(r, failed > 0 ? NotificationLevel.P1 : NotificationLevel.P2,
                "测试" + (failed > 0 ? "失败" : "通过") + " #" + runId,
                passed + " 通过 / " + failed + " 失败 / " + skipped + " 跳过"
                        + (r.getBaseUrl() == null ? "" : " · " + r.getBaseUrl()));
    }

    /** http 用例：直请求 baseUrl+path，校验 expected.status（支持 "2XX"）与 contains。 */
    private CaseOutcome runHttp(TestCaseEntity c, String baseUrl) {
        String method = c.getMethod() == null || c.getMethod().isBlank() ? "GET" : c.getMethod().toUpperCase();
        String path = c.getPath() == null ? "" : c.getPath();
        if (baseUrl == null || baseUrl.isBlank()) {
            return new CaseOutcome("skip", method + " " + (path.isEmpty() ? "(no path)" : path),
                    null, "未配置测试目标 baseUrl", 0);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        String full = baseUrl.replaceAll("/+$", "") + path;
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(full);
        Map<String, String> params = readStringMap(c.getParamsJson());
        if (params != null) {
            params.forEach(builder::queryParam);
        }
        URI uri = builder.build(true).toUri();
        String reqSum = method + " " + uri;
        long start = System.currentTimeMillis();
        try {
            var spec = RestClient.create().method(HttpMethod.valueOf(method)).uri(uri);
            Map<String, String> headers = readStringMap(c.getHeadersJson());
            if (headers != null) {
                headers.forEach(spec::header);
            }
            if (hasBody(method)) {
                spec.contentType(MediaType.APPLICATION_JSON).body(c.getBodyJson());
            }
            HttpStatusCode status;
            String body;
            try {
                ResponseEntity<String> resp = spec.retrieve().toEntity(String.class);
                status = resp.getStatusCode();
                body = resp.getBody();
            } catch (HttpClientErrorException e) {
                status = e.getStatusCode();
                body = e.getResponseBodyAsString();
            }
            long dur = System.currentTimeMillis() - start;
            Map<String, Object> exp = readObjectMap(c.getExpectedJson());
            boolean ok = matchesExpected(exp, status.value(), body);
            String respSum = status.value() + (body == null || body.isBlank() ? "" : " " + truncate(body, 500));
            if (ok) {
                return new CaseOutcome("pass", reqSum, respSum, null, dur);
            }
            return new CaseOutcome("fail", reqSum, respSum,
                    "期望 " + describeExpected(exp) + "，实际 HTTP " + status.value(), dur);
        } catch (Exception e) {
            return new CaseOutcome("fail", reqSum, null, "请求异常: " + rootMessage(e),
                    System.currentTimeMillis() - start);
        }
    }

    /** health 用例：走 CAP-07 健康检查（command 型走 SSH 执行；http 型走健康检查适配器）。 */
    private CaseOutcome runHealth(TestCaseEntity c, Long serverId, String baseUrl) {
        if (serverId == null) {
            return new CaseOutcome("skip", c.getName(), null, "未指定目标服务器（health 用例）", 0);
        }
        Map<String, Object> exp = readObjectMap(c.getExpectedJson());
        long start = System.currentTimeMillis();
        try {
            HealthCheckConfig cfg;
            if ("command".equals(str(exp.get("type")))) {
                String cmd = str(exp.get("command"));
                if (cmd == null || cmd.isBlank()) {
                    return new CaseOutcome("skip", c.getName(), null, "health 用例缺 command", 0);
                }
                cfg = HealthCheckConfig.command(cmd);
            } else {
                String url = str(exp.get("url"));
                if ((url == null || url.isBlank()) && baseUrl != null) {
                    String p = c.getPath() == null ? "" : c.getPath();
                    url = baseUrl.replaceAll("/+$", "") + (p.startsWith("/") ? p : "/" + p);
                }
                if (url == null || url.isBlank()) {
                    return new CaseOutcome("skip", c.getName(), null, "health 用例缺 url/baseUrl", 0);
                }
                int st = exp.get("status") instanceof Number n ? n.intValue() : 200;
                cfg = HealthCheckConfig.http(url, st);
            }
            HealthResult r = serverOpService.healthCheck(serverId, cfg);
            long dur = System.currentTimeMillis() - start;
            if (r.ok()) {
                return new CaseOutcome("pass", c.getName(), r.message(), null, dur);
            }
            return new CaseOutcome("fail", c.getName(), r.message(), "健康检查未通过: " + r.message(), dur);
        } catch (Exception e) {
            return new CaseOutcome("fail", c.getName(), null, "健康检查异常: " + rootMessage(e),
                    System.currentTimeMillis() - start);
        }
    }

    // ---------------- 自动回归（FR-05） ----------------

    @EventListener
    public void onDeploymentCompleted(DeploymentCompletedEvent evt) {
        if (!evt.success()) {
            return;
        }
        try {
            ProjectView p = projectService.get(evt.projectId());
            if (p.autoRegressionOnDeploy() == null || !p.autoRegressionOnDeploy()) {
                return;
            }
            List<TestSuiteEntity> suites = suiteRepo.findByProjectIdOrderByCreatedAtAsc(evt.projectId());
            if (suites.isEmpty()) {
                log.info("自动回归跳过：项目 {} 无测试套件", evt.projectId());
                return;
            }
            List<Long> ids = suites.stream().map(TestSuiteEntity::getId).toList();
            // P0-6：继承部署的需求关联，回归结果挂到同一主线
            String requirementId = deploymentRepo.findById(evt.deploymentId())
                    .map(d -> d.getRequirementId()).orElse(null);
            log.info("部署 #{} 成功，自动回归触发（项目 {}，套件 {}）", evt.deploymentId(), evt.projectId(), ids);
            createInternal(evt.projectId(), requirementId, ids, evt.deploymentId(), evt.serverId(), null, "deploy");
        } catch (Exception e) {
            log.warn("自动回归触发失败: {}", e.getMessage());
        }
    }

    // ---------------- 查询 / 报告 / 缺陷线索 ----------------

    public TestRunEntity require(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "测试运行不存在: " + id));
    }

    public TestRunView get(Long id) {
        return toView(require(id));
    }

    public List<TestRunView> history(String projectId, String status) {
        List<TestRunEntity> list = (status == null || status.isBlank())
                ? repo.findByProjectIdOrderByCreatedAtDesc(projectId)
                : repo.findByProjectIdAndStatusOrderByCreatedAtDesc(projectId, status.trim().toUpperCase());
        return list.stream().map(this::toView).toList();
    }

    public String report(Long id) {
        TestRunEntity r = require(id);
        return renderReport(r, resultRepo.findByRunIdOrderBySortAsc(id));
    }

    public String logs(Long id) {
        TestRunEntity r = require(id);
        return r.getLogsText() == null ? "" : r.getLogsText();
    }

    /** FR-06 失败转缺陷线索。 */
    public List<IssueDraftView> issues(Long id) {
        require(id);
        List<IssueDraftView> out = new ArrayList<>();
        for (TestCaseResultEntity re : resultRepo.findByRunIdOrderBySortAsc(id)) {
            if (!"fail".equals(re.getStatus())) {
                continue;
            }
            String expected = "—";
            if (re.getCaseId() != null) {
                TestCaseEntity c = caseRepo.findById(re.getCaseId()).orElse(null);
                if (c != null && c.getExpectedJson() != null) {
                    expected = c.getExpectedJson();
                }
            }
            out.add(new IssueDraftView(id, re.getCaseId(), "测试失败: " + re.getName(),
                    re.getRequestSummary(), expected,
                    re.getError() != null && !re.getError().isBlank() ? re.getError() : re.getResponseSummary(),
                    re.getStatus()));
        }
        return out;
    }

    @Transactional
    public void delete(Long id) {
        TestRunEntity r = require(id);
        if (TestRunEntity.RUNNING.equals(r.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "测试运行中不可删除");
        }
        resultRepo.deleteByRunId(id);
        repo.delete(r);
    }

    // ---------------- 视图 ----------------

    public TestRunView toView(TestRunEntity r) {
        List<Long> suiteIds = parseIds(r.getSuiteIdsJson());
        RunSummary summary = parseSummary(r.getSummaryJson());
        List<CaseResultView> results = resultRepo.findByRunIdOrderBySortAsc(r.getId()).stream()
                .map(this::toResultView).toList();
        return new TestRunView(r.getId(), r.getProjectId(), r.getRequirementId(), suiteIds, r.getDeploymentId(),
                r.getServerId(), r.getBaseUrl(), r.getStatus(), summary, r.getReportDocId(), r.getErrorSummary(),
                r.getTriggeredBy(), r.getStartedAt(), r.getFinishedAt(), r.getCreatedAt(), results);
    }

    // ---------------- 内部 ----------------

    private String resolveBaseUrl(String explicit, Long serverId, Long deploymentId) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip();
        }
        Long sid = serverId != null ? serverId : (deploymentId != null
                ? deploymentRepo.findById(deploymentId).map(e -> e.getServerId()).orElse(null) : null);
        return sid == null ? null : serverBaseUrl(sid);
    }

    private String serverBaseUrl(Long serverId) {
        ProjectServerEntity s = serverRepo.findById(serverId).orElse(null);
        if (s == null || s.getAccessConfig() == null || s.getAccessConfig().isBlank()) {
            return null;
        }
        try {
            String cfg = s.getAccessConfig();
            if (crypto != null && crypto.isEncrypted(cfg)) {
                cfg = crypto.decryptConfigJson(cfg);
            }
            JsonNode node = mapper.readTree(cfg);
            JsonNode u = node.get("baseUrl");
            return u == null || u.isNull() || u.asText().isBlank() ? null : u.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean matchesExpected(Map<String, Object> exp, int status, String body) {
        if (exp == null || exp.isEmpty()) {
            return status >= 200 && status < 300;
        }
        Object st = exp.get("status");
        if (st != null && !statusMatches(st, status)) {
            return false;
        }
        Object contains = exp.get("contains");
        if (contains != null && contains.toString().length() > 0) {
            if (body == null || !body.contains(contains.toString())) {
                return false;
            }
        }
        return true;
    }

    private boolean statusMatches(Object expected, int actual) {
        if (expected instanceof Number n) {
            return n.intValue() == actual;
        }
        String s = String.valueOf(expected).trim();
        if (s.endsWith("XX") && s.length() == 3) {
            return actual / 100 == s.charAt(0) - '0';
        }
        try {
            return Integer.parseInt(s) == actual;
        } catch (NumberFormatException e) {
            return actual >= 200 && actual < 300;
        }
    }

    private String describeExpected(Map<String, Object> exp) {
        if (exp == null || exp.isEmpty()) {
            return "HTTP 2xx";
        }
        StringBuilder sb = new StringBuilder();
        if (exp.get("status") != null) {
            sb.append("状态 ").append(exp.get("status"));
        }
        if (exp.get("contains") != null) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append("含 \"").append(exp.get("contains")).append("\"");
        }
        return sb.length() == 0 ? "HTTP 2xx" : sb.toString();
    }

    private boolean hasBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private Long createReportDoc(TestRunEntity r) {
        String md = renderReport(r, resultRepo.findByRunIdOrderBySortAsc(r.getId()));
        DocDetail doc = documentService.create(new DocRequest("report", null, r.getProjectId(),
                "测试报告 #" + r.getId() + " " + LocalDateTime.now().toString().replace("T", " ").substring(0, 16),
                List.of("test-report"), null, md));
        return doc.id();
    }

    private String renderReport(TestRunEntity r, List<TestCaseResultEntity> results) {
        RunSummary s = parseSummary(r.getSummaryJson());
        StringBuilder md = new StringBuilder();
        md.append("# 测试报告 #").append(r.getId()).append("\n\n");
        md.append("- 项目: `").append(r.getProjectId()).append("`\n");
        md.append("- 触发: ").append(r.getTriggeredBy() == null ? "user" : r.getTriggeredBy()).append("\n");
        md.append("- 状态: **").append(r.getStatus()).append("**\n");
        md.append("- 结果: ").append(s.total()).append(" 用例 / ").append(s.passed()).append(" 通过 / ")
                .append(s.failed()).append(" 失败 / ").append(s.skipped()).append(" 跳过\n");
        md.append("- 目标: ").append(r.getBaseUrl() == null || r.getBaseUrl().isBlank() ? "(服务器)" : "`" + r.getBaseUrl() + "`").append("\n");
        if (r.getDeploymentId() != null) {
            md.append("- 关联部署: #").append(r.getDeploymentId()).append("\n");
        }
        md.append("\n## 用例结果\n\n");
        for (TestCaseResultEntity re : results) {
            String icon = switch (re.getStatus()) {
                case "pass" -> "✅";
                case "skip" -> "⏭";
                default -> "❌";
            };
            md.append("### ").append(icon).append(" ").append(re.getName()).append(" (")
                    .append(re.getDuration() == null ? "?" : re.getDuration()).append("ms)\n\n");
            if (re.getRequestSummary() != null && !re.getRequestSummary().isBlank()) {
                md.append("- 请求: `").append(re.getRequestSummary()).append("`\n");
            }
            if (re.getResponseSummary() != null && !re.getResponseSummary().isBlank()) {
                md.append("- 响应: `").append(re.getResponseSummary()).append("`\n");
            }
            if (re.getError() != null && !re.getError().isBlank()) {
                md.append("- 错误: ").append(re.getError()).append("\n");
            }
            md.append("\n");
        }
        return md.toString();
    }

    private void notify(TestRunEntity r, NotificationLevel level, String title, String body) {
        try {
            notificationService.emit(new NotificationDraft(level, "test", title, body,
                    "test_run", String.valueOf(r.getId()), List.of()));
        } catch (Exception e) {
            log.warn("测试通知发送失败: {}", e.getMessage());
        }
    }

    /** 执行底座 WS topic：测试运行用 runId 字符串（与 /ws/test-runs/{id}/stream 对应） */
    private String topic(Long runId) {
        return String.valueOf(runId);
    }

    private CaseResultView toResultView(TestCaseResultEntity e) {
        return new CaseResultView(e.getId(), e.getCaseId(), e.getSuiteId(), e.getSort(), e.getName(),
                e.getStatus(), e.getRequestSummary(), e.getResponseSummary(), e.getError(), e.getDuration());
    }

    // ---------- JSON 序列化/解析 ----------

    private String writeIds(List<Long> ids) {
        try {
            return mapper.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<Long> parseIds(String json) {
        List<Long> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            for (JsonNode n : mapper.readTree(json)) {
                out.add(n.asLong());
            }
        } catch (Exception e) {
            // 忽略非法 JSON
        }
        return out;
    }

    private String writeSummary(int total, int passed, int failed, int skipped) {
        try {
            return mapper.writeValueAsString(Map.of("total", total, "passed", passed,
                    "failed", failed, "skipped", skipped));
        } catch (Exception e) {
            return "{}";
        }
    }

    private RunSummary parseSummary(String json) {
        if (json == null || json.isBlank()) {
            return new RunSummary(0, 0, 0, 0);
        }
        try {
            JsonNode n = mapper.readTree(json);
            return new RunSummary(n.path("total").asInt(0), n.path("passed").asInt(0),
                    n.path("failed").asInt(0), n.path("skipped").asInt(0));
        } catch (Exception e) {
            return new RunSummary(0, 0, 0, 0);
        }
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
    private Map<String, String> readStringMap(String json) {
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

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    /** 用例执行结果（内部）。 */
    private record CaseOutcome(String status, String requestSummary, String responseSummary,
                               String error, long duration) {
    }
}
