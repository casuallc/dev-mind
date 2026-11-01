package com.devmind.test.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.audit.AuditService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.EnvironmentService;
import com.devmind.project.ProjectService;
import com.devmind.project.model.EnvironmentEntity;
import com.devmind.test.dto.CreatePlanRunRequest;
import com.devmind.test.dto.PlanSnapshot;
import com.devmind.test.dto.TestPlanRequest;
import com.devmind.test.dto.TestPlanView;
import com.devmind.test.model.TestCaseEntity;
import com.devmind.test.model.TestCaseVersionEntity;
import com.devmind.test.model.TestPlanEntity;
import com.devmind.test.model.TestPlanItemEntity;
import com.devmind.test.model.TestSuiteEntity;
import com.devmind.test.repo.TestCaseRepository;
import com.devmind.test.repo.TestCaseVersionRepository;
import com.devmind.test.repo.TestPlanItemRepository;
import com.devmind.test.repo.TestPlanRepository;
import com.devmind.test.repo.TestSuiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CAP-104 M2 测试方案：CRUD、模板复制、引用校验与运行快照构建。
 * 方案保存校验用例/套件为 APPROVED；废弃引用拒绝新增；快照在运行前固化，之后编辑方案不影响历史。
 */
@Service
public class TestPlanService {

    private static final Pattern SENSITIVE = Pattern.compile("(?i).*(token|secret|password|passwd|credential|apikey|api_key).*");
    private static final String MASK = "******";
    private static final int MAX_CONCURRENCY = 16;

    private final TestPlanRepository repo;
    private final TestPlanItemRepository itemRepo;
    private final TestCaseRepository caseRepo;
    private final TestCaseVersionRepository versionRepo;
    private final TestSuiteRepository suiteRepo;
    private final ProjectService projectService;
    private final EnvironmentService environmentService;
    private final IdentityService identityService;
    private final AuditService auditService;
    private final ObjectMapper mapper;

    public TestPlanService(TestPlanRepository repo, TestPlanItemRepository itemRepo,
                           TestCaseRepository caseRepo, TestCaseVersionRepository versionRepo,
                           TestSuiteRepository suiteRepo, ProjectService projectService,
                           EnvironmentService environmentService, IdentityService identityService,
                           AuditService auditService, ObjectMapper mapper) {
        this.repo = repo;
        this.itemRepo = itemRepo;
        this.caseRepo = caseRepo;
        this.versionRepo = versionRepo;
        this.suiteRepo = suiteRepo;
        this.projectService = projectService;
        this.environmentService = environmentService;
        this.identityService = identityService;
        this.auditService = auditService;
        this.mapper = mapper;
    }

    public List<TestPlanView> list(String projectId, Boolean templateOnly, String keyword) {
        projectService.requireProject(projectId);
        List<TestPlanEntity> plans = Boolean.TRUE.equals(templateOnly)
                ? repo.findByProjectIdAndIsTemplateOrderByUpdatedAtDesc(projectId, true)
                : repo.findByProjectIdOrderByUpdatedAtDesc(projectId);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.strip().toLowerCase(Locale.ROOT);
        return plans.stream()
                .filter(p -> kw == null || containsIgnoreCase(p.getName(), kw)
                        || containsIgnoreCase(p.getDescription(), kw))
                .map(this::toView).toList();
    }

    public TestPlanView get(Long id) {
        return toView(require(id));
    }

    public TestPlanEntity require(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "测试方案不存在: " + id));
    }

    @Transactional
    public TestPlanView create(String projectId, TestPlanRequest req) {
        projectService.requireProject(projectId);
        TestPlanEntity p = new TestPlanEntity();
        p.setProjectId(projectId);
        p.setPlanVersion(1);
        p.setCreatedBy(identityService.currentActor());
        p.setCreatedAt(Instant.now());
        apply(p, req);
        repo.save(p);
        saveItems(p.getId(), req.items(), List.of());
        auditService.record("test", "plan.create", identityService.currentActor(), projectId, true,
                "方案 #" + p.getId() + " " + p.getName());
        return toView(p);
    }

    @Transactional
    public TestPlanView update(Long id, TestPlanRequest req) {
        TestPlanEntity p = require(id);
        int version = p.getPlanVersion() == null ? 1 : p.getPlanVersion();
        p.setPlanVersion(version + 1);
        List<TestPlanItemEntity> previousItems = itemsOf(id);
        apply(p, req);
        repo.save(p);
        itemRepo.deleteByPlanId(id);
        saveItems(id, req.items(), previousItems);
        auditService.record("test", "plan.update", identityService.currentActor(), p.getProjectId(), true,
                "方案 #" + id + " v" + p.getPlanVersion());
        return toView(p);
    }

    /** 模板/方案复制：新方案不继承 isTemplate（除非请求显式指定），编辑互不影响。 */
    @Transactional
    public TestPlanView copy(Long id, TestPlanRequest req) {
        TestPlanEntity src = require(id);
        TestPlanRequest override = req == null ? null : req;
        TestPlanEntity p = new TestPlanEntity();
        p.setProjectId(src.getProjectId());
        p.setPlanVersion(1);
        p.setCreatedBy(identityService.currentActor());
        p.setCreatedAt(Instant.now());
        TestPlanRequest merged = new TestPlanRequest(
                override != null && notBlank(override.name()) ? override.name() : src.getName() + " 副本",
                override != null && override.description() != null ? override.description() : src.getDescription(),
                override != null && override.environmentId() != null ? override.environmentId() : src.getEnvironmentId(),
                override != null && notBlank(override.failStrategy()) ? override.failStrategy() : src.getFailStrategy(),
                override != null && override.concurrency() != null ? override.concurrency() : src.getConcurrency(),
                override != null && override.parameters() != null ? override.parameters() : readStringMap(src.getParametersJson()),
                override != null && override.requirementId() != null ? override.requirementId() : src.getRequirementId(),
                override != null && override.isTemplate() != null ? override.isTemplate() : false,
                override != null && override.items() != null && !override.items().isEmpty()
                        ? override.items() : itemsOf(src.getId()).stream().map(this::toInput).toList());
        apply(p, merged);
        repo.save(p);
        saveItems(p.getId(), merged.items(), List.of());
        auditService.record("test", "plan.copy", identityService.currentActor(), src.getProjectId(), true,
                "从方案 #" + id + " 复制为 #" + p.getId());
        return toView(p);
    }

    @Transactional
    public void delete(Long id) {
        TestPlanEntity p = require(id);
        itemRepo.deleteByPlanId(id);
        repo.delete(p);
        auditService.record("test", "plan.delete", identityService.currentActor(), p.getProjectId(), true,
                "方案 #" + id + " " + p.getName());
    }

    /**
     * 运行前固化快照；mode/nodeIds/parameters/失败策略/并发/超时来自触发请求（缺省取方案），环境/需求可覆盖。
     * 运行阶段允许引用已废弃用例（旧方案仍可运行），但拒绝从未评审通过的用例。
     */
    public PlanSnapshot buildSnapshot(TestPlanEntity plan, List<TestPlanItemEntity> items,
                                      String mode, List<String> nodeIds,
                                      String agentNodeId, Long environmentId, String baseUrl, String requirementId,
                                      Map<String, String> runParameters, String failStrategy,
                                      Integer concurrency, Integer timeoutSec, List<String> labels,
                                      List<String> capabilities) {
        List<PlanSnapshot.PlanItemSnapshot> snapshots = new ArrayList<>();
        for (TestPlanItemEntity item : items) {
            if (!Boolean.TRUE.equals(item.getEnabled())) {
                continue;
            }
            List<PlanSnapshot.TestCaseSnapshot> cases = resolveCases(item, false);
            if (cases.isEmpty()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "方案项 #" + item.getId() + "（" + item.getRefType() + " " + item.getRefId()
                                + "）没有可执行用例");
            }
            snapshots.add(new PlanSnapshot.PlanItemSnapshot(item.getId(), item.getRefType(), item.getRefId(),
                    item.getRefVersion(), item.getSeq(), blankToNull(item.getParallelGroup()),
                    refName(item), readObjectMap(item.getOverridesJson()), cases));
        }
        if (snapshots.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "方案没有启用的方案项");
        }
        String strategy = failStrategy == null || failStrategy.isBlank()
                ? defaultStrategy(plan.getFailStrategy()) : normalizeStrategy(failStrategy);
        int parallel = concurrency == null ? defaultConcurrency(plan.getConcurrency()) : concurrency;
        if (parallel < 1 || parallel > MAX_CONCURRENCY) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "concurrency 需在 1~" + MAX_CONCURRENCY + " 之间");
        }
        if (timeoutSec != null && timeoutSec <= 0) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "timeoutSec 必须大于 0");
        }
        return new PlanSnapshot(PlanSnapshot.CURRENT_VERSION, plan.getId(), plan.getName(),
                plan.getPlanVersion(), strategy, parallel, timeoutSec,
                cleanList(labels), cleanList(capabilities),
                environmentId, requirementId, mode, nodeIds == null ? List.of() : nodeIds,
                agentNodeId, baseUrl, runParameters == null ? Map.of() : runParameters, Instant.now(), snapshots);
    }

    /** 环境快照（变量脱敏）：运行证据/报告用，不影响执行。 */
    public Map<String, Object> environmentSnapshot(Long environmentId, String projectId, String baseUrl) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("environmentId", environmentId);
        snap.put("baseUrl", baseUrl);
        if (environmentId == null) {
            return snap;
        }
        EnvironmentEntity env = environmentService.requireEnvironment(projectId, environmentId);
        snap.put("name", env.getName());
        snap.put("productVersion", env.getProductVersion());
        List<String> nodeIds = environmentService.nodeIdsOf(env);
        snap.put("nodeIds", nodeIds);
        Map<String, String> masked = new LinkedHashMap<>();
        environmentService.variablesOf(env).forEach((k, v) ->
                masked.put(k, k != null && SENSITIVE.matcher(k).matches() ? "******" : v));
        snap.put("variables", masked);
        snap.put("capturedAt", Instant.now().toString());
        return snap;
    }

    // ---------------- 内部 ----------------

    private void apply(TestPlanEntity p, TestPlanRequest req) {
        if (req == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "方案内容必填");
        }
        if (!notBlank(req.name())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "方案名称必填");
        }
        String strategy = normalize(req.failStrategy());
        if (strategy != null && !TestPlanEntity.FAIL_FAST.equals(strategy)
                && !TestPlanEntity.CONTINUE.equals(strategy)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "failStrategy 只能是 FAIL_FAST / CONTINUE");
        }
        int concurrency = req.concurrency() == null ? 1 : req.concurrency();
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "concurrency 需在 1~" + MAX_CONCURRENCY + " 之间");
        }
        if (req.environmentId() != null) {
            environmentService.requireEnvironment(p.getProjectId(), req.environmentId());
        }
        p.setName(req.name().strip());
        p.setDescription(req.description());
        p.setEnvironmentId(req.environmentId());
        p.setFailStrategy(strategy == null ? TestPlanEntity.CONTINUE : strategy);
        p.setConcurrency(concurrency);
        p.setParametersJson(write(unmaskParameters(p.getParametersJson(), req.parameters())));
        p.setRequirementId(blankToNull(req.requirementId()));
        p.setIsTemplate(Boolean.TRUE.equals(req.isTemplate()));
        p.setUpdatedAt(Instant.now());
    }

    private void saveItems(Long planId, List<TestPlanRequest.TestPlanItemInput> inputs,
                           List<TestPlanItemEntity> previousItems) {
        if (inputs == null || inputs.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "方案至少包含 1 个方案项");
        }
        Map<String, Map<String, Object>> previousOverrides = new LinkedHashMap<>();
        for (TestPlanItemEntity prev : previousItems) {
            previousOverrides.putIfAbsent(itemKey(prev.getRefType(), prev.getRefId(), prev.getRefVersion()),
                    readObjectMap(prev.getOverridesJson()));
        }
        int order = 1;
        for (TestPlanRequest.TestPlanItemInput in : inputs) {
            if (in == null || in.refId() == null) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "方案项引用不能为空");
            }
            String refType = normalize(in.refType());
            if (!TestPlanItemEntity.REF_CASE.equals(refType) && !TestPlanItemEntity.REF_SUITE.equals(refType)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "refType 只能是 CASE / SUITE");
            }
            Map<String, Object> overrides = in.overrides() == null ? Map.of()
                    : unmaskOverrides(in.overrides(),
                    previousOverrides.getOrDefault(itemKey(refType, in.refId(), in.refVersion()), Map.of()));
            validateOverrides(overrides);
            if (TestPlanItemEntity.REF_CASE.equals(refType)) {
                requireApprovedCase(in.refId(), in.refVersion(), true);
            } else {
                requireRunnableSuite(in.refId(), true);
            }
            TestPlanItemEntity e = new TestPlanItemEntity();
            e.setPlanId(planId);
            e.setRefType(refType);
            e.setRefId(in.refId());
            e.setRefVersion(in.refVersion());
            e.setSeq(in.seq() == null || in.seq() <= 0 ? order : in.seq());
            e.setParallelGroup(blankToNull(in.parallelGroup()));
            e.setOverridesJson(overrides.isEmpty() ? null : write(overrides));
            e.setEnabled(in.enabled() == null || in.enabled());
            itemRepo.save(e);
            order++;
        }
    }

    private void validateOverrides(Map<String, Object> overrides) {
        for (String key : overrides.keySet()) {
            if (!List.of("parameters", "env", "agentNodeId", "nodeId", "labels", "timeoutSec", "retry").contains(key)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "方案项 overrides 不支持的键: " + key);
            }
        }
        Object timeout = overrides.get("timeoutSec");
        if (timeout instanceof Number n && n.intValue() <= 0) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "overrides.timeoutSec 必须大于 0");
        }
        Object retry = overrides.get("retry");
        if (retry instanceof Map<?, ?> m) {
            Object max = m.get("max");
            if (max instanceof Number n && (n.intValue() < 1 || n.intValue() > 5)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "overrides.retry.max 需在 1~5 之间");
            }
        }
    }

    /**
     * @param strict true = 保存方案时（废弃用例拒绝新增）；false = 运行快照（旧方案仍可运行已废弃用例，
     *               但仍拒绝从未评审通过的用例）
     */
    private TestCaseEntity requireApprovedCase(Long caseId, Integer refVersion, boolean strict) {
        TestCaseEntity c = caseRepo.findById(caseId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "用例不存在: " + caseId));
        boolean approved = TestCaseVersionEntity.APPROVED.equals(c.getReviewStatus());
        boolean deprecated = TestCaseVersionEntity.DEPRECATED.equals(c.getReviewStatus());
        if (strict && !approved) {
            throw new DevMindException(ErrorCode.CONFLICT, deprecated
                    ? "用例已废弃，不可加入方案: " + caseId
                    : "用例未评审通过，不可加入方案: " + caseId);
        }
        if (!strict && !approved && !deprecated) {
            throw new DevMindException(ErrorCode.CONFLICT, "用例未评审通过，不可加入方案: " + caseId);
        }
        if (!Boolean.TRUE.equals(c.getEnabled())) {
            throw new DevMindException(ErrorCode.CONFLICT, "用例已禁用，不可加入方案: " + caseId);
        }
        if (refVersion != null) {
            TestCaseVersionEntity v = versionRepo.findByCaseIdAndVersionNo(caseId, refVersion)
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                            "用例版本不存在: " + caseId + " v" + refVersion));
            boolean versionOk = TestCaseVersionEntity.APPROVED.equals(v.getReviewStatus())
                    || (!strict && TestCaseVersionEntity.DEPRECATED.equals(v.getReviewStatus()));
            if (!versionOk) {
                throw new DevMindException(ErrorCode.CONFLICT,
                        "用例版本未评审通过: " + caseId + " v" + refVersion);
            }
        }
        return c;
    }

    private TestSuiteEntity requireRunnableSuite(Long suiteId, boolean strict) {
        TestSuiteEntity s = suiteRepo.findById(suiteId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "测试套件不存在: " + suiteId));
        if ("script".equals(s.getKind())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "脚本套件不支持加入方案（请用例库/方案只编排可结构化执行的用例）: " + suiteId);
        }
        if (strict && TestSuiteEntity.DEPRECATED.equals(s.getReviewStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "套件已废弃，不可加入方案: " + suiteId);
        }
        if (strict && s.getSourceAssetId() != null && !TestSuiteEntity.APPROVED.equals(s.getReviewStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "套件来源于资产且未评审通过，不可加入方案: " + suiteId);
        }
        return s;
    }

    private List<PlanSnapshot.TestCaseSnapshot> resolveCases(TestPlanItemEntity item, boolean strict) {
        if (TestPlanItemEntity.REF_CASE.equals(item.getRefType())) {
            TestCaseEntity c = requireApprovedCase(item.getRefId(), item.getRefVersion(), strict);
            TestSuiteEntity suite = suiteRepo.findById(c.getSuiteId()).orElse(null);
            String spec = c.getSpecJson();
            Integer version = c.getCaseVersion();
            if (item.getRefVersion() != null) {
                TestCaseVersionEntity v = versionRepo
                        .findByCaseIdAndVersionNo(c.getId(), item.getRefVersion()).orElse(null);
                if (v != null) {
                    spec = v.getSpecJson();
                    version = v.getVersionNo();
                }
            }
            return List.of(new PlanSnapshot.TestCaseSnapshot(c.getId(), c.getSuiteId(), c.getSort(), c.getName(),
                    c.getKind(), c.getMethod(), c.getPath(), c.getParamsJson(), c.getHeadersJson(), c.getBodyJson(),
                    c.getExpectedJson(), c.getEnabled(), c.getCaseKey(), c.getType(), c.getPriority(), c.getTags(),
                    spec, version, c.getReviewStatus(), c.getSourceAssetId(),
                    suite == null ? null : suite.getCommitHash()));
        }
        TestSuiteEntity suite = requireRunnableSuite(item.getRefId(), strict);
        List<PlanSnapshot.TestCaseSnapshot> cases = new ArrayList<>();
        for (TestCaseEntity c : caseRepo.findBySuiteIdOrderBySortAsc(suite.getId())) {
            if (!Boolean.TRUE.equals(c.getEnabled())) {
                continue;
            }
            boolean approved = TestCaseVersionEntity.APPROVED.equals(c.getReviewStatus());
            boolean deprecated = TestCaseVersionEntity.DEPRECATED.equals(c.getReviewStatus());
            if (strict ? !approved : !approved && !deprecated) {
                throw new DevMindException(ErrorCode.CONFLICT,
                        "套件 " + suite.getId() + " 内用例 " + c.getId() + " 未评审通过，不可运行方案");
            }
            cases.add(PlanSnapshot.TestCaseSnapshot.of(c, suite.getCommitHash()));
        }
        return cases;
    }

    private String refName(TestPlanItemEntity item) {
        if (TestPlanItemEntity.REF_CASE.equals(item.getRefType())) {
            return caseRepo.findById(item.getRefId()).map(TestCaseEntity::getName).orElse(null);
        }
        return suiteRepo.findById(item.getRefId()).map(TestSuiteEntity::getName).orElse(null);
    }

    private List<TestPlanItemEntity> itemsOf(Long planId) {
        return itemRepo.findByPlanIdOrderBySeqAsc(planId);
    }

    public TestPlanView toView(TestPlanEntity p) {
        List<TestPlanView.Item> items = itemsOf(p.getId()).stream().map(i -> {
            String reviewStatus = null;
            Integer caseCount = null;
            if (TestPlanItemEntity.REF_CASE.equals(i.getRefType())) {
                TestCaseEntity c = caseRepo.findById(i.getRefId()).orElse(null);
                reviewStatus = c == null ? null : c.getReviewStatus();
                caseCount = 1;
            } else {
                TestSuiteEntity s = suiteRepo.findById(i.getRefId()).orElse(null);
                reviewStatus = s == null ? null : s.getReviewStatus();
                caseCount = caseRepo.findBySuiteIdOrderBySortAsc(i.getRefId()).size();
            }
            return new TestPlanView.Item(i.getId(), i.getRefType(), i.getRefId(), i.getRefVersion(), i.getSeq(),
                    i.getParallelGroup(), redactMap(readObjectMap(i.getOverridesJson())), i.getEnabled(),
                    refName(i), reviewStatus, caseCount);
        }).toList();
        Map<String, String> parameters = new LinkedHashMap<>();
        readStringMap(p.getParametersJson()).forEach((k, v) ->
                parameters.put(k, isSensitiveKey(k) ? MASK : v));
        return new TestPlanView(p.getId(), p.getProjectId(), p.getName(), p.getDescription(),
                p.getEnvironmentId(), p.getFailStrategy(), p.getConcurrency(),
                parameters, p.getRequirementId(), p.getIsTemplate(),
                p.getPlanVersion(), p.getCreatedBy(), p.getCreatedAt(), p.getUpdatedAt(), items);
    }

    private TestPlanRequest.TestPlanItemInput toInput(TestPlanItemEntity i) {
        return new TestPlanRequest.TestPlanItemInput(i.getRefType(), i.getRefId(), i.getRefVersion(),
                i.getSeq(), i.getParallelGroup(), readObjectMap(i.getOverridesJson()), i.getEnabled());
    }

    // ---------- JSON ----------

    private String write(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "方案 JSON 序列化失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObjectMap(String json) {
        if (!notBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Object v = mapper.readValue(json, Object.class);
            return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, String> readStringMap(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        readObjectMap(json).forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
        return out;
    }

    private static String itemKey(String refType, Long refId, Integer refVersion) {
        return refType + ":" + refId + ":" + (refVersion == null ? 0 : refVersion);
    }

    private static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE.matcher(key).matches();
    }

    private static boolean isMasked(Object value) {
        return MASK.equals(value instanceof String s ? s.strip() : null);
    }

    /** 视图里的敏感值回传为掩码；保存时按同键旧值还原，避免掩码被写成真实参数。 */
    private Map<String, String> unmaskParameters(String storedJson, Map<String, String> incoming) {
        Map<String, String> out = new LinkedHashMap<>();
        if (incoming == null) {
            return out;
        }
        Map<String, String> stored = readStringMap(storedJson);
        incoming.forEach((key, value) -> {
            if (isMasked(value) && isSensitiveKey(key)) {
                if (!stored.containsKey(key)) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST,
                            "敏感参数 " + key + " 不能提交脱敏占位值，请填写真实值");
                }
                out.put(key, stored.get(key));
            } else {
                out.put(key, value);
            }
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unmaskOverrides(Map<String, Object> incoming, Map<String, Object> stored) {
        Object merged = unmaskValue(null, incoming, stored);
        return merged instanceof Map ? (Map<String, Object>) merged : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Object unmaskValue(String key, Object value, Object storedValue) {
        if (value instanceof Map<?, ?> map) {
            Map<?, ?> old = storedValue instanceof Map<?, ?> m ? m : Map.of();
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String name = String.valueOf(k);
                out.put(name, unmaskValue(name, v, old.get(name)));
            });
            return out;
        }
        if (value instanceof List<?> list) {
            List<?> old = storedValue instanceof List<?> l ? l : List.of();
            List<Object> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                out.add(unmaskValue(key, list.get(i), i < old.size() ? old.get(i) : null));
            }
            return out;
        }
        if (isSensitiveKey(key) && isMasked(value)) {
            if (storedValue == null || isMasked(storedValue)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "敏感字段 " + key + " 不能提交脱敏占位值，请填写真实值");
            }
            return storedValue;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> redactMap(Map<String, Object> value) {
        Object redacted = redactValue(null, value);
        return redacted instanceof Map ? (Map<String, Object>) redacted : new LinkedHashMap<>();
    }

    /** 敏感键（含方案参数/方案项 env、parameters 等嵌套结构）的值统一替换为掩码。 */
    private Object redactValue(String key, Object value) {
        if (isSensitiveKey(key)) {
            return MASK;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String name = String.valueOf(k);
                out.put(name, redactValue(name, v));
            });
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> redactValue(key, v)).toList();
        }
        return value;
    }

    private static String defaultStrategy(String raw) {
        return TestPlanEntity.FAIL_FAST.equalsIgnoreCase(raw == null ? "" : raw)
                ? TestPlanEntity.FAIL_FAST : TestPlanEntity.CONTINUE;
    }

    private static String normalizeStrategy(String raw) {
        String s = normalize(raw);
        if (!TestPlanEntity.FAIL_FAST.equals(s) && !TestPlanEntity.CONTINUE.equals(s)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "failStrategy 只能是 FAIL_FAST / CONTINUE");
        }
        return s;
    }

    private static int defaultConcurrency(Integer raw) {
        return raw == null ? 1 : Math.max(1, Math.min(MAX_CONCURRENCY, raw));
    }

    private static List<String> cleanList(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s.strip() : null;
    }

    private static String normalize(String s) {
        return notBlank(s) ? s.strip().toUpperCase(Locale.ROOT) : null;
    }

    private static boolean containsIgnoreCase(String value, String lowerKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }
}
