package com.devmind.test.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.audit.AuditService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.test.dto.EvidenceChainVerification;
import com.devmind.test.dto.TestPlanRunView;
import com.devmind.test.dto.TestRunView;
import com.devmind.test.model.TestCaseResultEntity;
import com.devmind.test.model.TestEvidenceEntity;
import com.devmind.test.model.TestPlanRunEntity;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.repo.TestCaseResultRepository;
import com.devmind.test.repo.TestEvidenceRepository;
import com.devmind.test.repo.TestPlanRunRepository;
import com.devmind.test.repo.TestRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * CAP-104 FR-15 证据包导出：单运行/方案运行 → zip（manifest + 报告 + 日志 + 断言 + 证据文件）。
 * manifest 带每条证据 sha256/prev_hash 与整条链校验结果；敏感参数键在导出时脱敏。
 */
@Service
public class EvidenceExportService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceExportService.class);
    private static final String FORMAT_VERSION = "1";
    private static final Pattern SENSITIVE = Pattern.compile("(?i).*(token|secret|password|passwd|credential|apikey|api_key).*");

    private final TestRunRepository runRepo;
    private final TestPlanRunRepository planRunRepo;
    private final TestCaseResultRepository resultRepo;
    private final TestEvidenceRepository evidenceRepo;
    private final EvidenceService evidenceService;
    private final TestRunService runService;
    private final TestPlanRunService planRunService;
    private final AuditService auditService;
    private final IdentityService identityService;
    private final ObjectMapper mapper;

    public EvidenceExportService(TestRunRepository runRepo, TestPlanRunRepository planRunRepo,
                                 TestCaseResultRepository resultRepo, TestEvidenceRepository evidenceRepo,
                                 EvidenceService evidenceService, TestRunService runService,
                                 TestPlanRunService planRunService, AuditService auditService,
                                 IdentityService identityService, ObjectMapper mapper) {
        this.runRepo = runRepo;
        this.planRunRepo = planRunRepo;
        this.resultRepo = resultRepo;
        this.evidenceRepo = evidenceRepo;
        this.evidenceService = evidenceService;
        this.runService = runService;
        this.planRunService = planRunService;
        this.auditService = auditService;
        this.identityService = identityService;
        this.mapper = mapper;
    }

    /** 导出单次运行（含方案子运行）。 */
    public byte[] exportRun(Long runId) {
        TestRunEntity run = runRepo.findById(runId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "测试运行不存在: " + runId));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("scope", "RUN");
        manifest.put("exportedAt", Instant.now().toString());
        manifest.put("exportedBy", identityService.currentActor());
        manifest.put("run", runMeta(run));
        manifest.put("results", resultsMeta(runId));
        List<TestEvidenceEntity> evidence = evidenceRepo.findByRunIdOrderBySeqNoAsc(runId);
        EvidenceChainVerification chain = evidenceService.verifyChain(runId);
        manifest.put("evidence", evidenceMeta(evidence));
        manifest.put("chains", List.of(chainMeta(chain)));
        manifest.put("redaction", "参数与环境变量中 token/secret/password/credential/apiKey 键已脱敏");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(zip, "manifest.json", writeBytes(manifest));
            put(zip, "report.md", runService.report(runId).getBytes(StandardCharsets.UTF_8));
            put(zip, "logs.txt", runService.logs(runId).getBytes(StandardCharsets.UTF_8));
            put(zip, "results.json", writeBytes(resultsMeta(runId)));
            putEvidence(zip, "evidence", evidence);
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "证据包生成失败: " + e.getMessage());
        }
        auditService.record("test", "evidence.export", identityService.currentActor(), run.getProjectId(), true,
                "运行 #" + runId + " 证据包（" + out.size() + " 字节）");
        return out.toByteArray();
    }

    /** 导出方案运行：父汇总 + 每个子运行独立目录 + 全量证据。 */
    public byte[] exportPlanRun(Long planRunId) {
        TestPlanRunEntity pr = planRunRepo.findById(planRunId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "方案运行不存在: " + planRunId));
        TestPlanRunView view = planRunService.get(planRunId);
        List<TestRunEntity> children = runRepo.findByPlanRunIdOrderByIdAsc(planRunId);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("scope", "PLAN_RUN");
        manifest.put("exportedAt", Instant.now().toString());
        manifest.put("exportedBy", identityService.currentActor());
        manifest.put("planRun", planRunMeta(view));
        manifest.put("planSnapshot", redact(readObject(pr.getPlanSnapshotJson())));
        manifest.put("environment", redact(readObject(pr.getEnvSnapshotJson())));
        manifest.put("precheck", readObject(pr.getPrecheckJson()));
        manifest.put("items", view.items());
        manifest.put("children", children.stream().map(this::runMeta).toList());
        List<Map<String, Object>> childResults = new ArrayList<>();
        List<Map<String, Object>> evidenceMeta = new ArrayList<>();
        List<Map<String, Object>> chains = new ArrayList<>();
        for (TestRunEntity child : children) {
            Map<String, Object> cr = new LinkedHashMap<>();
            cr.put("runId", child.getId());
            cr.put("nodeId", child.getAgentNodeId());
            cr.put("shardKey", child.getShardKey());
            cr.put("attempt", child.getAttempt());
            cr.put("results", resultsMeta(child.getId()));
            childResults.add(cr);
            List<TestEvidenceEntity> ev = evidenceRepo.findByRunIdOrderBySeqNoAsc(child.getId());
            evidenceMeta.addAll(evidenceMeta(child.getId(), ev));
            chains.add(chainMeta(evidenceService.verifyChain(child.getId())));
        }
        manifest.put("results", childResults);
        manifest.put("evidence", evidenceMeta);
        manifest.put("chains", chains);
        manifest.put("redaction", "参数与环境变量中 token/secret/password/credential/apiKey 键已脱敏");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(zip, "manifest.json", writeBytes(manifest));
            put(zip, "report.md", planRunService.report(planRunId).getBytes(StandardCharsets.UTF_8));
            put(zip, "report.json", writeBytes(planRunService.reportJson(planRunId)));
            for (TestRunEntity child : children) {
                String dir = "children/" + child.getId();
                put(zip, dir + "/report.md", runService.report(child.getId()).getBytes(StandardCharsets.UTF_8));
                put(zip, dir + "/logs.txt", runService.logs(child.getId()).getBytes(StandardCharsets.UTF_8));
                putEvidence(zip, "evidence/run-" + child.getId(),
                        evidenceRepo.findByRunIdOrderBySeqNoAsc(child.getId()));
            }
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "证据包生成失败: " + e.getMessage());
        }
        auditService.record("test", "evidence.export", identityService.currentActor(), pr.getProjectId(), true,
                "方案运行 #" + planRunId + " 证据包（" + out.size() + " 字节）");
        return out.toByteArray();
    }

    // ---------------- manifest ----------------

    private Map<String, Object> runMeta(TestRunEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("projectId", r.getProjectId());
        m.put("status", r.getStatus());
        m.put("conclusion", r.getConclusion());
        m.put("runScope", r.getRunScope());
        m.put("triggeredBy", r.getTriggeredBy());
        m.put("agentNodeId", r.getAgentNodeId());
        m.put("environmentId", r.getEnvironmentId());
        m.put("baseUrl", r.getBaseUrl());
        m.put("planRunId", r.getPlanRunId());
        m.put("planItemId", r.getPlanItemId());
        m.put("attempt", r.getAttempt());
        m.put("shardKey", r.getShardKey());
        m.put("requirementId", r.getRequirementId());
        m.put("workItemId", r.getWorkItemId());
        m.put("startedAt", r.getStartedAt());
        m.put("finishedAt", r.getFinishedAt());
        m.put("errorSummary", r.getErrorSummary());
        m.put("summary", readObject(r.getSummaryJson()));
        return m;
    }

    private Map<String, Object> planRunMeta(TestPlanRunView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("projectId", v.projectId());
        m.put("planId", v.planId());
        m.put("planName", v.planName());
        m.put("planVersion", v.planVersion());
        m.put("mode", v.mode());
        m.put("status", v.status());
        m.put("conclusion", v.conclusion());
        m.put("summary", v.summary());
        m.put("requirementId", v.requirementId());
        m.put("environmentId", v.environmentId());
        m.put("baseUrl", v.baseUrl());
        m.put("triggeredBy", v.triggeredBy());
        m.put("startedAt", v.startedAt());
        m.put("finishedAt", v.finishedAt());
        m.put("errorSummary", v.errorSummary());
        return m;
    }

    private List<Map<String, Object>> resultsMeta(Long runId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TestCaseResultEntity r : resultRepo.findByRunIdOrderBySortAsc(runId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("resultId", r.getId());
            m.put("caseId", r.getCaseId());
            m.put("suiteId", r.getSuiteId());
            m.put("name", r.getName());
            m.put("status", r.getStatus());
            m.put("failureCategory", r.getFailureCategory());
            m.put("durationMs", r.getDuration());
            m.put("nodeId", r.getNodeId());
            m.put("attempt", r.getAttempt());
            m.put("command", r.getCommand());
            m.put("workingDir", r.getWorkingDir());
            m.put("exitCode", r.getExitCode());
            m.put("error", r.getError());
            m.put("assertions", readArray(r.getAssertionJson()));
            m.put("environment", redact(readObject(r.getEnvSnapshotJson())));
            m.put("evidenceId", r.getEvidenceId());
            m.put("manualReview", r.getManualReview());
            m.put("manualNote", r.getManualNote());
            m.put("programStatus", r.getProgramStatus());
            m.put("programMessage", r.getProgramMessage());
            m.put("programMetrics", readObject(r.getProgramMetricsJson()));
            m.put("programArtifacts", readArray(r.getProgramArtifactsJson()));
            m.put("startedAt", r.getStartedAt());
            m.put("finishedAt", r.getFinishedAt());
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> evidenceMeta(List<TestEvidenceEntity> evidence) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TestEvidenceEntity e : evidence) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("runId", e.getRunId());
            m.put("resultId", e.getResultId());
            m.put("nodeId", e.getNodeId());
            m.put("kind", e.getKind());
            m.put("filename", e.getFilename());
            m.put("sha256", e.getSha256());
            m.put("prevHash", e.getPrevHash());
            m.put("sizeBytes", e.getSizeBytes());
            m.put("seqNo", e.getSeqNo());
            m.put("createdAt", e.getCreatedAt());
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> evidenceMeta(Long runId, List<TestEvidenceEntity> evidence) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> m : evidenceMeta(evidence)) {
            m.put("runId", runId);
            list.add(m);
        }
        return list;
    }

    private Map<String, Object> chainMeta(EvidenceChainVerification v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", v.valid());
        m.put("count", v.count());
        m.put("headHash", v.headHash());
        m.put("message", v.message());
        return m;
    }

    // ---------------- zip ----------------

    private void putEvidence(ZipOutputStream zip, String base, List<TestEvidenceEntity> evidence)
            throws IOException {
        for (TestEvidenceEntity e : evidence) {
            if (e.getId() == null) {
                continue;
            }
            try {
                EvidenceService.EvidenceFile file = evidenceService.read(e.getId());
                String seq = e.getSeqNo() == null ? "0" : String.valueOf(e.getSeqNo());
                put(zip, base + "/" + seq + "-" + safe(file.filename()), file.content());
            } catch (Exception ex) {
                // 文件缺失不阻断导出：manifest 已记录哈希与链校验结果，另写错误说明
                log.warn("证据文件导出失败: evidence={} err={}", e.getId(), ex.getMessage());
                put(zip, base + "/MISSING-" + e.getId() + ".txt",
                        ("证据文件缺失或哈希校验失败: " + ex.getMessage()).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private byte[] writeBytes(Object value) {
        try {
            return mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "证据包 JSON 序列化失败: " + e.getMessage());
        }
    }

    private Map<String, Object> readObject(String json) {
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

    private List<Object> readArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object v = mapper.readValue(json, Object.class);
            return v instanceof List ? (List<Object>) v : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 键名命中敏感词的值替换为掩码（递归）。 */
    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                out.put(key, SENSITIVE.matcher(key).matches() ? "******" : redact(v));
            });
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::redact).toList();
        }
        return value;
    }

    private String safe(String name) {
        String v = name == null || name.isBlank() ? "evidence.bin" : name.strip();
        v = v.replace('\\', '_').replace('/', '_');
        return v.contains("..") || v.length() > 180
                ? (v.substring(Math.max(0, v.length() - 40)).toLowerCase(Locale.ROOT)) : v;
    }
}
