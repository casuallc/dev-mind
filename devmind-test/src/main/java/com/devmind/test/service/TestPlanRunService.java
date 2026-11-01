package com.devmind.test.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.audit.AuditService;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.docs.DocumentService;
import com.devmind.docs.dto.DocDetail;
import com.devmind.docs.dto.DocRequest;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.notification.dto.NotificationDraft;
import com.devmind.notification.model.NotificationLevel;
import com.devmind.notification.service.NotificationService;
import com.devmind.project.EnvironmentService;
import com.devmind.project.ProjectService;
import com.devmind.project.model.EnvironmentEntity;
import com.devmind.test.dto.CreatePlanRunRequest;
import com.devmind.test.dto.PlanCaseResult;
import com.devmind.test.dto.PlanChildRunSpec;
import com.devmind.test.dto.PlanSnapshot;
import com.devmind.test.dto.PrecheckReport;
import com.devmind.test.dto.RunSummary;
import com.devmind.test.dto.TestPlanRunView;
import com.devmind.test.dto.TestRunView;
import com.devmind.test.model.TestCaseEntity;
import com.devmind.test.model.TestCaseResultEntity;
import com.devmind.test.model.TestPlanEntity;
import com.devmind.test.model.TestPlanItemEntity;
import com.devmind.test.model.TestPlanRunEntity;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.repo.TestCaseRepository;
import com.devmind.test.repo.TestCaseResultRepository;
import com.devmind.test.repo.TestPlanItemRepository;
import com.devmind.test.repo.TestPlanRepository;
import com.devmind.test.repo.TestPlanRunRepository;
import com.devmind.test.repo.TestRunRepository;
import com.devmind.test.spec.CaseSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * CAP-104 M2/M3 方案运行编排：预检 → 固化快照 → 父记录 → 子运行（顺序/并行组）→ 汇总。
 * FAIL_FAST 停止调度新项；重试保留每次 attempt；SHARD/MATRIX 把用例分散/复制到多节点。
 * 父运行只存快照与结果索引，用例结果与证据留在子运行，支持下钻。
 */
@Service
public class TestPlanRunService {

    private static final Logger log = LoggerFactory.getLogger(TestPlanRunService.class);
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(token|secret|password|passwd|credential|apikey|api_key).*");
    private static final String MASK = "******";

    private final TestPlanRunRepository repo;
    private final TestPlanRepository planRepo;
    private final TestPlanItemRepository itemRepo;
    private final TestRunRepository runRepo;
    private final TestCaseRepository caseRepo;
    private final TestCaseResultRepository resultRepo;
    private final TestPlanService planService;
    private final TestRunService runService;
    private final ProjectService projectService;
    private final EnvironmentService environmentService;
    private final EnvironmentPrecheckService precheckService;
    private final ObjectProvider<AgentNodeConnector> connectorProvider;
    private final NotificationService notificationService;
    private final DocumentService documentService;
    private final AuditService auditService;
    private final IdentityService identityService;
    private final DomainEventPublisher eventPublisher;
    private final ExecutionLogHub hub;
    private final ObjectMapper mapper;
    private final Duration staleTimeout;
    private final ExecutorService orchestrator = Executors.newVirtualThreadPerTaskExecutor();

    public TestPlanRunService(TestPlanRunRepository repo, TestPlanRepository planRepo,
                              TestPlanItemRepository itemRepo, TestRunRepository runRepo,
                              TestCaseRepository caseRepo, TestCaseResultRepository resultRepo,
                              TestPlanService planService, TestRunService runService,
                              ProjectService projectService, EnvironmentService environmentService,
                              EnvironmentPrecheckService precheckService,
                              ObjectProvider<AgentNodeConnector> connectorProvider,
                              NotificationService notificationService, DocumentService documentService,
                              AuditService auditService, IdentityService identityService,
                              DomainEventPublisher eventPublisher, ExecutionLogHub hub, ObjectMapper mapper,
                              @Value("${devmind.test.stale-timeout:PT2H}") Duration staleTimeout) {
        this.repo = repo;
        this.planRepo = planRepo;
        this.itemRepo = itemRepo;
        this.runRepo = runRepo;
        this.caseRepo = caseRepo;
        this.resultRepo = resultRepo;
        this.planService = planService;
        this.runService = runService;
        this.projectService = projectService;
        this.environmentService = environmentService;
        this.precheckService = precheckService;
        this.connectorProvider = connectorProvider;
        this.notificationService = notificationService;
        this.documentService = documentService;
        this.auditService = auditService;
        this.identityService = identityService;
        this.eventPublisher = eventPublisher;
        this.hub = hub;
        this.mapper = mapper;
        this.staleTimeout = staleTimeout;
    }

    @PreDestroy
    public void shutdown() {
        orchestrator.shutdownNow();
    }

    // ---------------- 触发 ----------------

    /** 触发方案运行：返回父记录后后台编排（前端可轮询/WS 看进度）。 */
    public TestPlanRunView create(Long planId, CreatePlanRunRequest req) {
        TestPlanEntity plan = planService.require(planId);
        if (Boolean.TRUE.equals(plan.getIsTemplate())) {
            throw new DevMindException(ErrorCode.CONFLICT, "模板方案不可直接运行，请先复制为新方案");
        }
        String projectId = plan.getProjectId();
        projectService.requireProject(projectId);
        CreatePlanRunRequest request = req == null
                ? new CreatePlanRunRequest(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null)
                : req;
        String mode = mode(request.mode());
        Long environmentId = request.environmentId() != null ? request.environmentId() : plan.getEnvironmentId();
        if (environmentId != null) {
            environmentService.requireEnvironment(projectId, environmentId);
        }
        List<String> explicitNodes = cleanList(request.nodeIds());
        if (TestPlanRunEntity.MODE_SEQUENTIAL.equals(mode) && !explicitNodes.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "顺序模式不支持 nodeIds；单节点请用 agentNodeId");
        }
        List<String> labels = cleanList(request.labels());
        List<String> capabilities = cleanList(request.capabilities());
        String agentNodeId = blankToNull(request.agentNodeId());
        List<String> nodeIds = List.of();
        if (TestPlanRunEntity.MODE_SEQUENTIAL.equals(mode)) {
            if (agentNodeId != null) {
                verifyNodeIds(List.of(agentNodeId));
            } else {
                agentNodeId = firstEnvNode(environmentId, projectId);
                if (agentNodeId == null && (!labels.isEmpty() || !capabilities.isEmpty())) {
                    agentNodeId = firstMatchingNode(labels, capabilities);
                    if (agentNodeId == null) {
                        throw new DevMindException(ErrorCode.BAD_REQUEST,
                                "没有满足标签/能力要求的在线节点: labels=" + labels + " capabilities=" + capabilities);
                    }
                }
                // 都未命中时保持 null，交给执行底座的“项目默认 → 平台默认”链路
            }
        } else {
            verifyNodeIds(explicitNodes);
            nodeIds = resolveDistributedNodes(explicitNodes, environmentId, projectId, labels, capabilities);
        }
        String baseUrl = resolveBaseUrl(request.baseUrl(), environmentId, projectId);
        String requirementId = request.requirementId() != null
                ? blankToNull(request.requirementId()) : plan.getRequirementId();
        Map<String, String> parameters = mergeParams(readStringMap(plan.getParametersJson()),
                request.parameters());
        List<TestPlanItemEntity> items = itemRepo.findByPlanIdOrderBySeqAsc(planId).stream()
                .filter(i -> Boolean.TRUE.equals(i.getEnabled())).toList();
        if (items.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "方案没有启用的方案项");
        }
        PlanSnapshot snapshot = planService.buildSnapshot(plan, items, mode, nodeIds,
                agentNodeId, environmentId, baseUrl, requirementId, parameters,
                request.failStrategy(), request.concurrency(), request.timeoutSec(), labels, capabilities);
        return start(plan, snapshot, request, environmentId, baseUrl, requirementId,
                planService.environmentSnapshot(environmentId, projectId, baseUrl));
    }

    /** 按原方案运行快照重跑（方案被改/删也不影响）；支持只重跑失败项/分片。 */
    public TestPlanRunView rerun(Long planRunId, CreatePlanRunRequest req) {
        TestPlanRunEntity src = require(planRunId);
        PlanSnapshot snap = parseSnapshot(src.getPlanSnapshotJson());
        CreatePlanRunRequest request = req == null
                ? new CreatePlanRunRequest(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null)
                : req;
        List<String> shardKeys = cleanList(request.shardKeys());
        boolean failedOnly = Boolean.TRUE.equals(request.failedOnly());
        List<TestPlanRunView.ItemResult> oldItems = parseItemResults(src.getItemResultsJson());
        if (failedOnly && shardKeys.isEmpty()) {
            shardKeys = oldItems.stream()
                    .filter(i -> i.shardKey() != null && !"PASS".equals(i.status()))
                    .map(TestPlanRunView.ItemResult::shardKey).distinct().toList();
        }
        List<Long> itemIds = failedOnly ? oldItems.stream()
                .filter(i -> i.itemId() != null && !"PASS".equals(i.status()))
                .map(TestPlanRunView.ItemResult::itemId).toList() : List.of();
        List<PlanSnapshot.PlanItemSnapshot> runItems = snap.items();
        if (!itemIds.isEmpty() && TestPlanRunEntity.MODE_SEQUENTIAL.equals(snap.mode())) {
            runItems = snap.items().stream()
                    .filter(i -> i.itemId() != null && itemIds.contains(i.itemId())).toList();
        }
        String requirementId = request.requirementId() != null
                ? blankToNull(request.requirementId()) : snap.requirementId();
        Long environmentId = request.environmentId() != null ? request.environmentId() : snap.environmentId();
        String baseUrl = request.baseUrl() != null && !request.baseUrl().isBlank()
                ? request.baseUrl().strip() : snap.baseUrl();
        Map<String, String> parameters = mergeParams(snap.parameters(), request.parameters());
        String failStrategy = request.failStrategy() == null || request.failStrategy().isBlank()
                ? snap.failStrategy() : request.failStrategy().strip().toUpperCase(Locale.ROOT);
        if (!TestPlanEntity.FAIL_FAST.equals(failStrategy) && !TestPlanEntity.CONTINUE.equals(failStrategy)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "failStrategy 只能是 FAIL_FAST / CONTINUE");
        }
        int concurrency = request.concurrency() != null ? request.concurrency()
                : snap.concurrency() == null ? 1 : snap.concurrency();
        if (concurrency < 1 || concurrency > 16) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "concurrency 需在 1~16 之间");
        }
        Integer timeoutSec = request.timeoutSec() != null ? request.timeoutSec() : snap.timeoutSec();
        if (timeoutSec != null && timeoutSec <= 0) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "timeoutSec 必须大于 0");
        }
        List<String> labels = request.labels() == null ? (snap.labels() == null ? List.of() : snap.labels())
                : cleanList(request.labels());
        List<String> capabilities = request.capabilities() == null
                ? (snap.capabilities() == null ? List.of() : snap.capabilities())
                : cleanList(request.capabilities());
        String agentNodeId = request.agentNodeId() != null ? blankToNull(request.agentNodeId()) : snap.agentNodeId();
        List<String> nodeIds = snap.nodeIds() == null ? List.of() : snap.nodeIds();
        if (TestPlanRunEntity.MODE_SEQUENTIAL.equals(snap.mode())) {
            if (!cleanList(request.nodeIds()).isEmpty()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "顺序模式不支持 nodeIds；单节点请用 agentNodeId");
            }
            if (agentNodeId != null) {
                verifyNodeIds(List.of(agentNodeId));
            }
        } else {
            List<String> requestedNodes = cleanList(request.nodeIds());
            if (!requestedNodes.isEmpty()) {
                verifyNodeIds(requestedNodes);
                nodeIds = requestedNodes;
            } else if (request.labels() != null || request.capabilities() != null) {
                nodeIds = resolveDistributedNodes(List.of(), environmentId, src.getProjectId(),
                        labels, capabilities);
            }
        }
        PlanSnapshot runSnap = new PlanSnapshot(snap.version(), snap.planId(), snap.planName(),
                snap.planVersion(), failStrategy, concurrency, timeoutSec, labels, capabilities, environmentId,
                requirementId, snap.mode(), nodeIds, agentNodeId, baseUrl,
                parameters, Instant.now(), runItems);
        TestPlanEntity plan = new TestPlanEntity();
        plan.setId(src.getPlanId());
        plan.setProjectId(src.getProjectId());
        plan.setName(src.getPlanName());
        plan.setPlanVersion(src.getPlanVersion());
        plan.setFailStrategy(failStrategy);
        plan.setConcurrency(concurrency);
        plan.setEnvironmentId(environmentId);
        plan.setRequirementId(requirementId);
        CreatePlanRunRequest rerunReq = new CreatePlanRunRequest(environmentId, baseUrl, agentNodeId,
                nodeIds, snap.mode(), parameters, null, requirementId, shardKeys, failedOnly,
                failStrategy, concurrency, timeoutSec, labels, capabilities);
        return start(plan, runSnap, rerunReq, environmentId, baseUrl, requirementId,
                src.getEnvSnapshotJson() == null ? Map.of() : readObjectMap(src.getEnvSnapshotJson()));
    }

    private TestPlanRunView start(TestPlanEntity plan, PlanSnapshot snapshot, CreatePlanRunRequest request,
                                  Long environmentId, String baseUrl, String requirementId,
                                  Map<String, Object> envSnapshot) {
        TestPlanRunEntity pr = new TestPlanRunEntity();
        pr.setProjectId(plan.getProjectId());
        pr.setPlanId(plan.getId());
        pr.setPlanName(snapshot.planName());
        pr.setPlanVersion(snapshot.planVersion());
        pr.setPlanSnapshotJson(write(snapshot));
        pr.setEnvSnapshotJson(write(envSnapshot));
        pr.setMode(snapshot.mode());
        pr.setStatus(TestPlanRunEntity.RUNNING);
        pr.setRequirementId(requirementId);
        pr.setEnvironmentId(environmentId);
        pr.setBaseUrl(baseUrl);
        pr.setTriggeredBy(identityService.currentActor());
        pr.setCancelRequested(false);
        Instant now = Instant.now();
        pr.setStartedAt(now);
        pr.setCreatedAt(now);
        repo.save(pr);
        // 仅重跑失败分片：通过临时字段传给编排线程（不落库，快照里已有全量 nodeIds）
        boolean failedOnly = Boolean.TRUE.equals(request.failedOnly());
        List<String> shardKeys = cleanList(request.shardKeys());
        String workItemId = blankToNull(request.workItemId());
        orchestrator.submit(() -> orchestrate(pr.getId(), shardKeys, failedOnly, workItemId));
        auditService.record("test", "plan.run", pr.getTriggeredBy(), pr.getProjectId(), true,
                "方案运行 #" + pr.getId() + " plan=" + pr.getPlanId() + " mode=" + pr.getMode());
        return toView(pr, new ArrayList<>());
    }

    // ---------------- 编排 ----------------

    private void orchestrate(Long planRunId, List<String> selectedShards, boolean failedOnly,
                             String workItemId) {
        TestPlanRunEntity pr = repo.findById(planRunId).orElse(null);
        if (pr == null) {
            return;
        }
        List<ItemOutcome> outcomes = new ArrayList<>();
        try {
            PlanSnapshot snap = parseSnapshot(pr.getPlanSnapshotJson());
            String topic = topic(planRunId);
            hub.publishLog(topic, "[方案开始] " + pr.getPlanName() + " · " + pr.getMode()
                    + " · 并发 " + snap.concurrency());
            PrecheckOutcome precheck = precheck(pr, snap);
            pr.setPrecheckJson(precheck == null ? null : write(precheck.report()));
            repo.save(pr);
            if (precheck != null && precheck.blockAll()) {
                String reason = failureSummary(precheck.report());
                for (PlanSnapshot.PlanItemSnapshot item : snap.items()) {
                    ItemOutcome o = new ItemOutcome(item.itemId(), item.refType(), item.refId(), item.seq(),
                            item.parallelGroup(), item.refName(), null);
                    o.status = "BLOCKED";
                    o.conclusion = "BLOCKED";
                    o.error = reason;
                    o.caseCount = item.cases().size();
                    o.blocked = item.cases().size();
                    outcomes.add(o);
                }
                finish(pr, outcomes, false, "环境预检未通过: " + reason);
                return;
            }
            if (TestPlanRunEntity.MODE_SEQUENTIAL.equals(pr.getMode())) {
                orchestrateSequential(pr, snap, outcomes);
            } else {
                orchestrateDistributed(pr, snap, outcomes, selectedShards,
                        precheck == null ? Map.of() : precheck.nodeFailures());
            }
            boolean cancelled = cancelled(planRunId);
            finish(pr, outcomes, cancelled, null);
        } catch (Exception e) {
            log.warn("方案运行编排失败: planRun={} err={}", planRunId, e.getMessage(), e);
            try {
                pr.setStatus(TestPlanRunEntity.FAILED);
                pr.setConclusion("FAIL");
                pr.setErrorSummary(truncate(rootMessage(e), 2000));
                pr.setFinishedAt(Instant.now());
                repo.save(pr);
                hub.done(topic(planRunId), pr.getStatus());
                notifyPlanRun(pr);
            } catch (Exception ignored) {
                // 收口失败不再抛
            }
        }
    }

    /**
     * 环境预检：顺序模式任何失败都阻断整个方案；分布式按节点区分——
     * 节点级失败（NODE_ONLINE/EXEC_PROTOCOL/COMMAND）只阻塞该节点，其他节点继续（FR-20）；
     * HTTP/VERSION_MATCH 属环境级失败，仍阻断整个方案，避免半个方案假通过。
     */
    private PrecheckOutcome precheck(TestPlanRunEntity pr, PlanSnapshot snap) {
        if (pr.getEnvironmentId() == null) {
            return null;
        }
        if (TestPlanRunEntity.MODE_SEQUENTIAL.equals(pr.getMode())) {
            PrecheckReport report = precheckService.run(pr.getProjectId(), pr.getEnvironmentId(),
                    snap.agentNodeId());
            if (report.passed()) {
                return new PrecheckOutcome(report, Map.of(), false);
            }
            String node = snap.agentNodeId() == null ? "" : snap.agentNodeId();
            return new PrecheckOutcome(report, Map.of(node, failureSummary(report)), true);
        }
        List<PrecheckReport.PrecheckItem> items = new ArrayList<>();
        Map<String, String> nodeFailures = new LinkedHashMap<>();
        boolean globalFailure = false;
        for (String nodeId : snap.nodeIds()) {
            PrecheckReport report = precheckService.runForNode(pr.getProjectId(), pr.getEnvironmentId(), nodeId);
            report.items().forEach(i -> items.add(new PrecheckReport.PrecheckItem(
                    nodeId + "/" + i.name(), i.pass(), i.expected(), i.actual(), i.message())));
            String nodeReason = report.items().stream()
                    .filter(i -> !i.pass() && nodeScoped(i.name()))
                    .map(i -> i.name() + ": " + i.message())
                    .reduce((a, b) -> a + "；" + b).orElse(null);
            if (nodeReason != null) {
                nodeFailures.put(nodeId, nodeReason);
            }
            if (report.items().stream().anyMatch(i -> !i.pass() && !nodeScoped(i.name()))) {
                globalFailure = true;
            }
        }
        PrecheckReport report = new PrecheckReport(!globalFailure && nodeFailures.isEmpty(),
                Instant.now(), items);
        return new PrecheckOutcome(report, nodeFailures, globalFailure);
    }

    static boolean nodeScoped(String itemName) {
        if (itemName == null) {
            return false;
        }
        String name = itemName.substring(itemName.lastIndexOf('/') + 1);
        return "NODE_ONLINE".equals(name) || "EXEC_PROTOCOL".equals(name) || "COMMAND".equals(name);
    }

    private static String failureSummary(PrecheckReport report) {
        return report.items().stream().filter(i -> !i.pass())
                .map(i -> i.name() + ": " + i.message())
                .reduce((a, b) -> a + "；" + b).orElse("环境预检未通过");
    }

    private void orchestrateSequential(TestPlanRunEntity pr, PlanSnapshot snap, List<ItemOutcome> outcomes) {
        List<List<PlanSnapshot.PlanItemSnapshot>> groups = groups(snap.items());
        boolean failFast = TestPlanEntity.FAIL_FAST.equals(snap.failStrategy());
        AtomicBoolean stop = new AtomicBoolean(false);
        for (List<PlanSnapshot.PlanItemSnapshot> group : groups) {
            if (stop.get() || cancelled(pr.getId())) {
                markRemaining(group, outcomes, cancelled(pr.getId()) ? "CANCELLED" : "SKIPPED",
                        cancelled(pr.getId()) ? "方案已取消" : "FAIL_FAST：前序方案项失败，未执行");
                continue;
            }
            List<ItemOutcome> groupOutcomes = new ArrayList<>();
            if (group.size() == 1) {
                groupOutcomes.add(executeItem(pr, snap, group.get(0), null, null, group.get(0).cases()));
            } else {
                groupOutcomes.addAll(executeGroup(pr, snap, group, stop));
            }
            outcomes.addAll(groupOutcomes);
            if (failFast && groupOutcomes.stream().anyMatch(o -> !"PASS".equals(o.status))) {
                stop.set(true);
            }
        }
    }

    /** parallelGroup 相同的项并行；无组名的项独占一组；组按首次出现的 seq 顺序执行。 */
    private List<List<PlanSnapshot.PlanItemSnapshot>> groups(List<PlanSnapshot.PlanItemSnapshot> items) {
        List<PlanSnapshot.PlanItemSnapshot> sorted = items.stream()
                .sorted(Comparator.comparing(i -> i.seq() == null ? Integer.MAX_VALUE : i.seq()))
                .toList();
        List<List<PlanSnapshot.PlanItemSnapshot>> groups = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PlanSnapshot.PlanItemSnapshot item : sorted) {
            String g = item.parallelGroup();
            if (g == null || g.isBlank()) {
                groups.add(List.of(item));
            } else if (seen.add(g)) {
                groups.add(sorted.stream().filter(x -> g.equals(x.parallelGroup())).toList());
            }
        }
        return groups;
    }

    private List<ItemOutcome> executeGroup(TestPlanRunEntity pr, PlanSnapshot snap,
                                           List<PlanSnapshot.PlanItemSnapshot> group, AtomicBoolean stop) {
        int concurrency = Math.max(1, Math.min(
                snap.concurrency() == null ? 1 : snap.concurrency(), group.size()));
        Semaphore sem = new Semaphore(concurrency);
        List<Future<ItemOutcome>> futures = new ArrayList<>();
        for (PlanSnapshot.PlanItemSnapshot item : group) {
            futures.add(orchestrator.submit(() -> {
                sem.acquire();
                try {
                    if (stop.get()) {
                        ItemOutcome skipped = new ItemOutcome(item.itemId(), item.refType(), item.refId(),
                                item.seq(), item.parallelGroup(), item.refName(), null);
                        skipped.status = "SKIPPED";
                        skipped.conclusion = "SKIPPED";
                        skipped.error = "FAIL_FAST：同组前序项失败，未执行";
                        skipped.caseCount = item.cases().size();
                        return skipped;
                    }
                    return executeItem(pr, snap, item, null, null, item.cases());
                } finally {
                    sem.release();
                }
            }));
        }
        List<ItemOutcome> out = new ArrayList<>();
        for (Future<ItemOutcome> f : futures) {
            try {
                out.add(f.get());
            } catch (Exception e) {
                out.add(ItemOutcome.failed(null, "并行方案项执行异常: " + rootMessage(e)));
            }
        }
        return out;
    }

    private ItemOutcome executeItem(TestPlanRunEntity pr, PlanSnapshot snap,
                                    PlanSnapshot.PlanItemSnapshot item, String node, String shardKey,
                                    List<PlanSnapshot.TestCaseSnapshot> scopedCases) {
        ItemOutcome out = new ItemOutcome(item.itemId(), item.refType(), item.refId(), item.seq(),
                item.parallelGroup(), item.refName(), shardKey);
        Map<String, Object> overrides = item.overrides() == null ? Map.of() : item.overrides();
        int maxAttempts = retryMax(item, overrides);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (cancelled(pr.getId())) {
                out.status = "CANCELLED";
                out.conclusion = "CANCELLED";
                out.error = "方案已取消";
                out.caseCount = scopedCases.size();
                return out;
            }
            List<PlanSnapshot.TestCaseSnapshot> cases = applyOverrides(scopedCases, overrides, snap.timeoutSec());
            Map<String, String> parameters = mergedParameters(snap.parameters(), overrides);
            List<Long> suiteIds = cases.stream().map(PlanSnapshot.TestCaseSnapshot::suiteId)
                    .filter(Objects::nonNull).distinct().toList();
            String childNode = node != null ? node : overrideNode(overrides);
            if (childNode == null) {
                childNode = snap.agentNodeId();
            }
            PlanChildRunSpec spec = new PlanChildRunSpec(pr.getProjectId(), pr.getId(), item.itemId(),
                    attempt, pr.getRequirementId(), null, pr.getEnvironmentId(), childNode, pr.getBaseUrl(),
                    shardKey, suiteIds,
                    TestPlanItemEntity.REF_CASE.equals(item.refType()) ? item.refId() : null,
                    TestPlanItemEntity.REF_CASE.equals(item.refType()) ? TestRunEntity.SCOPE_CASE
                            : TestRunEntity.SCOPE_SUITE,
                    parameters, cases);
            TestRunView child = runService.createPlanChildRun(spec, pr.getTriggeredBy());
            out.absorb(child, attempt, scopedCases.size());
            hub.publishLog(topic(pr.getId()), "[方案项] " + itemLabel(item)
                    + (shardKey == null ? "" : " · 节点 " + shardKey) + " · 第 " + attempt
                    + " 次 · " + child.status());
            if (TestRunEntity.SUCCESS.equals(child.status())) {
                return out;
            }
            if (attempt >= maxAttempts || !shouldRetry(child, overrides)) {
                return out;
            }
        }
        return out;
    }

    /**
     * 分布式编排：以「方案项 × 节点」为调度单元，每个单元一条子运行，保留 nodeId/shardKey 便于下钻。
     * MATRIX 每节点跑该项全量用例；SHARD 按用例 key 哈希分片。节点预检失败只阻塞该节点，
     * 其他节点继续（FR-20）；FAIL_FAST 停止调度尚未开始的单元，未执行用例记 BLOCKED/SKIPPED。
     */
    private void orchestrateDistributed(TestPlanRunEntity pr, PlanSnapshot snap, List<ItemOutcome> outcomes,
                                        List<String> selectedShards, Map<String, String> nodeFailures) {
        List<String> nodes = snap.nodeIds() == null ? List.of() : snap.nodeIds();
        if (nodes.isEmpty()) {
            outcomes.add(ItemOutcome.failed(null, "分布式模式缺少节点列表"));
            return;
        }
        Set<String> selected = selectedShards == null || selectedShards.isEmpty()
                ? Set.of() : new HashSet<>(selectedShards);
        boolean failFast = TestPlanEntity.FAIL_FAST.equals(snap.failStrategy());
        boolean matrix = TestPlanRunEntity.MODE_MATRIX.equals(snap.mode());
        AtomicBoolean stop = new AtomicBoolean(false);
        int concurrency = Math.max(1, snap.concurrency() == null ? 1 : snap.concurrency());
        Semaphore sem = new Semaphore(concurrency);
        List<Future<ItemOutcome>> futures = new ArrayList<>();
        int seq = 1;
        for (PlanSnapshot.PlanItemSnapshot item : snap.items()) {
            for (String node : nodes) {
                List<PlanSnapshot.TestCaseSnapshot> assigned = matrix
                        ? item.cases() : shardCases(item.cases(), node, nodes);
                int order = seq++;
                futures.add(orchestrator.submit(() -> {
                    if (!selected.isEmpty() && !selected.contains(node)) {
                        ItemOutcome o = ItemOutcome.skipped(item, node, order, "未选择该分片重跑");
                        o.caseCount = assigned.size();
                        return o;
                    }
                    if (assigned.isEmpty()) {
                        return ItemOutcome.skipped(item, node, order, "该节点未分到用例");
                    }
                    String nodeFailure = nodeFailures.get(node);
                    if (nodeFailure != null) {
                        ItemOutcome o = new ItemOutcome(item.itemId(), item.refType(), item.refId(),
                                item.seq(), item.parallelGroup(), item.refName(), node);
                        o.status = "BLOCKED";
                        o.conclusion = "BLOCKED";
                        o.error = "节点预检未通过: " + nodeFailure;
                        o.caseCount = assigned.size();
                        o.blocked = assigned.size();
                        return o;
                    }
                    sem.acquire();
                    try {
                        if (cancelled(pr.getId())) {
                            ItemOutcome o = new ItemOutcome(item.itemId(), item.refType(), item.refId(),
                                    item.seq(), item.parallelGroup(), item.refName(), node);
                            o.status = "CANCELLED";
                            o.conclusion = "CANCELLED";
                            o.error = "方案已取消";
                            o.caseCount = assigned.size();
                            return o;
                        }
                        if (stop.get()) {
                            ItemOutcome o = ItemOutcome.skipped(item, node, order,
                                    "FAIL_FAST：前序方案项失败，未执行");
                            o.caseCount = assigned.size();
                            return o;
                        }
                        ItemOutcome out = executeItem(pr, snap, item, node, node, assigned);
                        if (failFast && !"PASS".equals(out.status)) {
                            stop.set(true);
                        }
                        return out;
                    } finally {
                        sem.release();
                    }
                }));
            }
        }
        for (Future<ItemOutcome> f : futures) {
            try {
                outcomes.add(f.get());
            } catch (Exception e) {
                outcomes.add(ItemOutcome.failed(null, "分布式方案项执行异常: " + rootMessage(e)));
            }
        }
    }

    /** SHARD 分片：用例 key（caseKey 优先，否则 id）稳定哈希到节点下标。 */
    static List<PlanSnapshot.TestCaseSnapshot> shardCases(List<PlanSnapshot.TestCaseSnapshot> cases,
                                                         String node, List<String> nodes) {
        int idx = nodes.indexOf(node);
        if (idx < 0) {
            return List.of();
        }
        List<PlanSnapshot.TestCaseSnapshot> out = new ArrayList<>();
        for (PlanSnapshot.TestCaseSnapshot c : cases) {
            String key = c.caseKey() == null ? String.valueOf(c.id()) : c.caseKey();
            if (Math.floorMod(key.hashCode(), nodes.size()) == idx) {
                out.add(c);
            }
        }
        return out;
    }

    // ---------------- 汇总 / 收口 ----------------

    private void finish(TestPlanRunEntity pr, List<ItemOutcome> outcomes, boolean cancelled, String error) {
        int items = outcomes.size();
        int passed = 0;
        int failed = 0;
        int blocked = 0;
        int skipped = 0;
        int cancelledCount = 0;
        int caseTotal = 0;
        int casePassed = 0;
        int caseFailed = 0;
        int caseBlocked = 0;
        int caseErrors = 0;
        for (ItemOutcome o : outcomes) {
            switch (o.status == null ? "" : o.status) {
                case "PASS" -> passed++;
                case "FAIL" -> failed++;
                case "BLOCKED" -> blocked++;
                case "CANCELLED" -> cancelledCount++;
                default -> skipped++;
            }
            caseTotal += o.caseCount;
            casePassed += o.passed;
            caseFailed += o.failed;
            caseBlocked += o.blocked;
            caseErrors += o.errors;
        }
        String conclusion = cancelled || cancelledCount > 0 ? "CANCELLED"
                : caseTotal == 0 ? "BLOCKED"
                : caseFailed == 0 && caseErrors == 0 && caseBlocked == 0 && failed == 0 && blocked == 0
                ? "PASS"
                : casePassed > 0 ? "PARTIAL"
                : caseBlocked > 0 && caseFailed == 0 && caseErrors == 0 ? "BLOCKED" : "FAIL";
        String status = switch (conclusion) {
            case "PASS" -> TestPlanRunEntity.SUCCESS;
            case "BLOCKED" -> TestPlanRunEntity.BLOCKED;
            case "CANCELLED" -> TestPlanRunEntity.CANCELLED;
            default -> TestPlanRunEntity.FAILED;
        };
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("items", items);
        summary.put("executed", passed + failed + blocked);
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("blocked", blocked);
        summary.put("skipped", skipped);
        summary.put("cancelled", cancelledCount);
        summary.put("total", caseTotal);
        summary.put("casePassed", casePassed);
        summary.put("caseFailed", caseFailed);
        summary.put("caseBlocked", caseBlocked);
        summary.put("caseErrors", caseErrors);
        int denominator = casePassed + caseFailed + caseErrors;
        summary.put("passRate", denominator == 0 ? null
                : Math.round(casePassed * 1000.0 / denominator) / 10.0);
        summary.put("conclusion", conclusion);
        pr.setStatus(status);
        pr.setConclusion(conclusion);
        pr.setSummaryJson(write(summary));
        pr.setItemResultsJson(write(outcomes.stream().map(ItemOutcome::toView).toList()));
        if (error != null) {
            pr.setErrorSummary(truncate(error, 2000));
        }
        pr.setFinishedAt(Instant.now());
        repo.save(pr);
        createReportDocQuiet(pr);
        hub.done(topic(pr.getId()), status);
        notifyPlanRun(pr);
        try {
            eventPublisher.publish(SimpleDomainEvent.of("test.completed", pr.getProjectId(), null,
                    pr.getTriggeredBy(), "方案运行 #" + pr.getId() + " " + conclusion,
                    "TEST_PLAN_RUN", String.valueOf(pr.getId()), "PASS".equals(conclusion)));
        } catch (Exception e) {
            log.warn("方案完成事件发布失败(不阻塞): {}", e.getMessage());
        }
        auditService.record("test", "plan.run.finish", pr.getTriggeredBy(), pr.getProjectId(),
                "PASS".equals(conclusion), "方案运行 #" + pr.getId() + " " + conclusion);
    }

    private void markRemaining(List<PlanSnapshot.PlanItemSnapshot> group, List<ItemOutcome> outcomes,
                               String status, String reason) {
        for (PlanSnapshot.PlanItemSnapshot item : group) {
            ItemOutcome o = new ItemOutcome(item.itemId(), item.refType(), item.refId(), item.seq(),
                    item.parallelGroup(), item.refName(), null);
            o.status = status;
            o.conclusion = status;
            o.error = reason;
            o.caseCount = item.cases().size();
            outcomes.add(o);
        }
    }

    private void notifyPlanRun(TestPlanRunEntity pr) {
        try {
            Map<String, Object> summary = readObjectMap(pr.getSummaryJson());
            String body = "结论 " + pr.getConclusion() + " · 用例 " + summary.getOrDefault("total", 0)
                    + " 项 · 通过 " + summary.getOrDefault("casePassed", 0)
                    + " / 失败 " + summary.getOrDefault("caseFailed", 0)
                    + " / 阻塞 " + summary.getOrDefault("caseBlocked", 0)
                    + (pr.getBaseUrl() == null ? "" : " · " + pr.getBaseUrl());
            NotificationLevel level = "PASS".equals(pr.getConclusion())
                    ? NotificationLevel.P2 : NotificationLevel.P1;
            notificationService.emit(new NotificationDraft(level, "test-plan-run",
                    "方案运行" + ("PASS".equals(pr.getConclusion()) ? "通过" : "结束")
                            + " #" + pr.getId() + " " + pr.getPlanName(),
                    body, "TEST_PLAN_RUN", String.valueOf(pr.getId()), pr.getProjectId(), List.of()));
        } catch (Exception e) {
            log.warn("方案运行通知发送失败: {}", e.getMessage());
        }
    }

    // ---------------- 取消 / 重跑 ----------------

    public TestPlanRunView cancel(Long id) {
        TestPlanRunEntity pr = require(id);
        if (!TestPlanRunEntity.RUNNING.equals(pr.getStatus())
                && !TestPlanRunEntity.QUEUED.equals(pr.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "方案运行已结束，不能取消");
        }
        pr.setCancelRequested(true);
        repo.save(pr);
        hub.publishLog(topic(id), "[取消] 已请求取消，未启动的方案项将被跳过");
        auditService.record("test", "plan.run.cancel", identityService.currentActor(), pr.getProjectId(),
                true, "方案运行 #" + id);
        return toView(pr, new ArrayList<>());
    }

    private boolean cancelled(Long planRunId) {
        return repo.findById(planRunId).map(p -> Boolean.TRUE.equals(p.getCancelRequested())).orElse(false);
    }

    // ---------------- 查询 ----------------

    public TestPlanRunEntity require(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "方案运行不存在: " + id));
    }

    public TestPlanRunView get(Long id) {
        return toView(require(id), null);
    }

    public List<TestPlanRunView> list(String projectId, Long planId, String status, String conclusion) {
        if (projectId == null || projectId.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "projectId 必填");
        }
        projectService.requireProject(projectId);
        List<TestPlanRunEntity> list = planId == null
                ? repo.findByProjectIdOrderByCreatedAtDesc(projectId)
                : repo.findByPlanIdOrderByCreatedAtDesc(planId);
        String st = blankToNull(status);
        String cc = blankToNull(conclusion);
        return list.stream()
                .filter(r -> st == null || st.equalsIgnoreCase(r.getStatus()))
                .filter(r -> cc == null || cc.equalsIgnoreCase(r.getConclusion()))
                .map(r -> toView(r, List.of())).toList();
    }

    public List<TestRunView> children(Long id) {
        require(id);
        return runService.childrenOfPlanRun(id);
    }

    /** 方案运行的最终用例结果（重试取最后一次；按「方案项 + 分片」去重，矩阵/分片各节点结果均保留）。 */
    public List<PlanCaseResult> caseResults(Long id) {
        List<TestRunEntity> children = runRepo.findByPlanRunIdOrderByIdAsc(id);
        Map<String, TestRunEntity> finalAttempts = new LinkedHashMap<>();
        for (TestRunEntity child : children) {
            String key = child.getPlanItemId() == null
                    ? "run:" + child.getId()
                    : child.getPlanItemId() + "|" + (child.getShardKey() == null ? "" : child.getShardKey());
            TestRunEntity prev = finalAttempts.get(key);
            if (prev == null || (child.getAttempt() == null ? 1 : child.getAttempt())
                    >= (prev.getAttempt() == null ? 1 : prev.getAttempt())) {
                finalAttempts.put(key, child);
            }
        }
        Map<Long, TestCaseEntity> cases = new LinkedHashMap<>();
        List<PlanCaseResult> out = new ArrayList<>();
        for (TestRunEntity child : finalAttempts.values()) {
            for (TestCaseResultEntity re : resultRepo.findByRunIdOrderBySortAsc(child.getId())) {
                TestCaseEntity c = re.getCaseId() == null ? null
                        : cases.computeIfAbsent(re.getCaseId(), cid -> caseRepo.findById(cid).orElse(null));
                out.add(new PlanCaseResult(re.getCaseId(), c == null ? null : c.getCaseKey(),
                        re.getName(), c == null ? null : c.getCaseVersion(), re.getStatus(),
                        re.getFailureCategory(), child.getId(), re.getNodeId() == null
                        ? child.getAgentNodeId() : re.getNodeId(), child.getShardKey(),
                        re.getDuration(), re.getError()));
            }
        }
        return out;
    }

    public String report(Long id) {
        TestPlanRunEntity pr = require(id);
        StringBuilder md = new StringBuilder();
        md.append("# 方案运行报告 #").append(pr.getId()).append("\n\n");
        md.append("- 方案: ").append(pr.getPlanName()).append(" · v").append(pr.getPlanVersion()).append("\n");
        md.append("- 项目: `").append(pr.getProjectId()).append("`\n");
        md.append("- 模式: ").append(pr.getMode()).append("\n");
        md.append("- 状态: **").append(pr.getStatus()).append("** · 结论 **").append(pr.getConclusion()).append("**\n");
        md.append("- 触发: ").append(pr.getTriggeredBy()).append("\n");
        md.append("- 时间: ").append(pr.getStartedAt()).append(" → ").append(pr.getFinishedAt()).append("\n");
        if (pr.getRequirementId() != null) {
            md.append("- 关联需求: `").append(pr.getRequirementId()).append("`\n");
        }
        Map<String, Object> summary = readObjectMap(pr.getSummaryJson());
        md.append("- 方案项: ").append(summary.getOrDefault("items", 0))
                .append(" · 通过 ").append(summary.getOrDefault("passed", 0))
                .append(" / 失败 ").append(summary.getOrDefault("failed", 0))
                .append(" / 阻塞 ").append(summary.getOrDefault("blocked", 0))
                .append(" / 跳过 ").append(summary.getOrDefault("skipped", 0)).append("\n");
        md.append("- 用例: ").append(summary.getOrDefault("total", 0))
                .append(" · 通过 ").append(summary.getOrDefault("casePassed", 0))
                .append(" / 失败 ").append(summary.getOrDefault("caseFailed", 0))
                .append(" / 阻塞 ").append(summary.getOrDefault("caseBlocked", 0))
                .append(" / 异常 ").append(summary.getOrDefault("caseErrors", 0))
                .append(" · 通过率 ").append(summary.getOrDefault("passRate", "—")).append("%\n");
        if (pr.getErrorSummary() != null && !pr.getErrorSummary().isBlank()) {
            md.append("- 说明: ").append(pr.getErrorSummary()).append("\n");
        }
        md.append("\n## 方案项\n\n");
        for (TestPlanRunView.ItemResult item : parseItemResults(pr.getItemResultsJson())) {
            md.append("- [").append(item.status()).append("] ").append(item.refName());
            if (item.shardKey() != null) {
                md.append(" · 节点 `").append(item.shardKey()).append("`");
            }
            if (item.runId() != null) {
                md.append(" · 子运行 #").append(item.runId());
            }
            if (item.attempts() != null && item.attempts() > 1) {
                md.append(" · 尝试 ").append(item.attempts()).append(" 次");
            }
            if (item.error() != null && !item.error().isBlank()) {
                md.append(" — ").append(cap(item.error(), 300));
            }
            md.append("\n");
        }
        md.append("\n## 失败用例\n\n");
        boolean any = false;
        for (TestRunEntity child : runRepo.findByPlanRunIdOrderByIdAsc(id)) {
            for (TestCaseResultEntity re : resultRepo.findByRunIdOrderBySortAsc(child.getId())) {
                if (!"fail".equals(re.getStatus()) && !"error".equals(re.getStatus())
                        && !"blocked".equals(re.getStatus())) {
                    continue;
                }
                any = true;
                md.append("- [").append(re.getStatus()).append("] ").append(re.getName())
                        .append(" · 子运行 #").append(child.getId());
                if (re.getNodeId() != null) {
                    md.append(" · 节点 `").append(re.getNodeId()).append("`");
                }
                if (re.getFailureCategory() != null) {
                    md.append(" · ").append(re.getFailureCategory());
                }
                if (re.getError() != null) {
                    md.append(" — ").append(cap(re.getError(), 300));
                }
                md.append("\n");
            }
        }
        if (!any) {
            md.append("（无）\n");
        }
        return md.toString();
    }

    public Map<String, Object> reportJson(Long id) {
        TestPlanRunEntity pr = require(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planRun", toView(pr, null));
        m.put("cases", caseResults(id));
        m.put("children", children(id).stream().map(c -> Map.of(
                "runId", c.id(), "status", c.status(), "conclusion", c.conclusion(),
                "nodeId", c.agentNodeId() == null ? "" : c.agentNodeId(),
                "shardKey", c.shardKey() == null ? "" : c.shardKey(),
                "attempt", c.attempt() == null ? 1 : c.attempt(),
                "summary", c.summary() == null ? Map.of() : c.summary())).toList());
        return m;
    }

    // ---------------- 视图 / 解析 ----------------

    public TestPlanRunView toView(TestPlanRunEntity pr, List<TestRunView> children) {
        List<TestRunView> childViews = children != null ? children : null;
        if (childViews == null) {
            childViews = runService.childrenOfPlanRun(pr.getId());
        }
        List<TestPlanRunView.ChildRun> childRuns = childViews.stream().map(c -> new TestPlanRunView.ChildRun(
                c.id(), c.status(), c.conclusion(), c.agentNodeId(), c.shardKey(), c.attempt(),
                c.planItemId(), c.summary(), c.errorSummary(), c.startedAt(), c.finishedAt())).toList();
        return new TestPlanRunView(pr.getId(), pr.getProjectId(), pr.getPlanId(), pr.getPlanName(),
                pr.getPlanVersion(), pr.getMode(), pr.getStatus(), pr.getConclusion(),
                readObjectMap(pr.getSummaryJson()), parseItemResults(pr.getItemResultsJson()), childRuns,
                pr.getErrorSummary(), pr.getRequirementId(), pr.getEnvironmentId(), pr.getBaseUrl(),
                pr.getTriggeredBy(), pr.getCancelRequested(), pr.getReportDocId(), pr.getStartedAt(),
                pr.getFinishedAt(), pr.getCreatedAt(), redactJson(pr.getPlanSnapshotJson()),
                redactJson(pr.getEnvSnapshotJson()));
    }

    private List<TestPlanRunView.ItemResult> parseItemResults(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, TestPlanRunView.ItemResult.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private PlanSnapshot parseSnapshot(String json) {
        try {
            return mapper.readValue(json, PlanSnapshot.class);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.CONFLICT, "方案运行快照损坏: " + rootMessage(e));
        }
    }

    // ---------------- 覆盖 / 重试 ----------------

    /** 方案项覆盖 + 方案级默认超时：方案项/用例自身 timeout 优先，方案级只补缺省。 */
    private List<PlanSnapshot.TestCaseSnapshot> applyOverrides(List<PlanSnapshot.TestCaseSnapshot> cases,
                                                               Map<String, Object> overrides,
                                                               Integer planTimeoutSec) {
        Integer timeout = overrides.get("timeoutSec") instanceof Number n ? n.intValue() : null;
        List<String> labels = overrides.get("labels") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : null;
        if (timeout == null && labels == null && planTimeoutSec == null) {
            return cases;
        }
        List<PlanSnapshot.TestCaseSnapshot> out = new ArrayList<>();
        for (PlanSnapshot.TestCaseSnapshot c : cases) {
            out.add(applyCaseOverrides(c, timeout, labels, planTimeoutSec));
        }
        return out;
    }

    private PlanSnapshot.TestCaseSnapshot applyCaseOverrides(PlanSnapshot.TestCaseSnapshot c,
                                                             Integer timeout, List<String> labels,
                                                             Integer planTimeoutSec) {
        if (c.specJson() == null || c.specJson().isBlank()) {
            return c;
        }
        try {
            CaseSpec spec = mapper.readValue(c.specJson(), CaseSpec.class);
            CaseSpec.ExecutorSpec ex = spec.executor();
            Integer caseTimeout = ex == null ? null : ex.timeoutSec();
            Integer effectiveTimeout = timeout != null ? timeout
                    : (caseTimeout == null ? planTimeoutSec : caseTimeout);
            CaseSpec.ExecutorSpec updated = new CaseSpec.ExecutorSpec(
                    ex == null ? null : ex.location(),
                    labels != null ? labels : (ex == null ? null : ex.labels()),
                    ex == null ? null : ex.nodeId(),
                    effectiveTimeout,
                    ex == null ? null : ex.retry(),
                    ex == null ? null : ex.env());
            CaseSpec newSpec = new CaseSpec(spec.caseKey(), spec.title(), spec.type(), spec.priority(),
                    spec.tags(), spec.preconditions(), spec.steps(), spec.assertions(), spec.cleanup(),
                    updated, spec.http(), spec.command(), spec.manual(), spec.source());
            return new PlanSnapshot.TestCaseSnapshot(c.id(), c.suiteId(), c.sort(), c.name(), c.kind(),
                    c.method(), c.path(), c.paramsJson(), c.headersJson(), c.bodyJson(), c.expectedJson(),
                    c.enabled(), c.caseKey(), c.type(), c.priority(), c.tags(), write(newSpec),
                    c.caseVersion(), c.reviewStatus(), c.sourceAssetId(), c.suiteCommit());
        } catch (Exception e) {
            log.warn("方案项覆盖应用失败（按原用例执行）: case={} err={}", c.id(), e.getMessage());
            return c;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> mergedParameters(Map<String, String> base, Map<String, Object> overrides) {
        Map<String, String> out = new LinkedHashMap<>(base == null ? Map.of() : base);
        for (String key : List.of("parameters", "env")) {
            Object v = overrides.get(key);
            if (v instanceof Map<?, ?> m) {
                ((Map<String, Object>) m).forEach((k, val) ->
                        out.put(String.valueOf(k), val == null ? "" : String.valueOf(val)));
            }
        }
        return out;
    }

    private String overrideNode(Map<String, Object> overrides) {
        Object v = overrides.get("agentNodeId");
        if (v == null || String.valueOf(v).isBlank()) {
            v = overrides.get("nodeId");
        }
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).strip();
    }

    private int retryMax(PlanSnapshot.PlanItemSnapshot item, Map<String, Object> overrides) {
        Object retry = overrides.get("retry");
        Integer max = retryMaxOf(retry);
        if (max != null) {
            return clampRetry(max);
        }
        if (TestPlanItemEntity.REF_CASE.equals(item.refType()) && !item.cases().isEmpty()) {
            String specJson = item.cases().get(0).specJson();
            if (specJson != null && !specJson.isBlank()) {
                try {
                    CaseSpec spec = mapper.readValue(specJson, CaseSpec.class);
                    if (spec.executor() != null && spec.executor().retry() != null) {
                        Integer m = spec.executor().retry().max();
                        if (m != null) {
                            return clampRetry(m);
                        }
                    }
                } catch (Exception ignored) {
                    // 解析失败按不重试
                }
            }
        }
        return 1;
    }

    private Integer retryMaxOf(Object retry) {
        if (retry instanceof Map<?, ?> m && m.get("max") instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private int clampRetry(int max) {
        return Math.max(1, Math.min(5, max));
    }

    private boolean shouldRetry(TestRunView child, Map<String, Object> overrides) {
        Object retryObj = overrides.get("retry");
        List<String> on = List.of("fail", "error");
        if (retryObj instanceof Map<?, ?> m && m.get("on") instanceof List<?> l && !l.isEmpty()) {
            on = l.stream().map(x -> String.valueOf(x).toLowerCase(Locale.ROOT)).toList();
        }
        for (com.devmind.test.dto.CaseResultView r : child.results()) {
            String status = r.status() == null ? "" : r.status().toLowerCase(Locale.ROOT);
            String category = r.failureCategory() == null ? "" : r.failureCategory().toLowerCase(Locale.ROOT);
            if (on.contains(status) || (status.equals("error") && category.equals("timeout") && on.contains("timeout"))) {
                return true;
            }
        }
        return false;
    }

    // ---------------- 报告 / 工具 ----------------

    private void createReportDocQuiet(TestPlanRunEntity pr) {
        try {
            DocDetail doc = documentService.create(new DocRequest("report", null, pr.getRequirementId(),
                    pr.getProjectId(), "方案运行报告 #" + pr.getId() + " " + pr.getPlanName(),
                    List.of("test-plan-report"), null, report(pr.getId())));
            pr.setReportDocId(doc.id());
            repo.save(pr);
        } catch (Exception e) {
            log.warn("方案运行报告文档创建失败: {}", e.getMessage());
        }
    }

    /** 僵尸方案运行收割：父记录卡在 RUNNING 超时（子运行各自有收割）。 */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${devmind.test.plan-stale-reaper-delay-ms:120000}",
            initialDelayString = "${devmind.test.plan-stale-reaper-initial-delay-ms:120000}")
    public void reapStale() {
        Instant before = Instant.now().minus(staleTimeout);
        for (TestPlanRunEntity pr : repo.findByStatusInAndCreatedAtBefore(
                List.of(TestPlanRunEntity.QUEUED, TestPlanRunEntity.RUNNING), before)) {
            pr.setStatus(TestPlanRunEntity.FAILED);
            pr.setConclusion("FAIL");
            pr.setErrorSummary("方案运行超时（" + staleTimeout.toMinutes() + " 分钟未出终态），系统判定僵尸并收割");
            pr.setFinishedAt(Instant.now());
            repo.save(pr);
            hub.done(topic(pr.getId()), pr.getStatus());
            log.warn("僵尸方案运行已收割: #{} plan={}", pr.getId(), pr.getPlanId());
        }
    }

    /** 只校验节点存在；在线性交给预检/执行期处理（FR-20：单节点失联不得阻断其他节点）。 */
    private void verifyNodeIds(List<String> nodeIds) {
        if (nodeIds.isEmpty()) {
            return;
        }
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
        }
        for (String nodeId : nodeIds) {
            if (!connector.nodeExists(nodeId)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "节点不存在: " + nodeId);
            }
        }
    }

    private String firstEnvNode(Long environmentId, String projectId) {
        if (environmentId == null) {
            return null;
        }
        List<String> nodes = environmentService.nodeIdsOf(
                environmentService.requireEnvironment(projectId, environmentId));
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    private String firstMatchingNode(List<String> labels, List<String> capabilities) {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            return null;
        }
        List<String> matched = connector.onlineNodesMatching(labels, capabilities);
        return matched.isEmpty() ? null : matched.get(0);
    }

    /**
     * 分布式节点解析（FR-19）：显式 nodeIds > 环境节点组 > 标签/能力匹配 > 平台默认。
     * 环境节点组按整组返回；指定标签/能力但无在线节点时直接报错，避免静默落到默认节点。
     */
    List<String> resolveDistributedNodes(List<String> explicitNodes, Long environmentId,
                                                 String projectId, List<String> labels,
                                                 List<String> capabilities) {        if (!explicitNodes.isEmpty()) {
            return explicitNodes;
        }
        if (environmentId != null) {
            List<String> envNodes = environmentService.nodeIdsOf(
                    environmentService.requireEnvironment(projectId, environmentId));
            if (!envNodes.isEmpty()) {
                return envNodes;
            }
        }
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
        }
        if (!labels.isEmpty() || !capabilities.isEmpty()) {
            List<String> matched = connector.onlineNodesMatching(labels, capabilities);
            if (matched.isEmpty()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "没有满足标签/能力要求的在线节点: labels=" + labels
                                + " capabilities=" + capabilities);
            }
            return matched;
        }
        String platformDefault = connector.defaultNodeId();
        if (platformDefault != null && !platformDefault.isBlank()) {
            return List.of(platformDefault);
        }
        throw new DevMindException(ErrorCode.BAD_REQUEST,
                "分布式模式没有可用节点：请指定 nodeIds、环境节点组，或配置标签/能力匹配的在线节点");
    }

    private String resolveBaseUrl(String explicit, Long environmentId, String projectId) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip();
        }
        if (environmentId == null) {
            return null;
        }
        EnvironmentEntity env = environmentService.requireEnvironment(projectId, environmentId);
        Map<String, String> vars = environmentService.variablesOf(env);
        String v = vars.getOrDefault("baseUrl", vars.get("BASE_URL"));
        return v == null || v.isBlank() ? null : v.strip();
    }

    private String mode(String raw) {
        String m = raw == null || raw.isBlank() ? TestPlanRunEntity.MODE_SEQUENTIAL
                : raw.strip().toUpperCase(Locale.ROOT);
        if (!TestPlanRunEntity.MODE_SEQUENTIAL.equals(m) && !TestPlanRunEntity.MODE_SHARD.equals(m)
                && !TestPlanRunEntity.MODE_MATRIX.equals(m)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "mode 只能是 SEQUENTIAL / SHARD / MATRIX");
        }
        return m;
    }

    private List<String> cleanList(List<String> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull)
                .map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    }

    private Map<String, String> mergeParams(Map<String, String> base, Map<String, String> override) {
        Map<String, String> out = new LinkedHashMap<>(base == null ? Map.of() : base);
        if (override != null) {
            override.forEach((k, v) -> {
                if (k != null && !k.isBlank()) {
                    out.put(k.strip(), v == null ? "" : v);
                }
            });
        }
        return out;
    }

    private Map<String, String> readStringMap(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        readObjectMap(json).forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
        return out;
    }

    /** 方案快照/环境快照对外返回前按敏感键脱敏；解析失败宁可返回空也不泄露原文。 */
    private String redactJson(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            Object value = mapper.readValue(json, Object.class);
            return mapper.writeValueAsString(redactValue(value));
        } catch (Exception e) {
            log.warn("快照脱敏失败，已隐藏原文: {}", e.getMessage());
            return null;
        }
    }

    private Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                out.put(key, SENSITIVE.matcher(key).matches() ? MASK : redactValue(v));
            });
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::redactValue).toList();
        }
        return value;
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

    private String write(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "JSON 序列化失败: " + e.getMessage());
        }
    }

    private String topic(Long planRunId) {
        return "plan-" + planRunId;
    }

    private String itemLabel(PlanSnapshot.PlanItemSnapshot item) {
        return item.refName() == null ? item.refType() + " " + item.refId() : item.refName();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }

    private static String cap(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    /** 方案项/分片终态；absorb 只记录最后一次 attempt 的汇总，避免重试重复计数。 */
    private static final class ItemOutcome {
        Long itemId;
        String refType;
        Long refId;
        Integer seq;
        String parallelGroup;
        String refName;
        String shardKey;
        String status = "SKIPPED";
        String conclusion = "SKIPPED";
        Long runId;
        int attempts;
        long durationMs;
        int caseCount;
        int passed;
        int failed;
        int blocked;
        int errors;
        String error;

        ItemOutcome(Long itemId, String refType, Long refId, Integer seq, String parallelGroup,
                    String refName, String shardKey) {
            this.itemId = itemId;
            this.refType = refType;
            this.refId = refId;
            this.seq = seq;
            this.parallelGroup = parallelGroup;
            this.refName = refName;
            this.shardKey = shardKey;
        }

        void absorb(TestRunView child, int attempt, int expectedCases) {
            this.runId = child.id();
            this.attempts = attempt;
            this.durationMs += child.startedAt() == null || child.finishedAt() == null ? 0
                    : Duration.between(child.startedAt(), child.finishedAt()).toMillis();
            this.conclusion = child.conclusion();
            this.status = switch (child.status() == null ? "" : child.status()) {
                case TestRunEntity.SUCCESS -> "PASS";
                case TestRunEntity.BLOCKED -> "BLOCKED";
                case "CANCELLED" -> "CANCELLED";
                default -> "FAIL";
            };
            this.error = child.errorSummary();
            RunSummary s = child.summary();
            if (s != null && s.total() > 0) {
                this.caseCount = s.total();
                this.passed = s.passed();
                this.failed = s.failed();
                this.blocked = s.blocked();
                this.errors = s.error();
            } else {
                // 子运行在产出用例结果前就失败（节点离线/预检异常）：按应执行数记入终态，
                // 避免父运行因 caseTotal=0 伪装 BLOCKED 或整体 PASS。
                this.caseCount = expectedCases;
                this.passed = 0;
                this.failed = 0;
                this.blocked = "BLOCKED".equals(this.status) ? expectedCases : 0;
                this.errors = "BLOCKED".equals(this.status) || "CANCELLED".equals(this.status)
                        ? 0 : expectedCases;
                if (this.error == null) {
                    this.error = "子运行 #" + child.id() + " 未产出用例结果（" + child.status() + "）";
                }
            }
        }

        TestPlanRunView.ItemResult toView() {
            return new TestPlanRunView.ItemResult(itemId, refType, refId, seq, parallelGroup, refName,
                    status, conclusion, runId, attempts, durationMs, shardKey, caseCount, passed, failed,
                    blocked, errors, error);
        }

        static ItemOutcome failed(Long itemId, String error) {
            ItemOutcome o = new ItemOutcome(itemId, null, null, null, null, null, null);
            o.status = "FAIL";
            o.conclusion = "FAIL";
            o.error = error;
            return o;
        }

        static ItemOutcome skipped(PlanSnapshot.PlanItemSnapshot item, String node,
                                  int seq, String reason) {
            ItemOutcome o = new ItemOutcome(item.itemId(), item.refType(), item.refId(), item.seq(),
                    item.parallelGroup(), item.refName(), node);
            o.status = "SKIPPED";
            o.conclusion = "SKIPPED";
            o.error = reason;
            return o;
        }
    }

    /** 预检结果 + 分布式节点级失败（只阻塞对应节点）与是否环境级阻断（整方案 BLOCKED）。 */
    private record PrecheckOutcome(PrecheckReport report, Map<String, String> nodeFailures,
                                   boolean blockAll) {
    }
}
