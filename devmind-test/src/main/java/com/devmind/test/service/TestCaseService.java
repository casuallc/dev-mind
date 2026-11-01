package com.devmind.test.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.audit.AuditService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.program.ProgramContract;
import com.devmind.project.ProjectService;
import com.devmind.test.dto.CaseReviewRequest;
import com.devmind.test.dto.CaseSaveResult;
import com.devmind.test.dto.TestCaseSaveRequest;
import com.devmind.test.dto.TestCaseVersionView;
import com.devmind.test.dto.TestCaseView;
import com.devmind.test.model.TestAssetEntity;
import com.devmind.test.model.TestCaseEntity;
import com.devmind.test.model.TestCaseVersionEntity;
import com.devmind.test.model.TestSuiteEntity;
import com.devmind.test.repo.TestAssetRepository;
import com.devmind.test.repo.TestCaseRepository;
import com.devmind.test.repo.TestCaseVersionRepository;
import com.devmind.test.repo.TestSuiteRepository;
import com.devmind.test.spec.AssertionSpec;
import com.devmind.test.spec.CaseSignatures;
import com.devmind.test.spec.CaseSpec;
import com.devmind.test.spec.CaseSpecValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * CAP-104 用例资产服务：CaseSpec 写入、不可变版本、评审/废弃、检索与重复提示。
 * 审批后的用例才允许进入方案（M2）；单次运行仍允许手工触发草稿用例做验证。
 */
@Service
public class TestCaseService {

    private static final Logger log = LoggerFactory.getLogger(TestCaseService.class);
    private static final String REVIEW_APPROVED = TestCaseVersionEntity.APPROVED;
    private static final String REVIEW_DEPRECATED = TestCaseVersionEntity.DEPRECATED;

    private final TestCaseRepository caseRepo;
    private final TestCaseVersionRepository versionRepo;
    private final TestSuiteRepository suiteRepo;
    private final TestAssetRepository assetRepo;
    private final ProjectService projectService;
    private final IdentityService identityService;
    private final ObjectMapper mapper;
    private final AuditService auditService;

    public TestCaseService(TestCaseRepository caseRepo, TestCaseVersionRepository versionRepo,
                           TestSuiteRepository suiteRepo, TestAssetRepository assetRepo,
                           ProjectService projectService, IdentityService identityService,
                           ObjectMapper mapper, AuditService auditService) {
        this.caseRepo = caseRepo;
        this.versionRepo = versionRepo;
        this.suiteRepo = suiteRepo;
        this.assetRepo = assetRepo;
        this.projectService = projectService;
        this.identityService = identityService;
        this.mapper = mapper;
        this.auditService = auditService;
    }

    public List<TestCaseView> search(String projectId, Long suiteId, Long moduleId, String type,
                                     String reviewStatus, String keyword, String tags) {
        projectService.requireProject(projectId);
        return caseRepo.search(projectId.trim(), suiteId, moduleId, normalizeOrNull(type),
                        normalizeOrNull(reviewStatus), blankToNull(keyword), blankToNull(tags))
                .stream().map(this::toView).toList();
    }

    public TestCaseView get(Long id) {
        return toView(require(id));
    }

    @Transactional
    public CaseSaveResult create(Long suiteId, TestCaseSaveRequest req) {
        TestSuiteEntity suite = requireSuite(suiteId);
        String projectId = suite.getProjectId();
        if (projectId == null || projectId.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "脚本套件不支持用例编排");
        }
        CaseSpec spec = requireSpec(req);
        String caseKey = blankToNull(spec.caseKey());
        if (caseKey == null) {
            caseKey = "CASE-" + suiteId + "-" + Long.toString(System.nanoTime(), 36);
            spec = withCaseKey(spec, caseKey);
        }
        ensureCaseKeyFree(projectId, caseKey, null);
        checkSourceAsset(projectId, spec);

        TestCaseEntity e = new TestCaseEntity();
        e.setSuiteId(suiteId);
        e.setSort(nextSort(suiteId));
        e.setCaseKey(caseKey);
        e.setCaseVersion(1);
        e.setReviewStatus(TestCaseVersionEntity.DRAFT);
        e.setEnabled(true);
        e.setUpdatedAt(Instant.now());
        applySpec(e, spec);
        caseRepo.save(e);
        saveVersion(e, spec, TestCaseVersionEntity.DRAFT, changeNote(req), identityService.currentActor());
        log.info("CAP-104 新建用例: id={} key={} suite={}", e.getId(), caseKey, suiteId);
        auditService.record("test", "case.create", identityService.currentActor(), projectId, true,
                "用例 #" + e.getId() + " " + caseKey + " suite=" + suiteId);
        return new CaseSaveResult(toView(e), duplicates(projectId, e.getSignature(), e.getId()));
    }

    @Transactional
    public CaseSaveResult update(Long id, TestCaseSaveRequest req) {
        TestCaseEntity e = require(id);
        TestSuiteEntity suite = requireSuite(e.getSuiteId());
        String projectId = suite.getProjectId();
        CaseSpec spec = requireSpec(req);
        String caseKey = blankToNull(spec.caseKey());
        if (caseKey == null) {
            caseKey = e.getCaseKey();
            spec = withCaseKey(spec, caseKey);
        } else if (!caseKey.equals(e.getCaseKey())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "caseKey 创建后不可修改");
        }
        ensureCaseKeyFree(projectId, caseKey, id);
        checkSourceAsset(projectId, spec);

        int version = e.getCaseVersion() == null ? 1 : e.getCaseVersion();
        boolean approved = REVIEW_APPROVED.equals(e.getReviewStatus());
        boolean deprecated = REVIEW_DEPRECATED.equals(e.getReviewStatus());
        if (approved || deprecated) {
            version++;
        }
        e.setCaseVersion(version);
        e.setReviewStatus(TestCaseVersionEntity.DRAFT);
        e.setUpdatedAt(Instant.now());
        applySpec(e, spec);
        caseRepo.save(e);

        TestCaseVersionEntity v = versionRepo.findByCaseIdAndVersionNo(id, version).orElse(null);
        if (v == null) {
            saveVersion(e, spec, TestCaseVersionEntity.DRAFT, changeNote(req), identityService.currentActor());
        } else {
            v.setSpecJson(write(spec));
            v.setReviewStatus(TestCaseVersionEntity.DRAFT);
            v.setReviewedBy(null);
            v.setReviewedAt(null);
            versionRepo.save(v);
        }
        auditService.record("test", "case.update", identityService.currentActor(), projectId, true,
                "用例 #" + id + " v" + version + " " + caseKey);
        return new CaseSaveResult(toView(e), duplicates(projectId, e.getSignature(), e.getId()));
    }

    @Transactional
    public TestCaseView review(Long id, CaseReviewRequest req) {
        TestCaseEntity e = require(id);
        String status = normalizeOrNull(req == null ? null : req.reviewStatus());
        if (!REVIEW_APPROVED.equals(status) && !REVIEW_DEPRECATED.equals(status)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "评审状态只能是 APPROVED / DEPRECATED");
        }
        TestCaseVersionEntity v = versionRepo
                .findByCaseIdAndVersionNo(id, e.getCaseVersion() == null ? 1 : e.getCaseVersion())
                .orElseGet(() -> saveVersion(e, parse(e.getSpecJson()), TestCaseVersionEntity.DRAFT,
                        "评审兼容补建", identityService.currentActor()));
        v.setReviewStatus(status);
        v.setReviewedBy(identityService.currentActor());
        v.setReviewedAt(Instant.now());
        if (req.note() != null && !req.note().isBlank()) {
            v.setChangeNote(truncate(req.note().strip(), 512));
        }
        versionRepo.save(v);
        e.setReviewStatus(status);
        e.setUpdatedAt(Instant.now());
        caseRepo.save(e);
        auditService.record("test", "case.review", identityService.currentActor(), projectIdOf(e),
                REVIEW_APPROVED.equals(status), "用例 #" + id + " → " + status);
        return toView(e);
    }

    public List<TestCaseVersionView> versions(Long caseId) {
        require(caseId);
        return versionRepo.findByCaseIdOrderByVersionNoDesc(caseId).stream().map(v ->
                new TestCaseVersionView(v.getId(), v.getCaseId(), v.getVersionNo(), v.getReviewStatus(),
                        v.getChangeNote(), v.getCreatedBy(), v.getCreatedAt(), v.getReviewedBy(),
                        v.getReviewedAt(), parse(v.getSpecJson()))).toList();
    }

    @Transactional
    public void delete(Long id) {
        TestCaseEntity e = require(id);
        String projectId = projectIdOf(e);
        String caseKey = e.getCaseKey();
        versionRepo.deleteByCaseId(id);
        caseRepo.delete(e);
        auditService.record("test", "case.delete", identityService.currentActor(), projectId, true,
                "用例 #" + id + " " + caseKey);
    }

    public TestCaseEntity require(Long id) {
        return caseRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "用例不存在: " + id));
    }

    public TestCaseView toView(TestCaseEntity c) {
        return new TestCaseView(c.getId(), c.getSuiteId(), c.getSort(), c.getName(), c.getKind(), c.getMethod(),
                c.getPath(), readStringMap(c.getParamsJson()), readStringMap(c.getHeadersJson()), c.getBodyJson(),
                readObjectMap(c.getExpectedJson()), c.getEnabled(), c.getUpdatedAt(),
                c.getCaseKey(), c.getType(), c.getPriority(), c.getTags(), c.getCaseVersion(),
                c.getReviewStatus(), c.getSignature(), c.getSourceAssetId(), parse(c.getSpecJson()));
    }

    // ---------------- 内部 ----------------

    private String projectIdOf(TestCaseEntity e) {
        return e.getSuiteId() == null ? null : suiteRepo.findById(e.getSuiteId())
                .map(TestSuiteEntity::getProjectId).orElse(null);
    }

    private TestSuiteEntity requireSuite(Long suiteId) {
        TestSuiteEntity suite = suiteRepo.findById(suiteId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "测试套件不存在: " + suiteId));
        if ("script".equals(suite.getKind())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "脚本套件不支持用例编排（走脚本套件运行）");
        }
        return suite;
    }

    private CaseSpec requireSpec(TestCaseSaveRequest req) {
        if (req == null || req.spec() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "spec 必填");
        }
        CaseSpecValidator.validateOrThrow(req.spec());
        return req.spec();
    }

    private void ensureCaseKeyFree(String projectId, String caseKey, Long excludeId) {
        if (caseRepo.countCaseKeyInProject(projectId, caseKey, excludeId) > 0) {
            throw new DevMindException(ErrorCode.CONFLICT, "用例键已存在: " + caseKey);
        }
    }

    private void checkSourceAsset(String projectId, CaseSpec spec) {
        Long assetId = spec.source() == null ? null : spec.source().assetId();
        if (assetId == null) {
            return;
        }
        TestAssetEntity asset = assetRepo.findById(assetId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "来源资产不存在: " + assetId));
        if (!projectId.equals(asset.getProjectId())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "来源资产不属于项目 " + projectId);
        }
        if (!TestAssetEntity.APPROVED.equals(asset.getReviewStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "来源资产未评审通过，不可引用: " + assetId);
        }
        boolean program = TestAssetEntity.ROLE_PROGRAM.equals(asset.getAssetRole());
        boolean contract = spec.command() != null && ProgramContract.CONTRACT_VERSION
                .equals(blankToNull(spec.command().contractVersion()));
        if (contract && !program) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "contractVersion=\"1\" 要求引用的资产是 PROGRAM 程序包: " + assetId);
        }
        if (program && !contract) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "PROGRAM 程序包必须通过 command.contractVersion=\"1\" 引用（附录 C 契约）");
        }
        if (contract) {
            String cmd = spec.command().command() == null ? "" : spec.command().command();
            String entry = asset.getEntrypoint();
            if (entry == null || !cmd.contains(entry)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "command.command 需调用程序包入口脚本 " + entry + "（如 sh " + entry + "）");
            }
            String wd = spec.command().workingDir();
            if (wd != null && !wd.isBlank()) {
                String path = wd.strip().replace('\\', '/');
                if (path.startsWith("/") || path.contains("..") || path.contains(":")) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST,
                            "程序用例 workingDir 必须是程序包内相对路径: " + wd);
                }
            }
        }
    }

    /** CaseSpec → 旧 CAP-10 列（保持套件编辑页/历史报告兼容）。 */
    private void applySpec(TestCaseEntity e, CaseSpec spec) {
        String type = normalize(spec.type());
        e.setName(truncate(spec.title(), 128));
        e.setType(type);
        e.setKind(switch (type) {
            case CaseSpec.HTTP -> "http";
            case CaseSpec.COMMAND -> "health";
            default -> "manual";
        });
        e.setPriority(blankToNull(spec.priority()));
        e.setTags(spec.tags() == null ? null : truncate(String.join(",", spec.tags()), 256));
        e.setSpecJson(write(spec));
        e.setSignature(CaseSignatures.of(spec));
        e.setSourceAssetId(spec.source() == null ? null : spec.source().assetId());
        if (spec.http() != null) {
            e.setMethod(spec.http().method() == null ? "GET" : spec.http().method().strip().toUpperCase(Locale.ROOT));
            e.setPath(spec.http().path());
            e.setParamsJson(write(spec.http().params()));
            e.setHeadersJson(write(spec.http().headers()));
            e.setBodyJson(spec.http().body());
        } else {
            e.setMethod(null);
            e.setPath(null);
            e.setParamsJson("{}");
            e.setHeadersJson("{}");
            e.setBodyJson(null);
        }
        e.setExpectedJson(write(legacyExpected(spec)));
    }

    private Map<String, Object> legacyExpected(CaseSpec spec) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (CaseSpec.COMMAND.equals(normalize(spec.type())) && spec.command() != null) {
            out.put("type", "command");
            out.put("command", spec.command().command());
            return out;
        }
        if (spec.assertions() != null) {
            for (AssertionSpec a : spec.assertions()) {
                if (a != null && AssertionSpec.HTTP_STATUS.equalsIgnoreCase(a.source()) && a.expected() != null) {
                    out.put("status", a.expected());
                    break;
                }
            }
        }
        return out;
    }

    private int nextSort(Long suiteId) {
        return caseRepo.findBySuiteIdOrderBySortAsc(suiteId).stream()
                .map(TestCaseEntity::getSort).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(0) + 1;
    }

    private TestCaseVersionEntity saveVersion(TestCaseEntity c, CaseSpec spec, String status,
                                              String changeNote, String actor) {
        TestCaseVersionEntity v = new TestCaseVersionEntity();
        v.setCaseId(c.getId());
        v.setVersionNo(c.getCaseVersion() == null ? 1 : c.getCaseVersion());
        v.setSpecJson(write(spec));
        v.setReviewStatus(status);
        v.setChangeNote(truncate(changeNote, 512));
        v.setCreatedBy(actor);
        v.setCreatedAt(Instant.now());
        return versionRepo.save(v);
    }

    private List<TestCaseView> duplicates(String projectId, String signature, Long excludeId) {
        if (signature == null || projectId == null) {
            return List.of();
        }
        return caseRepo.findDuplicates(projectId, signature, excludeId).stream().map(this::toView).toList();
    }

    private String changeNote(TestCaseSaveRequest req) {
        return req.changeNote() == null || req.changeNote().isBlank() ? null : req.changeNote().strip();
    }

    private CaseSpec withCaseKey(CaseSpec spec, String caseKey) {
        return new CaseSpec(caseKey, spec.title(), spec.type(), spec.priority(), spec.tags(),
                spec.preconditions(), spec.steps(), spec.assertions(), spec.cleanup(), spec.executor(),
                spec.http(), spec.command(), spec.manual(), spec.source());
    }

    private String normalize(String type) {
        String t = normalizeOrNull(type);
        return t == null ? CaseSpec.MANUAL : t;
    }

    private String normalizeOrNull(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase(Locale.ROOT);
    }

    private String write(Object v) {
        try {
            return v == null ? null : mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "JSON 序列化失败");
        }
    }

    private CaseSpec parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, CaseSpec.class);
        } catch (Exception e) {
            log.warn("用例 spec 解析失败: {}", e.getMessage());
            return null;
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

    private Map<String, String> readStringMap(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        readObjectMap(json).forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
        return out;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
