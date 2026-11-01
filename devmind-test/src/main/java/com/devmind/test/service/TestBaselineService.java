package com.devmind.test.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.audit.AuditService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.test.dto.BaselineComparison;
import com.devmind.test.dto.PlanCaseResult;
import com.devmind.test.dto.TestBaselineView;
import com.devmind.test.model.TestBaselineEntity;
import com.devmind.test.model.TestPlanRunEntity;
import com.devmind.test.repo.TestBaselineRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-104 FR-16 基线：从方案运行固化用例版本/状态，供后续运行做回归对比。
 * 基线只追加、可删除；对比是查询视图，不回写历史运行。
 */
@Service
public class TestBaselineService {

    private final TestBaselineRepository repo;
    private final TestPlanRunService planRunService;
    private final ProjectService projectService;
    private final IdentityService identityService;
    private final AuditService auditService;
    private final ObjectMapper mapper;

    public TestBaselineService(TestBaselineRepository repo, TestPlanRunService planRunService,
                               ProjectService projectService, IdentityService identityService,
                               AuditService auditService, ObjectMapper mapper) {
        this.repo = repo;
        this.planRunService = planRunService;
        this.projectService = projectService;
        this.identityService = identityService;
        this.auditService = auditService;
        this.mapper = mapper;
    }

    public List<TestBaselineView> list(String projectId, Long planId) {
        projectService.requireProject(projectId);
        return repo.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(b -> planId == null || planId.equals(b.getPlanId()))
                .map(this::toView).toList();
    }

    public TestBaselineView get(Long id) {
        return toView(require(id));
    }

    public TestBaselineEntity require(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "基线不存在: " + id));
    }

    @Transactional
    public TestBaselineView create(Long planRunId, String name, String productVersion) {
        TestPlanRunEntity pr = planRunService.require(planRunId);
        if (TestPlanRunEntity.RUNNING.equals(pr.getStatus()) || TestPlanRunEntity.QUEUED.equals(pr.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "方案运行未结束，不能创建基线");
        }
        if (name == null || name.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "基线名称必填");
        }
        List<PlanCaseResult> cases = planRunService.caseResults(planRunId);
        if (cases.isEmpty()) {
            throw new DevMindException(ErrorCode.CONFLICT, "方案运行没有可固化的用例结果");
        }
        TestBaselineEntity b = new TestBaselineEntity();
        b.setProjectId(pr.getProjectId());
        b.setName(name.strip());
        b.setProductVersion(productVersion == null || productVersion.isBlank() ? null : productVersion.strip());
        b.setPlanId(pr.getPlanId());
        b.setPlanRunId(planRunId);
        b.setCaseVersionsJson(write(cases.stream().map(this::caseMeta).toList()));
        b.setSummaryJson(write(summaryOf(pr, cases)));
        b.setCreatedBy(identityService.currentActor());
        b.setCreatedAt(Instant.now());
        repo.save(b);
        auditService.record("test", "baseline.create", b.getCreatedBy(), b.getProjectId(), true,
                "基线 #" + b.getId() + " 来源方案运行 #" + planRunId);
        return toView(b);
    }

    @Transactional
    public void delete(Long id) {
        TestBaselineEntity b = require(id);
        repo.delete(b);
        auditService.record("test", "baseline.delete", identityService.currentActor(), b.getProjectId(), true,
                "基线 #" + id + " " + b.getName());
    }

    /** 基线 vs 指定方案运行：新增失败/已修复/持续失败/新增/移除用例 + 通过率与耗时变化。 */
    public BaselineComparison compare(Long baselineId, Long planRunId) {
        TestBaselineEntity b = require(baselineId);
        TestPlanRunEntity pr = planRunService.require(planRunId);
        if (!b.getProjectId().equals(pr.getProjectId())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "基线与方案运行不属于同一项目");
        }
        Map<String, Object> baselineSummary = readObject(b.getSummaryJson());
        Map<String, Object> currentSummary = readObject(pr.getSummaryJson());
        List<Map<String, Object>> baselineCases = readMapList(b.getCaseVersionsJson());
        List<PlanCaseResult> currentCases = planRunService.caseResults(planRunId);

        Map<String, Map<String, Object>> base = new LinkedHashMap<>();
        for (Map<String, Object> c : baselineCases) {
            base.put(key(c.get("caseId"), c.get("caseKey"), c.get("nodeId")), c);
        }
        Map<String, PlanCaseResult> cur = new LinkedHashMap<>();
        for (PlanCaseResult c : currentCases) {
            cur.put(key(c.caseId(), c.caseKey(), c.nodeId()), c);
        }

        List<BaselineComparison.CaseDiff> newFailures = new ArrayList<>();
        List<BaselineComparison.CaseDiff> fixed = new ArrayList<>();
        List<BaselineComparison.CaseDiff> persistent = new ArrayList<>();
        List<BaselineComparison.CaseDiff> newCases = new ArrayList<>();
        for (Map.Entry<String, PlanCaseResult> e : cur.entrySet()) {
            PlanCaseResult c = e.getValue();
            Map<String, Object> old = base.get(e.getKey());
            if (old == null) {
                newCases.add(diff(null, c));
                continue;
            }
            String before = str(old.get("status"));
            String now = c.status();
            if (passed(before) && failed(now)) {
                newFailures.add(diff(before, c));
            } else if (failed(before) && passed(now)) {
                fixed.add(diff(before, c));
            } else if (failed(before) && failed(now)) {
                persistent.add(diff(before, c));
            }
        }
        List<BaselineComparison.CaseDiff> removed = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : base.entrySet()) {
            if (!cur.containsKey(e.getKey())) {
                Map<String, Object> c = e.getValue();
                removed.add(new BaselineComparison.CaseDiff(num(c.get("caseId")), str(c.get("caseKey")),
                        str(c.get("name")), str(c.get("status")), null, intVal(c.get("caseVersion")), null,
                        num(c.get("runId")), null));
            }
        }

        double baselineRate = rate(base.values().stream().map(c -> str(c.get("status"))).toList());
        double currentRate = rate(currentCases.stream().map(PlanCaseResult::status).toList());
        long baselineDuration = base.values().stream().mapToLong(c -> longVal(c.get("durationMs"))).sum();
        long currentDuration = currentCases.stream().mapToLong(c -> c.durationMs() == null ? 0 : c.durationMs()).sum();
        return new BaselineComparison(b.getId(), planRunId, baselineSummary, currentSummary,
                newFailures, fixed, persistent, newCases, removed,
                Math.round((currentRate - baselineRate) * 10.0) / 10.0, currentDuration - baselineDuration);
    }

    // ---------------- 内部 ----------------

    private Map<String, Object> caseMeta(PlanCaseResult c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseId", c.caseId());
        m.put("caseKey", c.caseKey());
        m.put("caseVersion", c.version());
        m.put("name", c.name());
        m.put("status", c.status());
        m.put("failureCategory", c.failureCategory());
        m.put("runId", c.runId());
        m.put("nodeId", c.nodeId());
        m.put("shardKey", c.shardKey());
        m.put("durationMs", c.durationMs());
        return m;
    }

    private Map<String, Object> summaryOf(TestPlanRunEntity pr, List<PlanCaseResult> cases) {
        int passed = 0;
        int failed = 0;
        int error = 0;
        int blocked = 0;
        int skipped = 0;
        for (PlanCaseResult c : cases) {
            switch (c.status() == null ? "" : c.status().toLowerCase()) {
                case "pass" -> passed++;
                case "fail" -> failed++;
                case "error" -> error++;
                case "blocked" -> blocked++;
                default -> skipped++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", cases.size());
        m.put("passed", passed);
        m.put("failed", failed);
        m.put("error", error);
        m.put("blocked", blocked);
        m.put("skipped", skipped);
        m.put("passRate", denominator(passed, failed, error) == 0 ? null
                : Math.round(passed * 1000.0 / denominator(passed, failed, error)) / 10.0);
        m.put("conclusion", pr.getConclusion());
        m.put("planRunId", pr.getId());
        m.put("planVersion", pr.getPlanVersion());
        m.put("capturedAt", Instant.now().toString());
        return m;
    }

    private BaselineComparison.CaseDiff diff(String baselineStatus, PlanCaseResult c) {
        return new BaselineComparison.CaseDiff(c.caseId(), c.caseKey(), c.name(), baselineStatus, c.status(),
                null, c.version(), c.runId(), c.error());
    }

    private static boolean passed(String status) {
        return "pass".equalsIgnoreCase(status);
    }

    private static boolean failed(String status) {
        return "fail".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status);
    }

    private static String key(Object caseId, Object caseKey, Object nodeId) {
        String id = caseId == null ? "k:" + (caseKey == null ? "" : caseKey) : "id:" + caseId;
        return id + "|" + (nodeId == null ? "" : nodeId);
    }

    private static int denominator(int passed, int failed, int error) {
        return passed + failed + error;
    }

    private static double rate(List<String> statuses) {
        int passed = 0;
        int failed = 0;
        int error = 0;
        for (String s : statuses) {
            if (passed(s)) {
                passed++;
            } else if ("fail".equalsIgnoreCase(s)) {
                failed++;
            } else if ("error".equalsIgnoreCase(s)) {
                error++;
            }
        }
        int d = denominator(passed, failed, error);
        return d == 0 ? 0 : Math.round(passed * 1000.0 / d) / 10.0;
    }

    public TestBaselineView toView(TestBaselineEntity b) {
        List<Map<String, Object>> cases = readMapList(b.getCaseVersionsJson());
        return new TestBaselineView(b.getId(), b.getProjectId(), b.getName(), b.getProductVersion(),
                b.getPlanId(), b.getPlanRunId(), readObject(b.getSummaryJson()), cases.size(), cases,
                b.getCreatedBy(), b.getCreatedAt());
    }

    private String write(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "基线 JSON 序列化失败: " + e.getMessage());
        }
    }

    private Map<String, Object> readObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object v = mapper.readValue(json, Object.class);
            if (v instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, val) -> out.put(String.valueOf(k), val));
                return out;
            }
        } catch (Exception ignored) {
            // 损坏的历史 JSON 按空处理（对比仍可用用例状态）
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readMapList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object v = mapper.readValue(json, Object.class);
            if (v instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> map) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        map.forEach((k, val) -> m.put(String.valueOf(k), val));
                        out.add(m);
                    }
                }
                return out;
            }
        } catch (Exception ignored) {
            // 同上
        }
        return List.of();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Long num(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static Integer intVal(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static long longVal(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }
}
