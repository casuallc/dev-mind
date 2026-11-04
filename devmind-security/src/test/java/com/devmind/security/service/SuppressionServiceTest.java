package com.devmind.security.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.security.model.SecuritySuppressionEntity;
import com.devmind.security.repo.SecuritySuppressionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-66 FR-05 抑制规则：类型/理由校验；同规则重复创建 = 更新不堆行；
 * 生效集剔除过期（过期后下一次扫描同 fingerprint 重新按 NEW 出现）。
 */
class SuppressionServiceTest {

    private SecuritySuppressionRepository repo;
    private SuppressionService service;

    @BeforeEach
    void setUp() {
        repo = mock(SecuritySuppressionRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new SuppressionService(repo);
    }

    @Test
    void 非法类型与空理由拒绝() {
        assertThrows(DevMindException.class, () ->
                service.create("p1", 1L, "sca", "fp", "BOGUS", "r", null, "u"));
        assertThrows(DevMindException.class, () ->
                service.create("p1", 1L, "sca", "fp", SuppressionService.TYPE_FALSE_POSITIVE, " ", null, "u"));
    }

    @Test
    void 同规则重复创建更新而非堆行() {
        SecuritySuppressionEntity existing = new SecuritySuppressionEntity();
        existing.setId(7L);
        existing.setType(SuppressionService.TYPE_FALSE_POSITIVE);
        when(repo.findByProjectIdAndRepoIdAndEngineAndFingerprint("p1", 1L, "sca", "fp1"))
                .thenReturn(Optional.of(existing));

        SecuritySuppressionEntity saved = service.create("p1", 1L, "sca", "fp1",
                SuppressionService.TYPE_ACCEPTED_RISK, "重新评估为风险接受",
                Instant.now().plus(30, ChronoUnit.DAYS), "u2");
        assertEquals(7L, saved.getId());
        assertEquals(SuppressionService.TYPE_ACCEPTED_RISK, saved.getType());
    }

    @Test
    void 生效fingerprint集剔除过期与其他引擎() {
        SecuritySuppressionEntity active = suppression("fp-active", "sca",
                Instant.now().plus(1, ChronoUnit.DAYS));
        SecuritySuppressionEntity expired = suppression("fp-expired", "sca",
                Instant.now().minus(1, ChronoUnit.DAYS));
        SecuritySuppressionEntity noExpiry = suppression("fp-forever", "sca", null);
        SecuritySuppressionEntity otherEngine = suppression("fp-other", "sast", null);
        when(repo.findByProjectIdAndRepoId("p1", 1L))
                .thenReturn(List.of(active, expired, noExpiry, otherEngine));

        Set<String> fps = service.activeFingerprints("p1", 1L, "sca");
        assertEquals(Set.of("fp-active", "fp-forever"), fps);
        assertTrue(service.activeByFingerprint("p1", 1L, "sca").containsKey("fp-active"));
    }

    private static SecuritySuppressionEntity suppression(String fp, String engine, Instant expiresAt) {
        SecuritySuppressionEntity e = new SecuritySuppressionEntity();
        e.setFingerprint(fp);
        e.setEngine(engine);
        e.setExpiresAt(expiresAt);
        return e;
    }
}
