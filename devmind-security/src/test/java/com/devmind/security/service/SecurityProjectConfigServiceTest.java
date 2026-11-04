package com.devmind.security.service;

import com.devmind.security.config.SecurityProperties;
import com.devmind.security.model.SecurityProjectConfigEntity;
import com.devmind.security.repo.SecurityProjectConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-66 FR-10-1：内部构件白名单前缀严格段边界（com.apusic 不误伤 com.apusic2），
 * 平台默认与项目追加合并去重。
 */
class SecurityProjectConfigServiceTest {

    @Test
    void 前缀匹配严格段边界() {
        List<String> prefixes = List.of("com.apusic", "org.internal");
        assertTrue(SecurityProjectConfigService.matchesPrefix(prefixes, "com.apusic"));
        assertTrue(SecurityProjectConfigService.matchesPrefix(prefixes, "com.apusic.cloud"));
        assertFalse(SecurityProjectConfigService.matchesPrefix(prefixes, "com.apusic2"));
        assertFalse(SecurityProjectConfigService.matchesPrefix(prefixes, "org.apache"));
        assertFalse(SecurityProjectConfigService.matchesPrefix(prefixes, null));
        assertFalse(SecurityProjectConfigService.matchesPrefix(List.of(), "com.apusic"));
    }

    @Test
    void 平台与项目前缀合并去重去空() {
        SecurityProjectConfigRepository repo = mock(SecurityProjectConfigRepository.class);
        SecurityProperties props = new SecurityProperties();
        props.setInternalGroupPrefixes(List.of("com.apusic, org.internal", ""));
        SecurityProjectConfigEntity cfg = new SecurityProjectConfigEntity();
        cfg.setInternalGroupPrefixes("com.apusic,com.custom");
        when(repo.findById("p1")).thenReturn(Optional.of(cfg));

        SecurityProjectConfigService svc = new SecurityProjectConfigService(repo, props);
        assertEquals(List.of("com.apusic", "org.internal", "com.custom"), svc.mergedInternalPrefixes("p1"));

        when(repo.findById("p2")).thenReturn(Optional.empty());
        assertEquals(List.of("com.apusic", "org.internal"), svc.mergedInternalPrefixes("p2"));
    }
}
