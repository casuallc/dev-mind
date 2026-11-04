package com.devmind.security.controller;

import com.devmind.auth.IdentityService;
import com.devmind.security.dto.SecurityConfigRequest;
import com.devmind.security.model.SecurityProjectConfigEntity;
import com.devmind.security.service.SecurityAccessGuard;
import com.devmind.security.service.SecurityProjectConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目级扫描配置（CAP-66 FR-13）：内部构件白名单前缀追加（平台默认前缀在
 * application.yml，此处只存项目增量；effective = 两者合并）。
 */
@RestController
@RequestMapping("/api/security/projects/{projectId}/config")
public class SecurityProjectConfigController {

    private final SecurityProjectConfigService configService;
    private final SecurityAccessGuard accessGuard;
    private final IdentityService identityService;

    public SecurityProjectConfigController(SecurityProjectConfigService configService,
                                           SecurityAccessGuard accessGuard,
                                           IdentityService identityService) {
        this.configService = configService;
        this.accessGuard = accessGuard;
        this.identityService = identityService;
    }

    public record ConfigView(String projectId, String internalGroupPrefixes,
                             List<String> effectivePrefixes) {
    }

    @GetMapping
    public ConfigView get(@PathVariable String projectId) {
        accessGuard.requireRead(identityService.currentActor(), projectId);
        SecurityProjectConfigEntity e = configService.get(projectId);
        return new ConfigView(projectId, e == null ? "" : nullToEmpty(e.getInternalGroupPrefixes()),
                configService.mergedInternalPrefixes(projectId));
    }

    @PutMapping
    public ConfigView put(@PathVariable String projectId, @RequestBody SecurityConfigRequest req) {
        accessGuard.requireWrite(identityService.currentActor(), projectId);
        SecurityProjectConfigEntity e = configService.put(projectId, req.internalGroupPrefixes());
        return new ConfigView(projectId, nullToEmpty(e.getInternalGroupPrefixes()),
                configService.mergedInternalPrefixes(projectId));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
