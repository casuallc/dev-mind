package com.devmind.security.service;

import com.devmind.security.engine.NormalizedFinding;
import com.devmind.security.engine.ScaEngineAdapter;
import com.devmind.security.engine.ScanEngineAdapter;
import com.devmind.security.model.SecurityCveSuppressionEntity;
import com.devmind.security.model.SecurityFindingEntity;
import com.devmind.security.model.SecurityScanEntity;
import com.devmind.security.model.SecuritySuppressionEntity;
import com.devmind.security.model.VulnIntelEntity;
import com.devmind.security.parse.DepTreeParser;
import com.devmind.security.repo.SecurityCveSuppressionRepository;
import com.devmind.security.repo.SecurityFindingRepository;
import com.devmind.security.repo.SecurityProjectConfigRepository;
import com.devmind.security.repo.SecurityScanRepository;
import com.devmind.security.config.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-66 FR-04/FR-10 归一化与基线 diff 核心管线：
 * ①白名单过滤计数 ②fingerprint 去重 ③依赖树回填 ④EPSS/KEV 富化
 * ⑤修复指导 ⑥抑制匹配（过期复现为 NEW） ⑦diff（NEW→EXISTING→FIXED）。
 */
class FindingServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SecurityFindingRepository findingRepo;
    private SecurityScanRepository scanRepo;
    private SuppressionService suppressionService;
    private CveSuppressionService cveSuppressionService;
    private VulnIntelService intelService;
    private FindingService service;
    private ScanEngineAdapter adapter;
    private List<SecurityFindingEntity> saved;

    @BeforeEach
    void setUp() {
        findingRepo = mock(SecurityFindingRepository.class);
        scanRepo = mock(SecurityScanRepository.class);
        suppressionService = mock(SuppressionService.class);
        intelService = mock(VulnIntelService.class);
        SecurityCveSuppressionRepository cveRepo = mock(SecurityCveSuppressionRepository.class);
        cveSuppressionService = new CveSuppressionService(cveRepo);
        SecurityProjectConfigRepository cfgRepo = mock(SecurityProjectConfigRepository.class);
        when(cfgRepo.findById(anyString())).thenReturn(Optional.empty());
        SecurityProperties props = new SecurityProperties();
        props.setInternalGroupPrefixes(List.of("com.apusic"));
        SecurityProjectConfigService configService = new SecurityProjectConfigService(cfgRepo, props);

        when(suppressionService.activeByFingerprint(anyString(), any(), anyString()))
                .thenReturn(Map.of());
        when(intelService.lookup(anyCollection())).thenReturn(Map.of());
        when(scanRepo.findByProjectIdAndRepoIdAndStatusOrderByIdDesc(anyString(), any(), anyString()))
                .thenReturn(List.of());
        when(findingRepo.findByScanIdAndEngine(any(), anyString())).thenReturn(List.of());
        saved = new ArrayList<>();
        when(findingRepo.saveAll(any())).thenAnswer(inv -> {
            saved.addAll(inv.getArgument(0));
            return inv.getArgument(0);
        });
        adapter = new ScaEngineAdapter(mapper);
        service = new FindingService(findingRepo, scanRepo, suppressionService,
                cveSuppressionService, configService, intelService, mapper);
    }

    private static SecurityScanEntity scan(long id) {
        SecurityScanEntity s = new SecurityScanEntity();
        s.setId(id);
        s.setProjectId("p1");
        s.setRepoId(1L);
        s.setStatus(SecurityScanEntity.SUCCESS);
        return s;
    }

    private static NormalizedFinding scaFinding(String cve, String g, String a, String version, String scope) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("cve", cve);
        if (g != null) {
            detail.put("groupId", g);
        }
        if (a != null) {
            detail.put("artifactId", a);
        }
        return new NormalizedFinding("sca", cve, "HIGH", cve + " @ " + a,
                null, null, null, cve, g, a, version, scope, null, detail);
    }

    @Test
    void 白名单过滤内部构件并计数() {
        FindingService.NormalizeResult r = service.persistFindings(scan(1L), adapter,
                List.of(scaFinding("CVE-1", "com.apusic.internal", "sdk", "1.0", "compile"),
                        scaFinding("CVE-2", "org.apache", "lib", "2.0", "compile")), Map.of());
        assertEquals(2, r.raw());
        assertEquals(1, r.filtered());
        assertEquals(1, r.persisted());
        assertEquals(1, r.newCount());
        assertEquals("org.apache", saved.get(0).getDetailJson().contains("org.apache") ? "org.apache" : null);
    }

    @Test
    void 同fingerprint报告内去重保留首条() {
        NormalizedFinding f = scaFinding("CVE-1", "org.apache", "lib", "2.0", "compile");
        FindingService.NormalizeResult r = service.persistFindings(scan(1L), adapter,
                List.of(f, f, f), Map.of());
        assertEquals(3, r.raw());
        assertEquals(1, r.persisted());
    }

    @Test
    void 首扫全NEW次扫EXISTING消失置FIXED() {
        // 首扫：两条 NEW
        FindingService.NormalizeResult r1 = service.persistFindings(scan(1L), adapter,
                List.of(scaFinding("CVE-1", "org.a", "x", "1.0", "compile"),
                        scaFinding("CVE-2", "org.b", "y", "1.0", "compile")), Map.of());
        assertEquals(2, r1.newCount());
        assertTrue(saved.stream().allMatch(e -> SecurityFindingEntity.NEW.equals(e.getStatus())));
        assertTrue(saved.stream().allMatch(e -> Long.valueOf(1L).equals(e.getFirstSeenScanId())));

        // 模拟首扫落库行（prev 基线）
        List<SecurityFindingEntity> prevRows = new ArrayList<>(saved);
        saved.clear();
        when(scanRepo.findByProjectIdAndRepoIdAndStatusOrderByIdDesc("p1", 1L, SecurityScanEntity.SUCCESS))
                .thenReturn(List.of(scan(1L)));
        when(findingRepo.findByScanIdAndEngine(1L, "sca")).thenReturn(prevRows);

        // 次扫：CVE-1 还在（→EXISTING），CVE-2 消失（→FIXED），CVE-3 新出现（→NEW）
        FindingService.NormalizeResult r2 = service.persistFindings(scan(2L), adapter,
                List.of(scaFinding("CVE-1", "org.a", "x", "1.0", "compile"),
                        scaFinding("CVE-3", "org.c", "z", "1.0", "compile")), Map.of());
        assertEquals(1, r2.existingCount());
        assertEquals(1, r2.newCount());
        assertEquals(1, r2.fixedCount());

        Map<String, SecurityFindingEntity> byRule = new HashMap<>();
        for (SecurityFindingEntity e : saved) {
            byRule.put(e.getRuleId(), e);
        }
        assertEquals(SecurityFindingEntity.EXISTING, byRule.get("CVE-1").getStatus());
        assertEquals(Long.valueOf(1L), byRule.get("CVE-1").getFirstSeenScanId()); // first_seen 轨迹延续
        assertEquals(SecurityFindingEntity.FIXED, byRule.get("CVE-2").getStatus());
        assertEquals(Long.valueOf(2L), byRule.get("CVE-2").getFixedInScanId());
        assertEquals(SecurityFindingEntity.NEW, byRule.get("CVE-3").getStatus());
    }

    @Test
    void fingerprint抑制命中置SUPPRESSED() {
        NormalizedFinding f = scaFinding("CVE-1", "org.a", "x", "1.0", "compile");
        String fp = adapter.fingerprint(f);
        SecuritySuppressionEntity rule = new SecuritySuppressionEntity();
        rule.setFingerprint(fp);
        when(suppressionService.activeByFingerprint("p1", 1L, "sca")).thenReturn(Map.of(fp, rule));

        FindingService.NormalizeResult r = service.persistFindings(scan(1L), adapter, List.of(f), Map.of());
        assertEquals(1, r.suppressedCount());
        assertEquals(SecurityFindingEntity.SUPPRESSED, saved.get(0).getStatus());
    }

    @Test
    void cve抑制过期后复现为NEW() {
        SecurityCveSuppressionRepository cveRepo = mock(SecurityCveSuppressionRepository.class);
        SecurityCveSuppressionEntity expired = new SecurityCveSuppressionEntity();
        expired.setGroupId("org.a");
        expired.setArtifactId("*");
        expired.setCve("CVE-1");
        expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(cveRepo.findEffectiveForProject("p1")).thenReturn(List.of(expired));
        FindingService svc = new FindingService(findingRepo, scanRepo, suppressionService,
                new CveSuppressionService(cveRepo),
                new SecurityProjectConfigService(mock(SecurityProjectConfigRepository.class), new SecurityProperties()),
                intelService, mapper);

        FindingService.NormalizeResult r = svc.persistFindings(scan(1L), adapter,
                List.of(scaFinding("CVE-1", "org.a", "x", "1.0", "compile")), Map.of());
        assertEquals(0, r.suppressedCount());
        assertEquals(1, r.newCount()); // 过期 = 失效 → 重新 NEW
    }

    @Test
    void 依赖树回填directDep与引入链() {
        Map<String, DepTreeParser.DepInfo> tree = Map.of(
                "org.a:x", new DepTreeParser.DepInfo("compile", false,
                        List.of("org.top:app", "org.a:x")));
        FindingService.NormalizeResult r = service.persistFindings(scan(1L), adapter,
                List.of(scaFinding("CVE-1", "org.a", "x", "1.0", "compile"),
                        scaFinding("CVE-2", "org.b", "y", "1.0", "compile")), tree);
        assertEquals(2, r.persisted());
        Map<String, SecurityFindingEntity> byRule = new HashMap<>();
        for (SecurityFindingEntity e : saved) {
            byRule.put(e.getRuleId(), e);
        }
        assertEquals(Boolean.FALSE, byRule.get("CVE-1").getDirectDep());
        assertTrue(byRule.get("CVE-1").getDetailJson().contains("org.top:app &gt; org.a:x")
                || byRule.get("CVE-1").getDetailJson().contains("org.top:app > org.a:x"));
        assertTrue(byRule.get("CVE-2").getDetailJson().contains("depPathMissing"));
    }

    @Test
    void epssKev情报富化进detail() {
        VulnIntelEntity vi = new VulnIntelEntity();
        vi.setCve("CVE-1");
        vi.setEpssScore(0.94);
        vi.setEpssPercentile(0.99);
        vi.setKev(true);
        vi.setKevDueDate("2026-01-01");
        when(intelService.lookup(anyCollection())).thenReturn(Map.of("CVE-1", vi));

        service.persistFindings(scan(1L), adapter,
                List.of(scaFinding("CVE-1", "org.a", "x", "1.0", "compile")), Map.of());
        String detail = saved.get(0).getDetailJson();
        assertTrue(detail.contains("0.94"));
        assertTrue(detail.contains("\"kev\":true"));
        assertTrue(detail.contains("2026-01-01"));
    }

    @Test
    void 修复指导随detail落库() {
        NormalizedFinding f = scaFinding("CVE-1", "org.a", "x", "2.14.1", "compile");
        f.detail().put("versionRanges", List.of(Map.of(
                "versionStartIncluding", "2.0", "versionEndExcluding", "2.17.1")));
        service.persistFindings(scan(1L), adapter, List.of(f),
                Map.of("org.a:x", new DepTreeParser.DepInfo("compile", true, List.of("org.a:x"))));
        String detail = saved.get(0).getDetailJson();
        assertTrue(detail.contains("UPGRADE_DIRECT"));
        assertTrue(detail.contains("2.17.1"));
        assertNull(saved.get(0).getFixedInScanId());
    }

    @Test
    void query默认过滤testScope与kevFirst排序() {
        SecurityFindingEntity compileScope = row(1L, "HIGH", "compile", null);
        SecurityFindingEntity testScope = row(2L, "CRITICAL", "test", null);
        SecurityFindingEntity kev = row(3L, "MEDIUM", "compile", "{\"kev\":true}");
        when(findingRepo.findByScanId(9L)).thenReturn(List.of(compileScope, testScope, kev));

        // 默认：test scope 不进列表
        List<SecurityFindingEntity> def = service.query(9L, null, null, null, null, null, null, false, false);
        assertEquals(List.of(1L, 3L), def.stream().map(SecurityFindingEntity::getId).toList());
        // includeTestScope=true 放行，严重度降序
        List<SecurityFindingEntity> all = service.query(9L, null, null, null, null, null, null, true, false);
        assertEquals(List.of(2L, 1L, 3L), all.stream().map(SecurityFindingEntity::getId).toList());
        // kevFirst：KEV 优先于严重度
        List<SecurityFindingEntity> kevFirst = service.query(9L, null, null, null, null, null, null, true, true);
        assertEquals(3L, kevFirst.get(0).getId());
        // kev 过滤
        List<SecurityFindingEntity> onlyKev = service.query(9L, null, null, null, null, true, null, true, false);
        assertEquals(List.of(3L), onlyKev.stream().map(SecurityFindingEntity::getId).toList());
    }

    private static SecurityFindingEntity row(long id, String severity, String scope, String detailJson) {
        SecurityFindingEntity e = new SecurityFindingEntity();
        e.setId(id);
        e.setSeverity(severity);
        e.setDepScope(scope);
        e.setStatus(SecurityFindingEntity.NEW);
        e.setDetailJson(detailJson);
        return e;
    }
}
