package com.devmind.test.service;

import com.devmind.auth.IdentityService;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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
import com.devmind.execution.model.StepResult;
import com.devmind.execution.model.StepSpec;
import com.devmind.execution.runner.AgentNodeRouter;
import com.devmind.execution.runner.AgentNodeStepRunner;
import com.devmind.notification.dto.NotificationDraft;
import com.devmind.notification.model.NotificationLevel;
import com.devmind.notification.service.NotificationService;
import com.devmind.project.ProjectService;
import com.devmind.project.EnvironmentService;
import com.devmind.project.WorkItemService;
import com.devmind.project.dto.ProjectView;
import com.devmind.project.model.EnvironmentEntity;
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
 * CAP-10 测试执行：创建并异步执行 test_run（http 用例直请求 baseUrl 匹配 expected；health 用例——http 型
 * 服务端直接探测，command 型经 CAP-36 exec 帧下发 runner 节点执行）→ 用例级结果落库 + WS 实时 →
 * 汇总 + 报告文档（FR-04）→ 失败转缺陷线索（FR-06）。
 * 监听部署完成事件按项目 autoRegressionOnDeploy 自动回归（FR-05）。
 * 关键陷阱同构建/部署：create() 不加 @Transactional，save 自身事务即时提交后异步 run()。
 */
@Service
public class TestRunService {

    private static final Logger log = LoggerFactory.getLogger(TestRunService.class);

    private final IdentityService identityService;
    private final ExecutorService testExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final TestRunRepository repo;
    private final TestCaseResultRepository resultRepo;
    private final TestCaseRepository caseRepo;
    private final TestSuiteRepository suiteRepo;
    private final ProjectService projectService;
    private final WorkItemService workItemService;
    private final DeploymentRepository deploymentRepo;
    private final AgentNodeRouter agentNodeRouter;
    private final AgentNodeStepRunner agentNodeRunner;
    private final DocumentService documentService;
    private final NotificationService notificationService;
    private final ExecutionLogHub hub;
    private final ObjectMapper mapper;
    private final EnvironmentService environmentService;

    public TestRunService(TestRunRepository repo,
                          TestCaseResultRepository resultRepo,
                          TestCaseRepository caseRepo,
                          TestSuiteRepository suiteRepo,
                          ProjectService projectService,
                          WorkItemService workItemService,
                          DeploymentRepository deploymentRepo,
                          AgentNodeRouter agentNodeRouter,
                          AgentNodeStepRunner agentNodeRunner,
                          DocumentService documentService,
                          NotificationService notificationService,
                          ExecutionLogHub hub,
                          ObjectMapper mapper,
                          EnvironmentService environmentService,
                           IdentityService identityService) {
        this.identityService = identityService;
        this.repo = repo;
        this.resultRepo = resultRepo;
        this.caseRepo = caseRepo;
        this.suiteRepo = suiteRepo;
        this.projectService = projectService;
        this.workItemService = workItemService;
        this.deploymentRepo = deploymentRepo;
        this.agentNodeRouter = agentNodeRouter;
        this.agentNodeRunner = agentNodeRunner;
        this.documentService = documentService;
        this.notificationService = notificationService;
        this.hub = hub;
        this.mapper = mapper;
        this.environmentService = environmentService;
    }

    @PreDestroy
    public void shutdown() {
        testExecutor.shutdownNow();
    }

    // ---------------- 创建 / 执行 ----------------

    public TestRunView create(CreateTestRunRequest req) {
        return createInternal(req.projectId(), req.workItemId(), req.suiteIds(), req.deploymentId(),
                req.agentNodeId(), req.environmentId(), req.baseUrl(), identityService.currentActor());
    }

    private TestRunView createInternal(String projectId, String workItemId, List<Long> suiteIds,
                                       Long deploymentId, String agentNodeId, Long environmentId,
                                       String baseUrl, String triggeredBy) {
        projectService.requireProject(projectId);
        if (workItemId != null && !workItemId.isBlank()) {
            // P0-6 关联约定：任务须属于该项目
            workItemService.requireEntity(projectId, workItemId);
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
        // 环境补全（CAP-36）：缺省 agentNodeId 取环境首个节点，缺省 baseUrl 取环境变量 baseUrl/BASE_URL
        String envBaseUrl = null;
        if (environmentId != null) {
            EnvironmentEntity environment = environmentService.requireEnvironment(projectId, environmentId);
            List<String> envNodes = environmentService.nodeIdsOf(environment);
            if ((agentNodeId == null || agentNodeId.isBlank()) && !envNodes.isEmpty()) {
                agentNodeId = envNodes.get(0);
            }
            Map<String, String> vars = environmentService.variablesOf(environment);
            envBaseUrl = vars.getOrDefault("baseUrl", vars.get("BASE_URL"));
        }
        String resolvedBaseUrl = resolveBaseUrl(baseUrl, deploymentId);
        if ((resolvedBaseUrl == null || resolvedBaseUrl.isBlank()) && envBaseUrl != null && !envBaseUrl.isBlank()) {
            resolvedBaseUrl = envBaseUrl.strip();
        }

        TestRunEntity r = new TestRunEntity();
        r.setProjectId(projectId);
        r.setWorkItemId(workItemId == null || workItemId.isBlank() ? null : workItemId);
        r.setSuiteIdsJson(writeIds(suiteIds));
        r.setDeploymentId(deploymentId);
        r.setAgentNodeId(agentNodeId == null || agentNodeId.isBlank() ? null : agentNodeId.trim());
        r.setEnvironmentId(environmentId);
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
                            ? runHealth(c, r)
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

    /**
     * health 用例（CAP-36）：command 型经 exec 帧下发 runner 节点执行（exit 0 = 通过；
     * 缺省节点走路由链：项目默认 > 平台默认）；http 型由服务端直接探测 URL 状态码。
     */
    private CaseOutcome runHealth(TestCaseEntity c, TestRunEntity run) {
        Map<String, Object> exp = readObjectMap(c.getExpectedJson());
        long start = System.currentTimeMillis();
        try {
            if ("command".equals(str(exp.get("type")))) {
                String cmd = str(exp.get("command"));
                if (cmd == null || cmd.isBlank()) {
                    return new CaseOutcome("skip", c.getName(), null, "health 用例缺 command", 0);
                }
                String nodeId = run.getAgentNodeId();
                if (nodeId == null || nodeId.isBlank()) {
                    nodeId = agentNodeRouter.route(null,
                            projectService.requireProject(run.getProjectId()).agentNodeId(), null);
                }
                StringBuilder buf = new StringBuilder();
                Consumer<String> sink = line -> {
                    if (buf.length() < 2000) {
                        buf.append(line).append('\n');
                    }
                };
                StepResult res = agentNodeRunner.runStep(nodeId, run.getProjectId(), "health-" + run.getId(),
                        c.getSort() == null ? 0 : c.getSort(),
                        new StepSpec(c.getName() == null || c.getName().isBlank() ? "health" : c.getName(),
                                cmd, null, "test"),
                        Map.of(), null, sink);
                long dur = System.currentTimeMillis() - start;
                String out = truncate(buf.toString().strip(), 500);
                if (res.ok()) {
                    return new CaseOutcome("pass", c.getName(), out.isBlank() ? "exit=0" : out, null, dur);
                }
                String err = res.error() == null || res.error().isBlank() ? "exit=" + res.exitCode() : res.error();
                return new CaseOutcome("fail", c.getName(), out, "健康检查未通过: " + err, dur);
            }
            String url = str(exp.get("url"));
            if ((url == null || url.isBlank()) && run.getBaseUrl() != null) {
                String p = c.getPath() == null ? "" : c.getPath();
                url = run.getBaseUrl().replaceAll("/+$", "") + (p.startsWith("/") ? p : "/" + p);
            }
            if (url == null || url.isBlank()) {
                return new CaseOutcome("skip", c.getName(), null, "health 用例缺 url/baseUrl", 0);
            }
            int expected = exp.get("status") instanceof Number n ? n.intValue() : 200;
            int status;
            try {
                status = RestClient.create().get().uri(url).retrieve().toBodilessEntity().getStatusCode().value();
            } catch (HttpClientErrorException e) {
                status = e.getStatusCode().value();
            }
            long dur = System.currentTimeMillis() - start;
            String sum = "HTTP " + status + "（期望 " + expected + "）";
            if (status == expected) {
                return new CaseOutcome("pass", c.getName(), sum, null, dur);
            }
            return new CaseOutcome("fail", c.getName(), sum, "健康检查未通过: " + sum, dur);
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
            // P0-6：继承部署的任务关联，回归结果挂到同一主线
            String workItemId = deploymentRepo.findById(evt.deploymentId())
                    .map(d -> d.getWorkItemId()).orElse(null);
            log.info("部署 #{} 成功，自动回归触发（项目 {}，套件 {}）", evt.deploymentId(), evt.projectId(), ids);
            createInternal(evt.projectId(), workItemId, ids, evt.deploymentId(), evt.agentNodeId(), null, null,
                    "deploy");
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
        return new TestRunView(r.getId(), r.getProjectId(), r.getWorkItemId(), suiteIds, r.getDeploymentId(),
                r.getAgentNodeId(), r.getEnvironmentId(), r.getBaseUrl(), r.getStatus(), summary, r.getReportDocId(),
                r.getErrorSummary(),
                r.getTriggeredBy(), r.getStartedAt(), r.getFinishedAt(), r.getCreatedAt(), results);
    }

    // ---------------- 内部 ----------------

    /** baseUrl 解析（CAP-36）：显式 > 关联部署的环境变量 baseUrl/BASE_URL（servers 表已下线）。 */
    private String resolveBaseUrl(String explicit, Long deploymentId) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip();
        }
        if (deploymentId == null) {
            return null;
        }
        var d = deploymentRepo.findById(deploymentId).orElse(null);
        if (d == null || d.getEnvironmentId() == null) {
            return null;
        }
        try {
            Map<String, String> vars = environmentService.variablesOf(
                    environmentService.requireEnvironment(d.getProjectId(), d.getEnvironmentId()));
            String v = vars.getOrDefault("baseUrl", vars.get("BASE_URL"));
            return v == null || v.isBlank() ? null : v.strip();
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
        DocDetail doc = documentService.create(new DocRequest("report", null, r.getWorkItemId(), r.getProjectId(),
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
        md.append("- 目标: ").append(r.getBaseUrl() == null || r.getBaseUrl().isBlank() ? "(节点)" : "`" + r.getBaseUrl() + "`").append("\n");
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
