package com.devmind.test.service;

import com.devmind.auth.IdentityService;
import java.net.URI;
import java.time.Duration;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.audit.AuditService;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.program.ProgramContract;
import com.devmind.common.program.ProgramManifest;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.deploy.event.DeploymentCompletedEvent;
import com.devmind.deploy.model.DeploymentEntity;
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
import com.devmind.project.dto.WorkItemBrief;
import com.devmind.project.model.EnvironmentEntity;
import com.devmind.test.dto.CaseResultView;
import com.devmind.test.dto.CaseRunRequest;
import com.devmind.test.dto.CreateTestRunRequest;
import com.devmind.test.dto.IssueDraftView;
import com.devmind.test.dto.ManualReviewRequest;
import com.devmind.test.dto.PlanChildRunSpec;
import com.devmind.test.dto.PrecheckReport;
import com.devmind.test.dto.ProgramRunView;
import com.devmind.test.dto.RunExecutionSnapshot;
import com.devmind.test.dto.RunSummary;
import com.devmind.test.dto.ScriptSuiteEnv;
import com.devmind.test.dto.ScriptSuiteRunRequest;
import com.devmind.test.dto.TestEvidenceView;
import com.devmind.test.dto.TestRunView;
import com.devmind.test.executor.CaseExecInput;
import com.devmind.test.executor.CaseExecOutcome;
import com.devmind.test.executor.CaseExecutionService;
import com.devmind.test.executor.ProgramOutcome;
import com.devmind.test.model.TestCaseEntity;
import com.devmind.test.model.TestCaseResultEntity;
import com.devmind.test.model.TestCaseVersionEntity;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.model.TestSuiteEntity;
import com.devmind.test.repo.TestCaseRepository;
import com.devmind.test.repo.TestCaseResultRepository;
import com.devmind.test.repo.TestRunRepository;
import com.devmind.test.repo.TestSuiteRepository;
import com.devmind.test.spec.AssertionResult;
import com.devmind.test.spec.CaseSpec;
import com.devmind.execution.ws.ExecutionLogHub;

import java.util.stream.Collectors;

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
    private final DomainEventPublisher eventPublisher;
    private final CaseExecutionService caseExecutionService;
    private final EvidenceService evidenceService;
    private final EnvironmentPrecheckService precheckService;
    private final AuditService auditService;
    /** 僵尸运行阈值（devmind.test.stale-timeout）：QUEUED/RUNNING 超此时长无终态 → 收割置 FAILED */
    private final Duration staleTimeout;

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
                           IdentityService identityService,
                           DomainEventPublisher eventPublisher,
                           CaseExecutionService caseExecutionService,
                           EvidenceService evidenceService,
                           EnvironmentPrecheckService precheckService,
                           AuditService auditService,
                           @Value("${devmind.test.stale-timeout:PT2H}") Duration staleTimeout) {
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
        this.eventPublisher = eventPublisher;
        this.caseExecutionService = caseExecutionService;
        this.evidenceService = evidenceService;
        this.precheckService = precheckService;
        this.auditService = auditService;
        this.staleTimeout = staleTimeout;
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

    /**
     * CAP-73：编排线程以显式触发人身份创建（异步线程无 SecurityContext，
     * {@link IdentityService#currentActor()} 会错误回落 local）。
     */
    public TestRunView createAs(CreateTestRunRequest req, String triggeredBy) {
        return createInternal(req.projectId(), req.workItemId(), req.suiteIds(), req.deploymentId(),
                req.agentNodeId(), req.environmentId(), req.baseUrl(), triggeredBy);
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
            if ("script".equals(s.getKind())) {
                // CAP-69：脚本套件自带 git 源与命令，不走用例编排执行；误混入会导致空跑 SUCCESS 假象
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "套件 " + sid + " 是脚本套件，请经 /api/script-suites/{id}/run 触发");
            }
            ensureRunnable(s);
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
        r.setRunScope(TestRunEntity.SCOPE_SUITE);
        Instant now = Instant.now();
        r.setStartedAt(now);
        r.setCreatedAt(now);
        TestRunEntity saved = repo.save(r);
        testExecutor.submit(() -> run(saved.getId()));
        auditService.record("test", "run.create", triggeredBy, projectId, true,
                "运行 #" + saved.getId() + " suites=" + suiteIds);
        return toView(saved);
    }

    // ---------------- CAP-104 单用例运行 ----------------

    /**
     * 单用例运行（runScope=CASE）：仍生成完整 test_run（结果/证据/报告/通知一致），
     * 只是执行集合收敛为一个用例；环境预检与清理语义同套件运行。
     */
    public TestRunView createCaseRun(Long caseId, CaseRunRequest req) {
        CaseRunRequest request = req == null ? new CaseRunRequest(null, null, null, null, null, null) : req;
        TestCaseEntity c = caseRepo.findById(caseId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "用例不存在: " + caseId));
        TestSuiteEntity s = suiteRepo.findById(c.getSuiteId())
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "测试套件不存在: " + c.getSuiteId()));
        String projectId = s.getProjectId();
        if (projectId == null || projectId.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "脚本套件不支持单用例运行");
        }
        if (request.projectId() != null && !request.projectId().isBlank()
                && !request.projectId().strip().equals(projectId)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "用例不属于项目 " + request.projectId());
        }
        projectService.requireProject(projectId);
        if (request.workItemId() != null && !request.workItemId().isBlank()) {
            workItemService.requireEntity(projectId, request.workItemId());
        }
        ensureRunnable(s);
        ensureCaseRunnable(c);

        String agentNodeId = request.agentNodeId();
        String envBaseUrl = null;
        if (request.environmentId() != null) {
            EnvironmentEntity environment = environmentService.requireEnvironment(projectId, request.environmentId());
            List<String> envNodes = environmentService.nodeIdsOf(environment);
            if ((agentNodeId == null || agentNodeId.isBlank()) && !envNodes.isEmpty()) {
                agentNodeId = envNodes.get(0);
            }
            Map<String, String> vars = environmentService.variablesOf(environment);
            envBaseUrl = vars.getOrDefault("baseUrl", vars.get("BASE_URL"));
        }
        String baseUrl = request.baseUrl() != null && !request.baseUrl().isBlank()
                ? request.baseUrl().strip() : envBaseUrl;

        TestRunEntity r = new TestRunEntity();
        r.setProjectId(projectId);
        r.setWorkItemId(request.workItemId() == null || request.workItemId().isBlank()
                ? null : request.workItemId());
        r.setSuiteIdsJson(writeIds(List.of(s.getId())));
        r.setCaseId(caseId);
        r.setRunScope(TestRunEntity.SCOPE_CASE);
        r.setAgentNodeId(agentNodeId == null || agentNodeId.isBlank() ? null : agentNodeId.strip());
        r.setEnvironmentId(request.environmentId());
        r.setBaseUrl(baseUrl == null || baseUrl.isBlank() ? null : baseUrl.strip());
        r.setParametersJson(request.parameters() == null ? null : writeMap(request.parameters()));
        r.setStatus(TestRunEntity.RUNNING);
        r.setTriggeredBy(identityService.currentActor());
        Instant now = Instant.now();
        r.setStartedAt(now);
        r.setCreatedAt(now);
        TestRunEntity saved = repo.save(r);
        testExecutor.submit(() -> run(saved.getId()));
        auditService.record("test", "run.create.case", saved.getTriggeredBy(), projectId, true,
                "运行 #" + saved.getId() + " case=" + caseId);
        return toView(saved);
    }

    /**
     * CAP-104 M2 方案子运行：同步执行到终态后返回（并发/重试/失败策略由方案编排器控制）。
     * 用例来自方案快照，运行期不再读实时用例；报告/通知/完成事件由父方案运行统一发。
     */
    public TestRunView createPlanChildRun(PlanChildRunSpec spec, String triggeredBy) {
        projectService.requireProject(spec.projectId());
        if (spec.environmentId() != null) {
            environmentService.requireEnvironment(spec.projectId(), spec.environmentId());
        }
        if (spec.workItemId() != null && !spec.workItemId().isBlank()) {
            workItemService.requireEntity(spec.projectId(), spec.workItemId());
        }
        if (spec.cases() == null || spec.cases().isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "方案项无可执行用例");
        }
        TestRunEntity r = new TestRunEntity();
        r.setProjectId(spec.projectId());
        r.setRequirementId(blankToNull(spec.requirementId()));
        r.setWorkItemId(blankToNull(spec.workItemId()));
        r.setSuiteIdsJson(writeIds(spec.suiteIds() == null ? List.of() : spec.suiteIds()));
        r.setCaseId(spec.caseId());
        r.setRunScope(spec.runScope() == null ? TestRunEntity.SCOPE_SUITE : spec.runScope());
        r.setAgentNodeId(blankToNull(spec.agentNodeId()));
        r.setEnvironmentId(spec.environmentId());
        r.setBaseUrl(blankToNull(spec.baseUrl()));
        r.setParametersJson(spec.parameters() == null || spec.parameters().isEmpty()
                ? null : writeMap(spec.parameters()));
        r.setPlanRunId(spec.planRunId());
        r.setPlanItemId(spec.planItemId());
        r.setAttempt(spec.attempt() == null ? 1 : spec.attempt());
        r.setShardKey(blankToNull(spec.shardKey()));
        r.setExecutionSnapshotJson(toJson(new RunExecutionSnapshot(Instant.now(), spec.cases())));
        r.setStatus(TestRunEntity.RUNNING);
        r.setTriggeredBy(triggeredBy);
        Instant now = Instant.now();
        r.setStartedAt(now);
        r.setCreatedAt(now);
        TestRunEntity saved = repo.save(r);
        try {
            run(saved.getId());
        } catch (Exception e) {
            TestRunEntity failed = repo.findById(saved.getId()).orElse(saved);
            if (TestRunEntity.RUNNING.equals(failed.getStatus()) || TestRunEntity.QUEUED.equals(failed.getStatus())) {
                failed.setStatus(TestRunEntity.FAILED);
                failed.setConclusion("FAIL");
                failed.setErrorSummary(truncate(rootMessage(e), 2000));
                failed.setFinishedAt(Instant.now());
                repo.save(failed);
                hub.done(topic(saved.getId()), failed.getStatus());
            }
        }
        return toView(repo.findById(saved.getId()).orElse(saved));
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    // ---------------- CAP-69 脚本套件运行 ----------------
    /** RunnerWorkspace.prepareBuild 要求非空 SAFE_ID 项目归属：脚本套件无项目，用固定伪 id */
    static final String SCRIPT_PSEUDO_PROJECT = "script-tests";

    /**
     * CAP-69 FR-02 触发脚本套件运行。路由链：显式 agentNodeId → 套件默认节点 →（绑了项目时）
     * 项目默认节点 → 平台默认 → 409；env/命令触发时覆盖仅本次生效，不落库。
     */
    public TestRunView createScriptRun(Long suiteId, ScriptSuiteRunRequest req) {
        TestSuiteEntity s = suiteRepo.findById(suiteId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "脚本套件不存在: " + suiteId));
        if (!"script".equals(s.getKind())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "套件 " + suiteId + " 不是脚本套件（kind=" + s.getKind() + "）");
        }
        ensureRunnable(s);
        String explicit = req != null && req.agentNodeId() != null && !req.agentNodeId().isBlank()
                ? req.agentNodeId().strip() : null;
        // route() 只有两档回退：套件默认优先；套件未配默认且绑了项目时，回退档让给项目默认节点
        String fallback = s.getAgentNodeId();
        if ((fallback == null || fallback.isBlank()) && s.getProjectId() != null) {
            fallback = projectService.requireProject(s.getProjectId()).agentNodeId();
        }
        String nodeId = agentNodeRouter.route(explicit, fallback, null);
        agentNodeRouter.requireExecCapable(nodeId);

        // env 合并：套件存值（secret 原值就在 envJson）+ 触发覆盖；null 键值剔除（Map.copyOf 禁 null）
        Map<String, String> env = new LinkedHashMap<>();
        for (ScriptSuiteEnv e : readScriptEnv(s.getEnvJson())) {
            if (e.key() != null && !e.key().isBlank() && e.value() != null) {
                env.put(e.key().strip(), e.value());
            }
        }
        if (req != null && req.env() != null) {
            req.env().forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null) {
                    env.put(k.strip(), v);
                }
            });
        }
        String command = req != null && req.command() != null && !req.command().isBlank()
                ? req.command() : s.getCommand();

        TestRunEntity r = new TestRunEntity();
        // 套件绑了项目 → run 归属该项目（进项目运行历史 + CAP-03 报告文档沉淀）；未绑保持 NULL
        r.setProjectId(s.getProjectId());
        r.setSuiteIdsJson(writeIds(List.of(suiteId)));
        r.setAgentNodeId(nodeId);
        r.setStatus(TestRunEntity.RUNNING);
        r.setTriggeredBy(identityService.currentActor());
        r.setRunScope(TestRunEntity.SCOPE_SCRIPT);
        Instant now = Instant.now();
        r.setStartedAt(now);
        r.setCreatedAt(now);
        TestRunEntity saved = repo.save(r);
        Map<String, String> finalEnv = Map.copyOf(env);
        String finalCommand = command;
        testExecutor.submit(() -> runScript(saved.getId(), finalEnv, finalCommand));
        auditService.record("test", "run.create.script", saved.getTriggeredBy(), s.getProjectId(), true,
                "脚本运行 #" + saved.getId() + " suite=" + suiteId);
        return toView(saved);
    }

    /**
     * CAP-69 FR-03/04 脚本执行：exec 帧整包下发 runner（repo 块 = 套件 git 源，workspaceKey 控工作区
     * 复用），JUnit XML 经 DEVMIND_JUNIT stdout marker 回收解析进 test_case_results（caseId=null）。
     * junit 缺失不炸：results 为空 + errorSummary 注记。收口：exit≠0 或用例 fail>0 → FAILED。
     */
    private void runScript(Long runId, Map<String, String> env, String command) {
        TestRunEntity r = repo.findById(runId).orElse(null);
        if (r == null) {
            return;
        }
        Long suiteId = parseIds(r.getSuiteIdsJson()).stream().findFirst().orElse(null);
        TestSuiteEntity s = suiteId == null ? null : suiteRepo.findById(suiteId).orElse(null);
        if (s == null) {
            r.setStatus(TestRunEntity.FAILED);
            r.setErrorSummary("脚本套件不存在（可能运行前被删除）: " + suiteId);
            r.setFinishedAt(Instant.now());
            repo.save(r);
            hub.done(topic(runId), r.getStatus());
            return;
        }
        String topic = topic(runId);
        // marker 行从人读日志剔除（载荷另有落点），人读行实时推 WS 供详情抽屉看进度
        ScriptMarkers.Tap tap = new ScriptMarkers.Tap(line -> hub.publishLog(topic, line));
        int total = 0, passed = 0, failed = 0, skipped = 0;
        boolean execFailed = false;
        String junitXml = null;
        try {
            String wsKey = s.getWorkspaceKey() != null && !s.getWorkspaceKey().isBlank()
                    ? s.getWorkspaceKey() : String.valueOf(s.getId());
            StepResult res = agentNodeRunner.runStep(r.getAgentNodeId(), SCRIPT_PSEUDO_PROJECT,
                    "stsuite-" + wsKey, 1,
                    new StepSpec("script:" + s.getName(), wrapScriptCommand(command, s.getJunitPath()),
                            s.getWorkSubdir(), "test"),
                    env, new AgentExecCommand.Repo(s.getRepoUrl(), s.getBranch(), s.getCommitHash(), null),
                    null, s.getTimeoutSec() == null ? 7200L : s.getTimeoutSec().longValue(), tap);

            var xml = tap.junitXml();
            junitXml = xml.orElse(null);
            if (xml.isPresent()) {
                try {
                    int sort = 1;
                    for (JUnitXmlParser.ParsedCase c : JUnitXmlParser.parse(xml.get())) {
                        TestCaseResultEntity re = new TestCaseResultEntity();
                        re.setRunId(runId);
                        re.setCaseId(null); // 脚本套件无 CAP-10 用例行，结果行即一等公民
                        re.setSuiteId(suiteId);
                        re.setSort(sort++);
                        re.setName(c.name());
                        re.setStatus(c.status());
                        re.setError(truncate(c.error(), 1000));
                        re.setDuration(c.durationMs());
                        re.setNodeId(r.getAgentNodeId());
                        re.setAttempt(1);
                        re.setEnvSnapshotJson(toJson(scriptSnapshot(s, r.getAgentNodeId())));
                        re.setCreatedAt(Instant.now());
                        resultRepo.save(re);
                        hub.publishEvent(topic, "result", toResultView(re));
                        total++;
                        switch (c.status()) {
                            case "pass" -> passed++;
                            case "fail" -> failed++;
                            default -> skipped++;
                        }
                    }
                } catch (DevMindException ex) {
                    // XML 畸形 = 无法验证结果，fail-visible 按 FAILED 收口
                    failed++;
                    r.setErrorSummary(truncate(ex.getMessage(), 2000));
                }
            } else {
                String note = tap.markerLines() == 0 || tap.payloadLines() == 0
                        ? "未回传 JUnit 报告（产物缺失或 junitPath 配置有误: " + s.getJunitPath() + "）"
                        : "JUnit 报告载荷解码失败（详见服务端 warn 日志）";
                log.warn("脚本套件运行 #{}: {}", runId, note);
                r.setErrorSummary(truncate(note, 2000));
            }
            if (!res.ok()) {
                execFailed = true;
                String err = res.error() == null || res.error().isBlank() ? "exit=" + res.exitCode() : res.error();
                r.setErrorSummary(truncate("脚本执行失败: " + err, 2000));
            }
        } catch (Exception e) {
            failed++;
            r.setErrorSummary(truncate(rootMessage(e), 2000));
            tap.accept("[运行异常] " + rootMessage(e));
        }

        boolean bad = failed > 0 || execFailed;
        r.setStatus(bad ? TestRunEntity.FAILED : TestRunEntity.SUCCESS);
        r.setConclusion(conclusion(total, passed, failed, skipped, 0, 0));
        r.setSummaryJson(writeSummary(total, passed, failed, skipped));
        r.setLogsText(tap.logText());
        r.setFinishedAt(Instant.now());
        repo.save(r);

        // CAP-104 证据链：控制台日志 / JUnit XML / 运行摘要（证据失败不影响运行收口）
        appendEvidenceQuiet(runId, null, r.getAgentNodeId(), "CONSOLE_LOG", "console.log", tap.logText());
        if (junitXml != null) {
            appendEvidenceQuiet(runId, null, r.getAgentNodeId(), "JUNIT_XML", "junit.xml", junitXml);
        }
        appendEvidenceQuiet(runId, null, r.getAgentNodeId(), "RESULT_JSON", "script-result.json",
                toJson(scriptRunSummary(r, s, total, passed, failed, skipped)));

        // 无项目的脚本 run 跳过 CAP-03 报告文档沉淀（report 端点仍可按 results 重渲染）
        if (r.getProjectId() != null) {
            try {
                Long docId = createReportDoc(r);
                r.setReportDocId(docId);
                repo.save(r);
            } catch (Exception e) {
                log.warn("测试报告文档创建失败: {}", e.getMessage());
            }
        }

        hub.done(topic, r.getStatus());
        notify(r, bad ? NotificationLevel.P1 : NotificationLevel.P2,
                "脚本测试" + (bad ? "失败" : "通过") + " #" + runId,
                passed + " 通过 / " + failed + " 失败 / " + skipped + " 跳过 · " + s.getName());
        publishCompleted(r);
    }

    /**
     * FR-03 命令包装：跑完把 junitPath 产物 gzip+base64 打成单行 DEVMIND_JUNIT marker 回传，
     * 退出码原样透传。尾段必须<b>贴在命令末行同一行</b>——runner execAllowlist 逐行校验首 token，
     * 独立行的 `ec=$?`/`p=$(gzip…)` 会被判为白名单外命令；贴行后行首仍是套件自己的命令
     * （本来就该在白名单里），`{`/`}`/echo/exit 属 shell 内置豁免。
     */
    static String wrapScriptCommand(String command, String junitPath) {
        return "{ " + command.strip()
                + "; ec=$?; p=$(gzip -c " + junitPath + " 2>/dev/null | base64 | tr -d '\\n'); echo \""
                + ScriptMarkers.JUNIT + " $p\"; exit $ec; }";
    }

    private List<ScriptSuiteEnv> readScriptEnv(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, ScriptSuiteEnv.class));
        } catch (Exception e) {
            return List.of();
        }
    }


    private void run(Long runId) {
        TestRunEntity r = repo.findById(runId).orElse(null);
        if (r == null) {
            return;
        }
        EnvironmentEntity environment = environmentOf(r);
        StringBuilder logs = new StringBuilder();

        // CAP-104 FR-06 环境预检：失败即 BLOCKED，不产生假失败。
        // 方案子运行跳过：方案触发时已预检，重复探测既慢又会把父运行已放行的事项再判一遍。
        if (environment != null && r.getPlanRunId() == null) {
            PrecheckReport precheck = precheckService.run(r.getProjectId(), environment.getId(), r.getAgentNodeId());
            appendEvidenceQuiet(runId, null, r.getAgentNodeId(), "QUEUE_LOG", "precheck.json", toJson(precheck));
            if (!precheck.passed()) {
                String reason = precheck.items().stream().filter(i -> !i.pass())
                        .map(i -> i.name() + ": " + i.message())
                        .collect(Collectors.joining("；"));
                blockRun(r, logs, "环境预检未通过: " + reason);
                return;
            }
        }

        Counters c = new Counters();
        int sort = 1;
        try {
            for (CaseExecTarget target : caseTargetsOf(r)) {
                TestCaseEntity caseEntity = target.entity();
                if (caseEntity.getSpecJson() != null && !caseEntity.getSpecJson().isBlank()) {
                    try {
                        c.add(runSpecCase(r, caseEntity, target.suiteCommit(), environment, sort++, logs));
                    } catch (Exception e) {
                        c.add(saveErrorResult(r, caseEntity, sort++, rootMessage(e), logs));
                        logs.append("[ERROR] ").append(caseEntity.getName()).append(" — ")
                                .append(rootMessage(e)).append('\n');
                    }
                } else {
                    CaseOutcome out = "health".equalsIgnoreCase(caseEntity.getKind())
                            ? runHealth(caseEntity, r)
                            : runHttp(caseEntity, r.getBaseUrl());
                    c.add(saveLegacyResult(r, caseEntity, out, sort++, logs));
                }
            }
            if (c.total == 0) {
                logs.append("[运行结束] 选中的套件无可执行用例（全部禁用或套件为空）\n");
            }
        } catch (Exception e) {
            c.error();
            logs.append("[运行异常] ").append(rootMessage(e)).append('\n');
            r.setErrorSummary(truncate(rootMessage(e), 2000));
        }

        // CAP-104 FR-06 环境清理：cleanup_json 配置了才执行，日志进证据链
        if (environment != null) {
            String cleanupNode = cleanupNode(r, environment);
            if (cleanupNode != null) {
                try {
                    EnvironmentPrecheckService.CleanupOutcome cleanup = precheckService.cleanup(
                            r.getProjectId(), environment, cleanupNode,
                            line -> logs.append("[cleanup] ").append(line).append('\n'));
                    if (cleanup != null) {
                        logs.append(cleanup.message()).append('\n');
                        appendEvidenceQuiet(runId, null, cleanupNode, "CLEANUP_LOG", "cleanup-env.log",
                                "command: " + cleanup.command() + "\n" + cleanup.output());
                    }
                } catch (Exception e) {
                    logs.append("[cleanup failed] ").append(rootMessage(e)).append('\n');
                }
            }
        }

        finishRun(r, logs, c);
    }

    /** CASE 作用域只跑指定用例；SUITE 作用域跑所有启用用例。 */
    private List<TestCaseEntity> casesOf(TestRunEntity r) {
        if (TestRunEntity.SCOPE_CASE.equals(r.getRunScope()) && r.getCaseId() != null) {
            TestCaseEntity c = caseRepo.findById(r.getCaseId()).orElse(null);
            return c == null ? List.of() : List.of(c);
        }
        List<TestCaseEntity> out = new ArrayList<>();
        for (Long sid : parseIds(r.getSuiteIdsJson())) {
            for (TestCaseEntity c : caseRepo.findBySuiteIdOrderBySortAsc(sid)) {
                if (Boolean.TRUE.equals(c.getEnabled())) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** 执行目标：快照运行用快照里的用例与套件 commit；普通运行用实时数据。 */
    private record CaseExecTarget(TestCaseEntity entity, String suiteCommit) {
    }

    private List<CaseExecTarget> caseTargetsOf(TestRunEntity r) {
        if (r.getExecutionSnapshotJson() != null && !r.getExecutionSnapshotJson().isBlank()) {
            try {
                RunExecutionSnapshot snap = mapper.readValue(r.getExecutionSnapshotJson(),
                        RunExecutionSnapshot.class);
                if (snap.cases() != null) {
                    return snap.cases().stream()
                            .map(c -> new CaseExecTarget(c.toEntity(), c.suiteCommit()))
                            .toList();
                }
            } catch (Exception e) {
                throw new DevMindException(ErrorCode.CONFLICT,
                        "运行用例快照损坏，无法按快照执行: run=" + r.getId() + " " + rootMessage(e));
            }
        }
        return casesOf(r).stream().map(c -> {
            TestSuiteEntity suite = suiteRepo.findById(c.getSuiteId()).orElse(null);
            return new CaseExecTarget(c, suite == null ? null : suite.getCommitHash());
        }).toList();
    }

    /** CAP-104 规范用例执行 + 结果落库 + 证据链。返回 pass/fail/blocked/skip/error。 */
    private String runSpecCase(TestRunEntity r, TestCaseEntity c, String suiteCommit,
                               EnvironmentEntity environment, int sort, StringBuilder logs) {
        CaseSpec spec = caseExecutionService.parseSpec(c.getSpecJson());
        CaseExecInput in = new CaseExecInput(r.getProjectId(), r.getId(), environment, r.getAgentNodeId(),
                r.getBaseUrl(), suiteCommit,
                readStringMap(r.getParametersJson()), c.getId(), c.getCaseKey());
        String title = spec.title() == null || spec.title().isBlank()
                ? (c.getName() == null ? c.getCaseKey() : c.getName()) : spec.title();
        CaseExecOutcome out = caseExecutionService.execute(title, spec, in);

        TestCaseResultEntity re = new TestCaseResultEntity();
        re.setRunId(r.getId());
        re.setCaseId(c.getId());
        re.setSuiteId(c.getSuiteId());
        re.setSort(sort);
        re.setName(truncate(title, 128));
        re.setStatus(out.status());
        re.setRequestSummary(truncate(out.requestSummary(), 1000));
        re.setResponseSummary(truncate(out.responseSummary(), 1000));
        re.setError(truncate(out.error(), 1000));
        re.setDuration(out.durationMs());
        re.setNodeId(out.nodeId());
        re.setAttempt(1);
        re.setFailureCategory(out.failureCategory());
        re.setAssertionJson(toJson(out.assertions()));
        re.setCommand(truncate(out.command(), 4000));
        re.setExitCode(out.exitCode());
        re.setEnvSnapshotJson(toJson(out.envSnapshot()));
        re.setWorkingDir(truncate(out.workingDir(), 512));
        re.setStartedAt(out.startedAt());
        re.setFinishedAt(out.finishedAt());
        re.setCreatedAt(Instant.now());
        applyProgramColumns(re, out.program());
        resultRepo.save(re);
        Long evidenceId = appendCaseEvidence(r.getId(), re, out);
        if (evidenceId != null) {
            re.setEvidenceId(evidenceId);
            resultRepo.save(re);
        }
        hub.publishEvent(topic(r.getId()), "result", toResultView(re));

        logLine(logs, out.status(), title, out.durationMs(), out.error());
        return out.status();
    }

    /** 程序用例结果列（M1.5）：清单/输入/输出摘要随结果落库，原始 JSON 与产物进证据链。 */
    private void applyProgramColumns(TestCaseResultEntity re, ProgramOutcome p) {
        if (p == null) {
            return;
        }
        re.setProgramAssetId(p.assetId());
        re.setProgramContractVersion(p.contractVersion());
        re.setProgramManifestJson(p.manifestJson());
        re.setProgramStatus(p.resultStatus());
        re.setProgramMessage(truncate(p.resultMessage(), 4000));
        if (p.metrics() != null && !p.metrics().isEmpty()) {
            re.setProgramMetricsJson(toJson(p.metrics()));
        }
        if (p.artifacts() != null && !p.artifacts().isEmpty()) {
            re.setProgramArtifactsJson(toJson(p.artifacts().stream()
                    .map(a -> Map.of("path", a.path(),
                            "sizeBytes", a.content() == null ? 0 : a.content().length,
                            "sha256", a.sha256() == null ? "" : a.sha256()))
                    .toList()));
        }
    }

    /** 旧 CAP-10 用例（无 spec）：沿用原执行器，结果同样进证据链。 */
    private String saveLegacyResult(TestRunEntity r, TestCaseEntity c, CaseOutcome out, int sort,
                                    StringBuilder logs) {
        Instant finished = Instant.now();
        TestCaseResultEntity re = new TestCaseResultEntity();
        re.setRunId(r.getId());
        re.setCaseId(c.getId());
        re.setSuiteId(c.getSuiteId());
        re.setSort(sort);
        re.setName(c.getName() == null ? (c.getMethod() + " " + c.getPath()) : c.getName());
        re.setStatus(out.status());
        re.setRequestSummary(truncate(out.requestSummary(), 1000));
        re.setResponseSummary(truncate(out.responseSummary(), 1000));
        re.setError(truncate(out.error(), 1000));
        re.setDuration(out.duration());
        re.setAttempt(1);
        re.setFailureCategory("fail".equals(out.status()) ? "ASSERTION" : null);
        re.setStartedAt(finished.minusMillis(Math.max(0, out.duration())));
        re.setFinishedAt(finished);
        re.setCreatedAt(finished);
        resultRepo.save(re);
        CaseExecOutcome synthetic = new CaseExecOutcome(out.status(),
                "fail".equals(out.status()) ? "ASSERTION" : null, List.of(),
                out.requestSummary(), out.responseSummary(), out.error(), out.duration(),
                null, null, null, "", "", null, re.getStartedAt(), re.getFinishedAt(), null, null);
        Long evidenceId = appendCaseEvidence(r.getId(), re, synthetic);
        if (evidenceId != null) {
            re.setEvidenceId(evidenceId);
            resultRepo.save(re);
        }
        hub.publishEvent(topic(r.getId()), "result", toResultView(re));
        logLine(logs, out.status(), re.getName(), out.duration(), out.error());
        return out.status();
    }

    private String saveErrorResult(TestRunEntity r, TestCaseEntity c, int sort, String message,
                                   StringBuilder logs) {
        Instant now = Instant.now();
        TestCaseResultEntity re = new TestCaseResultEntity();
        re.setRunId(r.getId());
        re.setCaseId(c.getId());
        re.setSuiteId(c.getSuiteId());
        re.setSort(sort);
        re.setName(truncate(c.getName() == null ? c.getCaseKey() : c.getName(), 128));
        re.setStatus("error");
        re.setError(truncate(message, 1000));
        re.setDuration(0L);
        re.setAttempt(1);
        re.setFailureCategory("UNKNOWN");
        re.setStartedAt(now);
        re.setFinishedAt(now);
        re.setCreatedAt(now);
        resultRepo.save(re);
        appendEvidenceQuiet(r.getId(), re.getId(), null, "RESULT_JSON", "case-" + re.getId() + ".json",
                toJson(Map.of("caseId", c.getId(), "status", "error", "error", message == null ? "" : message)));
        hub.publishEvent(topic(r.getId()), "result", toResultView(re));
        logLine(logs, "error", re.getName(), 0L, message);
        return "error";
    }

    private void logLine(StringBuilder logs, String status, String name, long duration, String error) {
        if (logs == null) {
            return;
        }
        logs.append('[').append(status == null ? "?" : status.toUpperCase()).append("] ")
                .append(name == null ? "-" : name).append(" · ").append(duration).append("ms");
        if (error != null && !error.isBlank()) {
            logs.append(" — ").append(error);
        }
        logs.append('\n');
    }

    private void blockRun(TestRunEntity r, StringBuilder logs, String reason) {
        logs.append("[BLOCKED] ").append(reason).append('\n');
        r.setStatus(TestRunEntity.BLOCKED);
        r.setConclusion("BLOCKED");
        r.setSummaryJson(writeSummary(0, 0, 0, 0, 0, 0));
        r.setErrorSummary(truncate(reason, 2000));
        r.setLogsText(logs.toString());
        r.setFinishedAt(Instant.now());
        repo.save(r);
        appendEvidenceQuiet(r.getId(), null, r.getAgentNodeId(), "RESULT_JSON", "run-summary.json",
                toJson(runSummary(r)));
        createReportDocQuiet(r);
        hub.done(topic(r.getId()), r.getStatus());
        notify(r, NotificationLevel.P1, "测试阻塞 #" + r.getId(), reason);
        publishCompleted(r);
    }

    private void finishRun(TestRunEntity r, StringBuilder logs, Counters c) {
        boolean bad = c.failed > 0 || c.errors > 0;
        r.setStatus(bad ? TestRunEntity.FAILED : c.blocked > 0 ? TestRunEntity.BLOCKED : TestRunEntity.SUCCESS);
        r.setConclusion(conclusion(c));
        r.setSummaryJson(writeSummary(c.total, c.passed, c.failed, c.skipped, c.blocked, c.errors));
        r.setLogsText(logs.toString());
        r.setFinishedAt(Instant.now());
        repo.save(r);
        appendEvidenceQuiet(r.getId(), null, r.getAgentNodeId(), "RESULT_JSON", "run-summary.json",
                toJson(runSummary(r)));
        createReportDocQuiet(r);
        hub.done(topic(r.getId()), r.getStatus());
        notify(r, bad ? NotificationLevel.P1 : NotificationLevel.P2,
                "测试" + (bad ? "失败" : TestRunEntity.BLOCKED.equals(r.getStatus()) ? "阻塞" : "通过")
                        + " #" + r.getId(),
                c.passed + " 通过 / " + c.failed + " 失败 / " + c.blocked + " 阻塞 / "
                        + c.errors + " 异常 / " + c.skipped + " 跳过"
                        + (r.getBaseUrl() == null ? "" : " · " + r.getBaseUrl()));
        publishCompleted(r);
    }

    private String conclusion(Counters c) {
        return conclusion(c.total, c.passed, c.failed, c.skipped, c.blocked, c.errors);
    }

    private String conclusion(int total, int passed, int failed, int skipped, int blocked, int errors) {
        if (total == 0) {
            return "BLOCKED";
        }
        if (failed == 0 && blocked == 0 && errors == 0) {
            return "PASS";
        }
        if (passed == 0 && failed == 0) {
            return "BLOCKED";
        }
        if (passed > 0) {
            return "PARTIAL";
        }
        return "FAIL";
    }

    private Map<String, Object> runSummary(TestRunEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", r.getId());
        m.put("projectId", r.getProjectId());
        m.put("status", r.getStatus());
        m.put("conclusion", r.getConclusion());
        m.put("summary", parseSummary(r.getSummaryJson()));
        m.put("baseUrl", r.getBaseUrl());
        m.put("environmentId", r.getEnvironmentId());
        m.put("nodeId", r.getAgentNodeId());
        m.put("errorSummary", r.getErrorSummary());
        m.put("finishedAt", r.getFinishedAt());
        return m;
    }

    private Map<String, Object> scriptRunSummary(TestRunEntity r, TestSuiteEntity s,
                                                 int total, int passed, int failed, int skipped) {
        Map<String, Object> m = runSummary(r);
        m.put("suite", s.getName());
        m.put("repoUrl", s.getRepoUrl());
        m.put("branch", s.getBranch());
        m.put("commit", s.getCommitHash() == null ? "HEAD" : s.getCommitHash());
        m.put("summary", Map.of("total", total, "passed", passed, "failed", failed, "skipped", skipped));
        return m;
    }

    private Map<String, Object> scriptSnapshot(TestSuiteEntity s, String nodeId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", nodeId);
        m.put("suite", s.getName());
        m.put("repoUrl", s.getRepoUrl());
        m.put("branch", s.getBranch());
        m.put("commit", s.getCommitHash() == null ? "HEAD" : s.getCommitHash());
        m.put("workspaceKey", s.getWorkspaceKey());
        m.put("junitPath", s.getJunitPath());
        m.put("project", SCRIPT_PSEUDO_PROJECT);
        m.put("executedAt", Instant.now().toString());
        return m;
    }

    /** 单用例证据：结果 JSON（断言差异/环境快照）+ stdout/stderr/清理日志，返回结果证据 id。 */
    private Long appendCaseEvidence(Long runId, TestCaseResultEntity re, CaseExecOutcome out) {
        if (out.program() != null) {
            return appendProgramEvidence(runId, re, out);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", re.getCaseId());
        payload.put("caseKey", null);
        payload.put("name", re.getName());
        payload.put("status", re.getStatus());
        payload.put("failureCategory", re.getFailureCategory());
        payload.put("requestSummary", re.getRequestSummary());
        payload.put("responseSummary", re.getResponseSummary());
        payload.put("error", re.getError());
        payload.put("command", out.command());
        payload.put("exitCode", out.exitCode());
        payload.put("assertions", out.assertions());
        payload.put("envSnapshot", out.envSnapshot());
        payload.put("stdout", cap(out.stdout(), 16 * 1024));
        payload.put("stderr", cap(out.stderr(), 16 * 1024));
        payload.put("cleanup", cap(out.cleanupLog(), 16 * 1024));
        payload.put("finishedAt", re.getFinishedAt());
        Long first = appendEvidenceQuiet(runId, re.getId(), out.nodeId(), "RESULT_JSON",
                "case-" + re.getId() + "-result.json", toJson(payload));
        if (out.stdout() != null && !out.stdout().isBlank()) {
            appendEvidenceQuiet(runId, re.getId(), out.nodeId(), "STDOUT",
                    "case-" + re.getId() + "-stdout.txt", cap(out.stdout(), 512 * 1024));
        }
        if (out.stderr() != null && !out.stderr().isBlank()) {
            appendEvidenceQuiet(runId, re.getId(), out.nodeId(), "STDERR",
                    "case-" + re.getId() + "-stderr.txt", cap(out.stderr(), 512 * 1024));
        }
        if (out.cleanupLog() != null && !out.cleanupLog().isBlank()) {
            appendEvidenceQuiet(runId, re.getId(), out.nodeId(), "CLEANUP_LOG",
                    "case-" + re.getId() + "-cleanup.log", cap(out.cleanupLog(), 256 * 1024));
        }
        return first;
    }

    /**
     * 程序型用例证据链：清单/输入/原始输出/stdout/stderr/每个产物/汇总。
     * 产物内容超出证据配置时逐条跳过并留日志，不影响其它证据入链。
     */
    private Long appendProgramEvidence(Long runId, TestCaseResultEntity re, CaseExecOutcome out) {
        ProgramOutcome p = out.program();
        Long first = appendEvidenceQuiet(runId, re.getId(), out.nodeId(),
                ProgramContract.EVIDENCE_MANIFEST, "case-" + re.getId() + "-program-manifest.json",
                cap(p.manifestJson(), 1_800_000));
        appendEvidenceQuiet(runId, re.getId(), out.nodeId(), ProgramContract.EVIDENCE_INPUT,
                "case-" + re.getId() + "-input.json", cap(p.inputJson(), 1_800_000));
        if (p.resultJson() != null && !p.resultJson().isBlank()) {
            appendEvidenceQuiet(runId, re.getId(), out.nodeId(), ProgramContract.EVIDENCE_RESULT,
                    "case-" + re.getId() + "-result.json", cap(p.resultJson(), 1_800_000));
        }
        if (out.stdout() != null && !out.stdout().isBlank()) {
            appendEvidenceQuiet(runId, re.getId(), out.nodeId(), ProgramContract.EVIDENCE_STDOUT,
                    "case-" + re.getId() + "-stdout.txt", cap(out.stdout(), 512 * 1024));
        }
        if (out.stderr() != null && !out.stderr().isBlank()) {
            appendEvidenceQuiet(runId, re.getId(), out.nodeId(), ProgramContract.EVIDENCE_STDERR,
                    "case-" + re.getId() + "-stderr.txt", cap(out.stderr(), 512 * 1024));
        }
        int index = 0;
        for (ProgramOutcome.ProgramArtifact a : p.artifacts()) {
            index++;
            appendEvidenceBytesQuiet(runId, re.getId(), out.nodeId(), ProgramContract.EVIDENCE_ARTIFACT,
                    "case-" + re.getId() + "-artifact-" + index + "-" + fileNameOf(a.path()),
                    a.content());
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseId", re.getCaseId());
        summary.put("status", re.getStatus());
        summary.put("failureCategory", re.getFailureCategory());
        summary.put("assetId", p.assetId());
        summary.put("assetSha256", p.assetSha256());
        summary.put("contractVersion", p.contractVersion());
        summary.put("programStatus", p.resultStatus());
        summary.put("programMessage", p.resultMessage());
        summary.put("metrics", p.metrics());
        summary.put("assertions", out.assertions());
        summary.put("exitCode", out.exitCode());
        summary.put("artifacts", p.artifacts().stream()
                .map(a -> Map.of("path", a.path(), "sizeBytes", a.content() == null ? 0 : a.content().length,
                        "sha256", a.sha256())).toList());
        summary.put("envSnapshot", out.envSnapshot());
        summary.put("cleanup", cap(out.cleanupLog(), 16 * 1024));
        summary.put("finishedAt", re.getFinishedAt());
        appendEvidenceQuiet(runId, re.getId(), out.nodeId(), ProgramContract.EVIDENCE_SUMMARY,
                "case-" + re.getId() + "-program-summary.json", toJson(summary));
        return first;
    }

    private String fileNameOf(String path) {
        String p = path == null ? "artifact" : path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }

    private Long appendEvidenceQuiet(Long runId, Long resultId, String nodeId, String kind,
                                     String filename, String content) {
        try {
            return evidenceService.appendText(runId, resultId, nodeId, kind, filename,
                    cap(content, 1_800_000)).getId();
        } catch (Exception e) {
            log.warn("测试证据写入跳过: run={} kind={} err={}", runId, kind, e.getMessage());
            return null;
        }
    }

    private Long appendEvidenceBytesQuiet(Long runId, Long resultId, String nodeId, String kind,
                                          String filename, byte[] content) {
        try {
            return evidenceService.append(runId, resultId, nodeId, kind, filename, content).getId();
        } catch (Exception e) {
            log.warn("测试证据写入跳过: run={} kind={} file={} err={}",
                    runId, kind, filename, e.getMessage());
            return null;
        }
    }

    private void createReportDocQuiet(TestRunEntity r) {
        if (r.getProjectId() == null || r.getPlanRunId() != null) {
            // 方案子运行的报告由父方案运行汇总，避免一次方案生成 N 份文档
            return;
        }
        try {
            Long docId = createReportDoc(r);
            r.setReportDocId(docId);
            repo.save(r);
        } catch (Exception e) {
            log.warn("测试报告文档创建失败: {}", e.getMessage());
        }
    }

    private EnvironmentEntity environmentOf(TestRunEntity r) {
        if (r.getEnvironmentId() == null || r.getProjectId() == null) {
            return null;
        }
        try {
            return environmentService.requireEnvironment(r.getProjectId(), r.getEnvironmentId());
        } catch (Exception e) {
            log.warn("测试运行目标环境不可用: run={} env={} err={}", r.getId(), r.getEnvironmentId(), e.getMessage());
            return null;
        }
    }

    private String cleanupNode(TestRunEntity r, EnvironmentEntity environment) {
        if (r.getAgentNodeId() != null && !r.getAgentNodeId().isBlank()) {
            return r.getAgentNodeId();
        }
        List<String> nodes = environmentService.nodeIdsOf(environment);
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    private void ensureRunnable(TestSuiteEntity s) {
        if (TestSuiteEntity.DEPRECATED.equals(s.getReviewStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "套件已废弃，不可运行: " + s.getId());
        }
        if (s.getSourceAssetId() != null && !TestSuiteEntity.APPROVED.equals(s.getReviewStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "套件 " + s.getId() + " 来源于资产 " + s.getSourceAssetId() + "，资产套件须评审通过后才可运行");
        }
    }

    private void ensureCaseRunnable(TestCaseEntity c) {
        if (TestCaseVersionEntity.DEPRECATED.equals(c.getReviewStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "用例已废弃，不可运行: " + c.getId());
        }
        if (c.getSourceAssetId() != null && !TestCaseVersionEntity.APPROVED.equals(c.getReviewStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "用例 " + c.getId() + " 引用的资产未评审通过，不可运行");
        }
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
            // CAP-69：脚本套件不进部署自动回归（自带 git 源/命令，非用例编排；要跑需显式触发）
            List<TestSuiteEntity> suites = suiteRepo.findByProjectIdOrderByCreatedAtAsc(evt.projectId())
                    .stream().filter(x -> !"script".equals(x.getKind())).toList();
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

    /**
     * 僵尸运行收割：QUEUED/RUNNING 超过阈值（默认 2h）无终态 → 置 FAILED + P0 通知。
     * 典型来源：runner 掉线/服务重启，脚本运行线程中断，记录永远停在 RUNNING。
     */
    @Scheduled(fixedDelayString = "${devmind.test.stale-reaper-delay-ms:60000}",
            initialDelayString = "${devmind.test.stale-reaper-initial-delay-ms:90000}")
    public void reapStale() {
        Instant before = Instant.now().minus(staleTimeout);
        List<TestRunEntity> stale = repo.findByStatusInAndCreatedAtBefore(
                List.of(TestRunEntity.QUEUED, TestRunEntity.RUNNING), before);
        for (TestRunEntity r : stale) {
            r.setStatus(TestRunEntity.FAILED);
            r.setErrorSummary("运行超时（" + staleTimeout.toMinutes() + " 分钟未出终态），系统判定僵尸并收割");
            r.setFinishedAt(Instant.now());
            repo.save(r);
            hub.done(topic(r.getId()), TestRunEntity.FAILED);
            log.warn("僵尸测试运行已收割: #{} projectId={} createdAt={}", r.getId(), r.getProjectId(), r.getCreatedAt());
            notificationService.emit(new NotificationDraft(NotificationLevel.P0, "TEST_RUN_STALE_REAPED",
                    "测试运行超时收割 #" + r.getId(),
                    "QUEUED/RUNNING 超过 " + staleTimeout.toMinutes() + " 分钟未出终态，已置 FAILED；请检查执行节点后重跑",
                    "TEST_RUN", String.valueOf(r.getId()), r.getProjectId(), List.of()));
            publishCompleted(r);
        }
    }

    public TestRunEntity require(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "测试运行不存在: " + id));
    }

    public TestRunView get(Long id) {
        TestRunEntity r = require(id);
        return toView(r, brief(r.getWorkItemId()));
    }

    public List<TestRunView> history(String projectId, String status) {
        List<TestRunEntity> list = (status == null || status.isBlank())
                ? repo.findByProjectIdAndPlanRunIdIsNullOrderByCreatedAtDesc(projectId)
                : repo.findByProjectIdAndStatusAndPlanRunIdIsNullOrderByCreatedAtDesc(
                        projectId, status.trim().toUpperCase());
        Map<String, WorkItemBrief> briefs = briefsOf(list.stream().map(TestRunEntity::getWorkItemId).toList());
        return list.stream().map(r -> toView(r, briefs.get(r.getWorkItemId()))).toList();
    }

    /** CAP-104 M2 父方案运行下的子运行（下钻用，按 id 升序 = 创建顺序）。 */
    public List<TestRunView> childrenOfPlanRun(Long planRunId) {
        return repo.findByPlanRunIdOrderByIdAsc(planRunId).stream().map(this::toView).toList();
    }

    public String report(Long id) {
        TestRunEntity r = require(id);
        return renderReport(r, resultRepo.findByRunIdOrderBySortAsc(id));
    }

    /** CAP-104 结构化报告（稳定契约，供基线与统计复用）。 */
    public Map<String, Object> reportJson(Long id) {
        TestRunEntity r = require(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run", runSummary(r));
        m.put("runScope", r.getRunScope());
        m.put("suiteIds", parseIds(r.getSuiteIdsJson()));
        m.put("caseId", r.getCaseId());
        m.put("workItemId", r.getWorkItemId());
        m.put("results", resultRepo.findByRunIdOrderBySortAsc(id).stream()
                .map(this::toResultView).toList());
        m.put("evidence", evidenceService.list(id));
        return m;
    }

    /**
     * CAP-104 FR-09 人工复核/人工判定：CONFIRMED/REJECTED 只记录复核意见、原始判定不变；
     * PASS/FAIL 用于 MANUAL 用例给出人工结论（改结果状态并重算运行汇总）。
     */
    @Transactional
    public CaseResultView manualReview(Long runId, Long resultId, ManualReviewRequest req) {
        TestRunEntity r = require(runId);
        TestCaseResultEntity re = resultRepo.findById(resultId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "用例结果不存在: " + resultId));
        if (!runId.equals(re.getRunId())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "结果不属于该运行: " + resultId);
        }
        if (TestRunEntity.RUNNING.equals(r.getStatus()) || TestRunEntity.QUEUED.equals(r.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "运行尚未结束，不能复核结果");
        }
        String review = req == null || req.review() == null ? "" : req.review().strip().toUpperCase();
        if (!List.of("CONFIRMED", "REJECTED", "PASS", "FAIL").contains(review)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "review 仅支持 CONFIRMED / REJECTED / PASS / FAIL");
        }
        String note = req.note() == null ? null : truncate(req.note().strip(), 1000);
        if ("PASS".equals(review) || "FAIL".equals(review)) {
            re.setStatus("PASS".equals(review) ? "pass" : "fail");
            if ("FAIL".equals(review)) {
                re.setFailureCategory("ASSERTION");
                if (note != null && !note.isBlank()) {
                    re.setError(note);
                }
            } else {
                re.setFailureCategory(null);
                re.setError(null);
            }
            re.setManualReview("CONFIRMED");
        } else {
            re.setManualReview(review);
        }
        re.setManualNote(note);
        resultRepo.save(re);
        appendEvidenceQuiet(r.getId(), re.getId(), re.getNodeId(), "MANUAL_REVIEW",
                "case-" + re.getId() + "-review.json",
                toJson(Map.of("resultId", re.getId(), "review", review,
                        "note", note == null ? "" : note, "status", re.getStatus(),
                        "reviewedBy", identityService.currentActor(),
                        "reviewedAt", Instant.now().toString())));
        recomputeRun(r);
        return toResultView(re);
    }

    /** 人工判定改变结果后重算运行汇总/结论（运行已终态，不重跑）。 */
    private void recomputeRun(TestRunEntity r) {
        Counters c = new Counters();
        resultRepo.findByRunIdOrderBySortAsc(r.getId()).forEach(x -> c.add(x.getStatus()));
        r.setStatus(c.failed > 0 || c.errors > 0 ? TestRunEntity.FAILED
                : c.blocked > 0 ? TestRunEntity.BLOCKED : TestRunEntity.SUCCESS);
        r.setConclusion(conclusion(c));
        r.setSummaryJson(writeSummary(c.total, c.passed, c.failed, c.skipped, c.blocked, c.errors));
        repo.save(r);
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
        if (TestRunEntity.RUNNING.equals(r.getStatus()) || TestRunEntity.QUEUED.equals(r.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "测试运行中不可删除");
        }
        // 证据随运行删除（无单条删除 API；此处是唯一清理入口）
        evidenceService.deleteRun(id);
        resultRepo.deleteByRunId(id);
        repo.delete(r);
    }

    // ---------------- 视图 ----------------

    public TestRunView toView(TestRunEntity r) {
        return toView(r, null);
    }

    public TestRunView toView(TestRunEntity r, WorkItemBrief workItem) {
        List<Long> suiteIds = parseIds(r.getSuiteIdsJson());
        RunSummary summary = parseSummary(r.getSummaryJson());
        List<CaseResultView> results = resultRepo.findByRunIdOrderBySortAsc(r.getId()).stream()
                .map(this::toResultView).toList();
        return new TestRunView(r.getId(), r.getProjectId(), r.getWorkItemId(), suiteIds, r.getDeploymentId(),
                r.getAgentNodeId(), r.getEnvironmentId(), r.getBaseUrl(), r.getStatus(), summary, r.getReportDocId(),
                r.getErrorSummary(),
                r.getTriggeredBy(), r.getStartedAt(), r.getFinishedAt(), r.getCreatedAt(), results, workItem,
                r.getRunScope() == null ? TestRunEntity.SCOPE_SUITE : r.getRunScope(), r.getConclusion(),
                r.getPlanRunId(), r.getPlanItemId(), r.getAttempt(), r.getRequirementId(), r.getShardKey());
    }

    /** 单条摘要（详情出参用）；workItemId 为空或已删除返回 null。 */
    private WorkItemBrief brief(String workItemId) {
        return workItemId == null ? null : briefsOf(List.of(workItemId)).get(workItemId);
    }

    /** 批量摘要（列表出参用）：一次批量查，避免逐行 N+1。 */
    private Map<String, WorkItemBrief> briefsOf(List<String> workItemIds) {
        List<String> ids = workItemIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        // 空表禁 Map.of()：历史记录可未关联工作单元，调用方 get(null) 时 MapN 抛 NPE
        return ids.isEmpty() ? new java.util.HashMap<>() : workItemService.briefsByIds(ids);
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
        if (r.getProjectId() != null) {
            md.append("- 项目: `").append(r.getProjectId()).append("`\n");
        } else {
            md.append("- 类型: 脚本套件（CAP-69，独立于项目）\n");
        }
        md.append("- 触发: ").append(r.getTriggeredBy() == null ? "user" : r.getTriggeredBy()).append("\n");
        md.append("- 状态: **").append(r.getStatus()).append("**\n");
        if (r.getConclusion() != null) {
            md.append("- 结论: **").append(r.getConclusion()).append("**\n");
        }
        int denominator = s.passed() + s.failed() + s.error();
        String passRate = denominator == 0 ? "—"
                : String.format(java.util.Locale.ROOT, "%.1f%%", s.passed() * 100.0 / denominator);
        md.append("- 结果: ").append(s.total()).append(" 用例 / ").append(s.passed()).append(" 通过 / ")
                .append(s.failed()).append(" 失败 / ").append(s.blocked()).append(" 阻塞 / ")
                .append(s.error()).append(" 异常 / ").append(s.skipped()).append(" 跳过 / 通过率 ")
                .append(passRate).append("\n");
        md.append("- 目标: ").append(r.getBaseUrl() == null || r.getBaseUrl().isBlank() ? "(节点)" : "`" + r.getBaseUrl() + "`").append("\n");
        if (r.getDeploymentId() != null) {
            md.append("- 关联部署: #").append(r.getDeploymentId()).append("\n");
        }
        if (r.getErrorSummary() != null && !r.getErrorSummary().isBlank()) {
            md.append("- 阻塞/异常说明: ").append(r.getErrorSummary()).append("\n");
        }
        List<TestCaseResultEntity> failed = results.stream()
                .filter(x -> "fail".equals(x.getStatus()) || "error".equals(x.getStatus())
                        || "blocked".equals(x.getStatus())).toList();
        if (!failed.isEmpty()) {
            md.append("\n## 失败清单\n\n");
            for (TestCaseResultEntity re : failed) {
                md.append("- [").append(re.getStatus() == null ? "fail" : re.getStatus()).append("] ")
                        .append(re.getName());
                if (re.getFailureCategory() != null) {
                    md.append(" · ").append(re.getFailureCategory());
                }
                if (re.getError() != null && !re.getError().isBlank()) {
                    md.append(" — ").append(cap(re.getError(), 200));
                }
                if (re.getEvidenceId() != null) {
                    md.append(" · 证据 /api/test-evidence/").append(re.getEvidenceId()).append("/download");
                }
                md.append("\n");
            }
        }
        md.append("\n## 用例结果\n\n");
        for (TestCaseResultEntity re : results) {
            String icon = switch (re.getStatus() == null ? "" : re.getStatus()) {
                case "pass" -> "✅";
                case "skip" -> "⏭";
                case "blocked" -> "⛔";
                case "error" -> "⚠️";
                default -> "❌";
            };
            md.append("### ").append(icon).append(" ").append(re.getName()).append(" (")
                    .append(re.getDuration() == null ? "?" : re.getDuration()).append("ms)\n\n");
            if (re.getNodeId() != null && !re.getNodeId().isBlank()) {
                md.append("- 节点: `").append(re.getNodeId()).append("`\n");
            }
            if (re.getFailureCategory() != null && !re.getFailureCategory().isBlank()) {
                md.append("- 失败分类: ").append(re.getFailureCategory()).append("\n");
            }
            if (re.getRequestSummary() != null && !re.getRequestSummary().isBlank()) {
                md.append("- 请求: `").append(re.getRequestSummary()).append("`\n");
            }
            if (re.getResponseSummary() != null && !re.getResponseSummary().isBlank()) {
                md.append("- 响应: `").append(re.getResponseSummary()).append("`\n");
            }
            for (AssertionResult a : parseAssertions(re.getAssertionJson())) {
                md.append("- 断言 ").append(a.pass() ? "✅" : "❌").append(' ')
                        .append(a.source() == null ? "" : a.source()).append(' ')
                        .append(a.selector() == null ? "" : a.selector()).append(' ')
                        .append(a.op() == null ? "" : a.op()).append(" 期望 `")
                        .append(a.expected() == null ? "" : a.expected()).append("` 实际 `")
                        .append(a.actual() == null ? "" : a.actual()).append('`');
                if (a.message() != null && !a.message().isBlank()) {
                    md.append(" — ").append(a.message());
                }
                md.append("\n");
            }
            if (re.getError() != null && !re.getError().isBlank()) {
                md.append("- 错误: ").append(re.getError()).append("\n");
            }
            if (re.getEvidenceId() != null) {
                md.append("- 证据: `/api/test-evidence/").append(re.getEvidenceId()).append("/download`\n");
            }
            if (re.getManualReview() != null) {
                md.append("- 人工复核: ").append(re.getManualReview());
                if (re.getManualNote() != null && !re.getManualNote().isBlank()) {
                    md.append(" — ").append(re.getManualNote());
                }
                md.append("\n");
            }
            md.append("\n");
        }
        return md.toString();
    }

    private void notify(TestRunEntity r, NotificationLevel level, String title, String body) {
        if (r.getPlanRunId() != null) {
            // 方案子运行不单独通知：一次方案几十条通知会淹没真正的方案级结果
            return;
        }
        try {
            notificationService.emit(new NotificationDraft(level, "test", title, body,
                    "test_run", String.valueOf(r.getId()), r.getProjectId(), List.of()));
        } catch (Exception e) {
            log.warn("测试通知发送失败: {}", e.getMessage());
        }
    }

    /**
     * 终态广播 test.completed（P0-3 事件总线）：workItemId 缺省从来源部署继承（CAP-10 自动回归链）。
     * 通知侧已直发 notify()，该事件在通知监听 IGNORED；订阅方是验收联动（回归全绿 → 需求进 ACCEPTANCE）。
     */
    private void publishCompleted(TestRunEntity r) {
        if (r.getPlanRunId() != null) {
            // 方案子运行不向外发布 test.completed：方案父运行完成后统一发布一次
            return;
        }
        try {
            String workItemId = r.getWorkItemId();
            if ((workItemId == null || workItemId.isBlank()) && r.getDeploymentId() != null) {
                workItemId = deploymentRepo.findById(r.getDeploymentId())
                        .map(DeploymentEntity::getWorkItemId).orElse(null);
            }
            eventPublisher.publish(SimpleDomainEvent.of("test.completed", r.getProjectId(), workItemId,
                    r.getTriggeredBy(),
                    "测试 #" + r.getId() + " " + r.getStatus(),
                    "TEST_RUN", String.valueOf(r.getId()), TestRunEntity.SUCCESS.equals(r.getStatus())));
        } catch (Exception e) {
            log.warn("test.completed 事件发布失败(不阻塞): run={} err={}", r.getId(), e.getMessage());
        }
    }

    /** 执行底座 WS topic：测试运行用 runId 字符串（与 /ws/test-runs/{id}/stream 对应） */
    private String topic(Long runId) {
        return String.valueOf(runId);
    }

    public CaseResultView toResultView(TestCaseResultEntity e) {
        return new CaseResultView(e.getId(), e.getCaseId(), e.getSuiteId(), e.getSort(), e.getName(),
                e.getStatus(), e.getRequestSummary(), e.getResponseSummary(), e.getError(), e.getDuration(),
                e.getNodeId(), e.getAttempt(), e.getFailureCategory(), parseAssertions(e.getAssertionJson()),
                e.getCommand(), e.getExitCode(), readObjectMap(e.getEnvSnapshotJson()), e.getEvidenceId(),
                e.getStartedAt(), e.getFinishedAt(), e.getManualReview(), e.getManualNote(),
                parseProgram(e));
    }

    /** 程序列 → 详情视图；非程序用例返回 null（前端据此区分展示）。 */
    private ProgramRunView parseProgram(TestCaseResultEntity e) {
        if (e.getProgramAssetId() == null && (e.getProgramManifestJson() == null || e.getProgramManifestJson().isBlank())) {
            return null;
        }
        ProgramManifest manifest = null;
        if (e.getProgramManifestJson() != null && !e.getProgramManifestJson().isBlank()) {
            try {
                manifest = ProgramManifest.parse(e.getProgramManifestJson());
            } catch (Exception ex) {
                log.debug("程序清单快照解析失败（按空字段展示）: result={} err={}", e.getId(), ex.getMessage());
            }
        }
        return new ProgramRunView(e.getProgramAssetId(), e.getProgramContractVersion(),
                manifest == null ? null : manifest.name(),
                manifest == null ? null : manifest.version(),
                manifest == null ? null : manifest.runtime(),
                manifest == null ? null : manifest.runtimeVersion(),
                manifest == null ? null : manifest.entrypoint(),
                manifest == null ? null : manifest.targetOs(),
                manifest == null ? null : manifest.targetArch(),
                e.getProgramStatus(), e.getProgramMessage(),
                readObjectMap(e.getProgramMetricsJson()), parseArtifacts(e.getProgramArtifactsJson()));
    }

    private List<ProgramRunView.Artifact> parseArtifacts(String json) {
        List<ProgramRunView.Artifact> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            for (JsonNode n : mapper.readTree(json)) {
                out.add(new ProgramRunView.Artifact(n.path("path").asText(null),
                        n.path("sizeBytes").asLong(0), n.path("sha256").asText(null)));
            }
        } catch (Exception e) {
            // 元数据损坏不影响结果展示
        }
        return out;
    }

    private List<AssertionResult> parseAssertions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, AssertionResult.class));
        } catch (Exception e) {
            return List.of();
        }
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
        return writeSummary(total, passed, failed, skipped, 0, 0);
    }

    private String writeSummary(int total, int passed, int failed, int skipped, int blocked, int errors) {
        try {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("total", total);
            m.put("passed", passed);
            m.put("failed", failed);
            m.put("skipped", skipped);
            m.put("blocked", blocked);
            m.put("error", errors);
            return mapper.writeValueAsString(m);
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
                    n.path("failed").asInt(0), n.path("skipped").asInt(0),
                    n.path("blocked").asInt(0), n.path("error").asInt(0));
        } catch (Exception e) {
            return new RunSummary(0, 0, 0, 0);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "null";
        }
    }

    private String writeMap(Map<String, String> map) {
        return toJson(map == null ? Map.of() : map);
    }

    private String cap(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…[截断]";
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
        if (s.length() <= max) {
            return s;
        }
        // 截断后缀计入 max：否则 128/512 这类定长列会写到 max+5 导致入库失败
        String suffix = "…[截断]";
        return s.substring(0, Math.max(0, max - suffix.length())) + suffix;
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

    /** 运行级计数：total 含全部结果，error 单列以便报告区分“断言失败”与“执行异常”。 */
    private static final class Counters {
        int total;
        int passed;
        int failed;
        int skipped;
        int blocked;
        int errors;

        void add(String status) {
            total++;
            switch (status == null ? "" : status.toLowerCase(java.util.Locale.ROOT)) {
                case "pass" -> passed++;
                case "fail" -> failed++;
                case "blocked" -> blocked++;
                case "error" -> errors++;
                default -> skipped++;
            }
        }

        void error() {
            total++;
            errors++;
        }
    }
}
