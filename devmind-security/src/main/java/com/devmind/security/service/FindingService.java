package com.devmind.security.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.security.engine.NormalizedFinding;
import com.devmind.security.engine.ScanEngineAdapter;
import com.devmind.security.model.SecurityCveSuppressionEntity;
import com.devmind.security.model.SecurityFindingEntity;
import com.devmind.security.model.SecurityScanEntity;
import com.devmind.security.model.SecuritySuppressionEntity;
import com.devmind.security.model.VulnIntelEntity;
import com.devmind.security.parse.DepTreeParser;
import com.devmind.security.repo.SecurityFindingRepository;
import com.devmind.security.repo.SecurityScanRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 归一化与基线 diff 核心（CAP-66 FR-04/FR-10）：引擎报告 → 统一 finding 行。
 *
 * <p>管线顺序（误报治理漏斗不可颠倒）：
 * ①白名单过滤（内部构件 CPE 噪声，计数进 filtered_count 不入库）
 * → ②fingerprint 去重 → ③依赖树回填（scope/directDep/引入链）
 * → ④EPSS/KEV 情报富化（查表，缺记录字段 null）
 * → ⑤修复指导（remediation 分类 + 推荐版本，纯启发式不编造）
 * → ⑥抑制匹配（fingerprint 抑制 + CVE 抑制，命中 → SUPPRESSED）
 * → ⑦基线 diff（prev = 同 项目+仓库+引擎 最近 SUCCESS 扫描；branch 不是 diff 维度）。</p>
 *
 * <p>FIXED 语义：prev 扫描中 NEW/EXISTING 且本次消失的 finding，复制一行进本次扫描
 * 置 FIXED + fixed_in_scan_id（列表可见「这次修掉了什么」）。</p>
 */
@Service
public class FindingService {

    private static final Set<String> DOWNGRADED_SCOPES = Set.of("test", "provided");

    private final SecurityFindingRepository findingRepo;
    private final SecurityScanRepository scanRepo;
    private final SuppressionService suppressionService;
    private final CveSuppressionService cveSuppressionService;
    private final SecurityProjectConfigService configService;
    private final VulnIntelService intelService;
    private final ObjectMapper mapper;

    public FindingService(SecurityFindingRepository findingRepo,
                          SecurityScanRepository scanRepo,
                          SuppressionService suppressionService,
                          CveSuppressionService cveSuppressionService,
                          SecurityProjectConfigService configService,
                          VulnIntelService intelService,
                          ObjectMapper mapper) {
        this.findingRepo = findingRepo;
        this.scanRepo = scanRepo;
        this.suppressionService = suppressionService;
        this.cveSuppressionService = cveSuppressionService;
        this.configService = configService;
        this.intelService = intelService;
        this.mapper = mapper;
    }

    /** 单引擎归一化落库的计数回执（scan 行计数由编排层跨引擎累加） */
    public record NormalizeResult(int raw, int filtered, int persisted,
                                  int newCount, int existingCount, int suppressedCount, int fixedCount) {
    }

    /**
     * 归一化 + diff + 落库（引擎报告上传齐后由编排线程调用，非事务方法靠 saveAll 自身提交）。
     *
     * @param depTree SCA 的引入链索引（GA → DepInfo；无依赖树报告时传 null/空）
     */
    public NormalizeResult persistFindings(SecurityScanEntity scan, ScanEngineAdapter adapter,
                                           List<NormalizedFinding> parsed,
                                           Map<String, DepTreeParser.DepInfo> depTree) {
        String engine = adapter.engineId();
        int raw = parsed.size();

        // ①白名单过滤（仅 SCA：内部构件 CPE 噪声）
        List<String> internalPrefixes = configService.mergedInternalPrefixes(scan.getProjectId());
        int filtered = 0;
        List<NormalizedFinding> stage = new ArrayList<>(parsed.size());
        for (NormalizedFinding f : parsed) {
            if (SecurityFindingEntity.ENGINE_SCA.equals(engine)
                    && SecurityProjectConfigService.matchesPrefix(internalPrefixes, f.groupId())) {
                filtered++;
                continue;
            }
            stage.add(f);
        }

        // ②fingerprint 去重（同 fp 保留首条；报告内重复如多 CPE 同 CVE 同构件）
        Map<String, NormalizedFinding> deduped = new LinkedHashMap<>();
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (NormalizedFinding f : stage) {
            String fp = adapter.fingerprint(f);
            if (fp == null || fp.isBlank() || deduped.containsKey(fp)) {
                continue;
            }
            deduped.put(fp, f);
            fingerprints.put(fp, fp);
        }

        // prev 基线：同 项目+仓库 最近 SUCCESS（branch 不是 diff 维度）
        SecurityScanEntity prev = scanRepo
                .findByProjectIdAndRepoIdAndStatusOrderByIdDesc(
                        scan.getProjectId(), scan.getRepoId(), SecurityScanEntity.SUCCESS)
                .stream()
                .filter(s -> !s.getId().equals(scan.getId()))
                .findFirst().orElse(null);
        Map<String, SecurityFindingEntity> prevByFp = new LinkedHashMap<>();
        if (prev != null) {
            for (SecurityFindingEntity e : findingRepo.findByScanIdAndEngine(prev.getId(), engine)) {
                prevByFp.putIfAbsent(e.getFingerprint(), e);
            }
        }

        // 抑制集（fingerprint 规则 + CVE 抑制库）一次批取
        Map<String, SecuritySuppressionEntity> suppressions =
                suppressionService.activeByFingerprint(scan.getProjectId(), scan.getRepoId(), engine);
        List<SecurityCveSuppressionEntity> cveSuppressions =
                SecurityFindingEntity.ENGINE_SCA.equals(engine)
                        ? cveSuppressionService.effectiveFor(scan.getProjectId()) : List.of();

        // 情报富化查表（SCA：CVE → EPSS/KEV）
        Map<String, VulnIntelEntity> intel = Map.of();
        if (SecurityFindingEntity.ENGINE_SCA.equals(engine)) {
            Set<String> cves = new HashSet<>();
            for (NormalizedFinding f : deduped.values()) {
                if (f.cve() != null && !f.cve().isBlank()) {
                    cves.add(f.cve());
                }
            }
            intel = intelService.lookup(cves);
        }

        Instant now = Instant.now();
        List<SecurityFindingEntity> rows = new ArrayList<>();
        int newCount = 0;
        int existingCount = 0;
        int suppressedCount = 0;
        for (Map.Entry<String, NormalizedFinding> entry : deduped.entrySet()) {
            String fp = entry.getKey();
            NormalizedFinding f = entry.getValue();
            Map<String, Object> detail = f.detail() != null
                    ? new LinkedHashMap<>(f.detail()) : new LinkedHashMap<>();

            // ③依赖树回填（scope 以依赖树为准补空缺；directDep/引入链）
            Boolean directDep = f.directDep();
            String depPath = null;
            if (SecurityFindingEntity.ENGINE_SCA.equals(engine) && depTree != null && !depTree.isEmpty()
                    && f.artifactId() != null) {
                String ga = (f.groupId() != null ? f.groupId() + ":" : "") + f.artifactId();
                DepTreeParser.DepInfo info = depTree.get(ga);
                if (info != null) {
                    directDep = info.direct();
                    depPath = String.join(" > ", info.path());
                    detail.put("depPath", depPath);
                } else {
                    detail.put("depPathMissing", "依赖树中未找到该构件（可能为依赖树采集范围外或直采 jar）");
                }
            }

            // ④EPSS/KEV 富化
            if (f.cve() != null) {
                VulnIntelEntity vi = intel.get(f.cve());
                if (vi != null) {
                    if (vi.getEpssScore() != null) {
                        detail.put("epssScore", vi.getEpssScore());
                    }
                    if (vi.getEpssPercentile() != null) {
                        detail.put("epssPercentile", vi.getEpssPercentile());
                    }
                    if (vi.isKev()) {
                        detail.put("kev", true);
                        if (vi.getKevDueDate() != null) {
                            detail.put("kevDueDate", vi.getKevDueDate());
                        }
                    }
                }
            }

            // ⑤修复指导（仅 SCA 有构件坐标与版本区间）
            if (SecurityFindingEntity.ENGINE_SCA.equals(engine)) {
                detail.put("remediation", RemediationAdvisor.advise(
                        f.groupId(), f.artifactId(), f.depVersion(),
                        detail.get("versionRanges"), directDep, depPath));
            }

            // ⑥抑制匹配
            SecuritySuppressionEntity fpSuppression = suppressions.get(fp);
            SecurityCveSuppressionEntity cveSuppression = SecurityFindingEntity.ENGINE_SCA.equals(engine)
                    ? cveSuppressionService.match(scan.getProjectId(), f.groupId(), f.artifactId(), f.cve(),
                    cveSuppressions) : null;

            // ⑦diff + 建行
            SecurityFindingEntity e = new SecurityFindingEntity();
            e.setScanId(scan.getId());
            e.setProjectId(scan.getProjectId());
            e.setRepoId(scan.getRepoId());
            e.setEngine(engine);
            e.setFingerprint(fp);
            e.setRuleId(f.ruleId());
            e.setSeverity(f.severity());
            e.setTitle(f.title());
            e.setFilePath(f.filePath());
            e.setLineNo(f.lineNo());
            e.setCodeHash(f.lineContent() != null
                    ? ScanEngineAdapter.sha1(f.lineContent().replaceAll("\\s+", "")) : null);
            e.setDepScope(f.depScope());
            e.setDirectDep(directDep);
            e.setDetailJson(writeJson(detail));
            SecurityFindingEntity prevRow = prevByFp.get(fp);
            if (fpSuppression != null || cveSuppression != null) {
                e.setStatus(SecurityFindingEntity.SUPPRESSED);
                suppressedCount++;
                if (cveSuppression != null) {
                    detail.put("cveSuppressed", true);
                    e.setDetailJson(writeJson(detail));
                }
            } else if (prevRow != null
                    && !SecurityFindingEntity.SUPPRESSED.equals(prevRow.getStatus())
                    && !SecurityFindingEntity.FIXED.equals(prevRow.getStatus())) {
                e.setStatus(SecurityFindingEntity.EXISTING);
                existingCount++;
            } else {
                // prev 无记录，或 prev 已被抑制/已修复而本次复现（抑制过期/解除、修复回退）→ 重新 NEW
                e.setStatus(SecurityFindingEntity.NEW);
                newCount++;
            }
            e.setFirstSeenScanId(prevRow != null && prevRow.getFirstSeenScanId() != null
                    ? prevRow.getFirstSeenScanId() : scan.getId());
            e.setCreatedAt(now);
            e.setUpdatedAt(now);
            rows.add(e);
        }

        // FIXED 落行：prev NEW/EXISTING 且本次消失
        int fixedCount = 0;
        for (SecurityFindingEntity prevRow : prevByFp.values()) {
            if (!SecurityFindingEntity.NEW.equals(prevRow.getStatus())
                    && !SecurityFindingEntity.EXISTING.equals(prevRow.getStatus())) {
                continue;
            }
            if (deduped.containsKey(prevRow.getFingerprint())) {
                continue;
            }
            SecurityFindingEntity fixed = copyAsFixed(prevRow, scan.getId(), now);
            rows.add(fixed);
            fixedCount++;
        }

        findingRepo.saveAll(rows);
        return new NormalizeResult(raw, filtered, rows.size(), newCount, existingCount, suppressedCount, fixedCount);
    }

    /** prev 行复制进本次扫描置 FIXED（first_seen 轨迹保留，detail 原样） */
    private static SecurityFindingEntity copyAsFixed(SecurityFindingEntity prev, long scanId, Instant now) {
        SecurityFindingEntity e = new SecurityFindingEntity();
        e.setScanId(scanId);
        e.setProjectId(prev.getProjectId());
        e.setRepoId(prev.getRepoId());
        e.setEngine(prev.getEngine());
        e.setFingerprint(prev.getFingerprint());
        e.setRuleId(prev.getRuleId());
        e.setSeverity(prev.getSeverity());
        e.setTitle(prev.getTitle());
        e.setFilePath(prev.getFilePath());
        e.setLineNo(prev.getLineNo());
        e.setCodeHash(prev.getCodeHash());
        e.setDepScope(prev.getDepScope());
        e.setDirectDep(prev.getDirectDep());
        e.setDetailJson(prev.getDetailJson());
        e.setStatus(SecurityFindingEntity.FIXED);
        e.setFirstSeenScanId(prev.getFirstSeenScanId());
        e.setFixedInScanId(scanId);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    public SecurityFindingEntity get(long findingId) {
        return findingRepo.findById(findingId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "finding 不存在: " + findingId));
    }

    /**
     * 发现列表查询（FR-03/FR-10）：test/provided scope 默认不进列表（includeTestScope=true 才放行）；
     * kev/epssMin 过滤需解析 detail_json（仅在这两个过滤条件出现时解析）。
     * 排序：默认严重度降序 + id；kevFirst=true 时 KEV 在榜优先。
     */
    public List<SecurityFindingEntity> query(long scanId, String engine, String status, String severity,
                                             Boolean directDep, Boolean kev, Double epssMin,
                                             boolean includeTestScope, boolean kevFirst) {
        List<SecurityFindingEntity> all = engine != null && !engine.isBlank()
                ? findingRepo.findByScanIdAndEngine(scanId, engine)
                : findingRepo.findByScanId(scanId);
        boolean needDetail = Boolean.TRUE.equals(kev) || epssMin != null;
        List<SecurityFindingEntity> out = new ArrayList<>();
        for (SecurityFindingEntity e : all) {
            if (status != null && !status.isBlank() && !status.equals(e.getStatus())) {
                continue;
            }
            if (severity != null && !severity.isBlank() && !severity.equals(e.getSeverity())) {
                continue;
            }
            if (directDep != null && !directDep.equals(e.getDirectDep())) {
                continue;
            }
            if (!includeTestScope && e.getDepScope() != null && DOWNGRADED_SCOPES.contains(e.getDepScope())) {
                continue;
            }
            if (needDetail) {
                Map<String, Object> detail = readJson(e.getDetailJson());
                if (Boolean.TRUE.equals(kev) && !Boolean.TRUE.equals(detail.get("kev"))) {
                    continue;
                }
                if (epssMin != null) {
                    Object score = detail.get("epssScore");
                    if (!(score instanceof Number n) || n.doubleValue() < epssMin) {
                        continue;
                    }
                }
            }
            out.add(e);
        }
        Comparator<SecurityFindingEntity> cmp = Comparator
                .comparingInt((SecurityFindingEntity e) -> severityRank(e.getSeverity())).reversed()
                .thenComparing(SecurityFindingEntity::getId);
        if (kevFirst) {
            cmp = Comparator
                    .comparing((SecurityFindingEntity e) -> !isKev(e))
                    .thenComparing(Comparator.comparingInt(
                            (SecurityFindingEntity e) -> severityRank(e.getSeverity())).reversed())
                    .thenComparing(SecurityFindingEntity::getId);
        }
        out.sort(cmp);
        return out;
    }

    private boolean isKev(SecurityFindingEntity e) {
        Object v = readJson(e.getDetailJson()).get("kev");
        return Boolean.TRUE.equals(v);
    }

    static int severityRank(String severity) {
        if (severity == null) {
            return 0;
        }
        return switch (severity) {
            case NormalizedFinding.CRITICAL -> 5;
            case NormalizedFinding.HIGH -> 4;
            case NormalizedFinding.MEDIUM -> 3;
            case NormalizedFinding.LOW -> 2;
            default -> 1;
        };
    }

    private String writeJson(Map<String, Object> detail) {
        return mapper.writeValueAsString(detail);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
