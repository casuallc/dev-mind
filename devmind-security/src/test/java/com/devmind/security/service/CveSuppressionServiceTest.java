package com.devmind.security.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.security.model.SecurityCveSuppressionEntity;
import com.devmind.security.repo.SecurityCveSuppressionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-66 FR-10-3 CVE 抑制库：ACCEPTED_RISK 强制过期时间；uk 含可空 project_id
 * 必须应用层查重（平台级 NULL 撞不上唯一约束）；匹配支持 artifactId='*' 整组、过期失效。
 */
class CveSuppressionServiceTest {

    private SecurityCveSuppressionRepository repo;
    private CveSuppressionService service;

    @BeforeEach
    void setUp() {
        repo = mock(SecurityCveSuppressionRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new CveSuppressionService(repo);
    }

    @Test
    void 风险接受必须带过期时间() {
        assertThrows(DevMindException.class, () -> service.create(
                "com.x", "lib", "CVE-2024-0001", "p1",
                CveSuppressionService.TYPE_ACCEPTED_RISK, "理由", null, "u"));
        // FALSE_POSITIVE 可以无过期
        SecurityCveSuppressionEntity e = service.create("com.x", "lib", "CVE-2024-0001", "p1",
                CveSuppressionService.TYPE_FALSE_POSITIVE, "误报", null, "u");
        assertNotNull(e);
    }

    @Test
    void 平台级条目应用层查重() {
        SecurityCveSuppressionEntity platform = entry("com.x", "lib", "CVE-2024-0001", null, null);
        when(repo.findByGroupIdAndArtifactIdAndCve("com.x", "lib", "CVE-2024-0001"))
                .thenReturn(List.of(platform));
        // 平台级已存在 → 再建平台级冲突
        assertThrows(DevMindException.class, () -> service.create(
                "com.x", "lib", "CVE-2024-0001", null,
                CveSuppressionService.TYPE_FALSE_POSITIVE, "r", null, "admin"));
        // 但项目级不与之冲突（scope 不同）
        SecurityCveSuppressionEntity projectLevel = service.create("com.x", "lib", "CVE-2024-0001", "p1",
                CveSuppressionService.TYPE_FALSE_POSITIVE, "r", null, "u");
        assertEquals("p1", projectLevel.getProjectId());
    }

    @Test
    void 匹配支持整组通配与过期失效() {
        SecurityCveSuppressionEntity wildcard = entry("com.x", "*", "CVE-2024-0001", null, null);
        SecurityCveSuppressionEntity expired = entry("com.y", "lib", "CVE-2024-0002", null,
                Instant.now().minus(1, ChronoUnit.DAYS));
        List<SecurityCveSuppressionEntity> effective = List.of(wildcard, expired);

        assertNotNull(service.match("p1", "com.x", "anything", "CVE-2024-0001", effective));
        assertNull(service.match("p1", "com.y", "lib", "CVE-2024-0002", effective)); // 过期 → 复现 NEW
        assertNull(service.match("p1", "com.z", "lib", "CVE-2024-0001", effective));
        assertNull(service.match("p1", null, "lib", "CVE-2024-0001", effective));
    }

    private static SecurityCveSuppressionEntity entry(String g, String a, String cve,
                                                      String projectId, Instant expiresAt) {
        SecurityCveSuppressionEntity e = new SecurityCveSuppressionEntity();
        e.setGroupId(g);
        e.setArtifactId(a);
        e.setCve(cve);
        e.setProjectId(projectId);
        e.setType(CveSuppressionService.TYPE_FALSE_POSITIVE);
        e.setExpiresAt(expiresAt);
        return e;
    }
}
