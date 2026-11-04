package com.devmind.security.controller;

import com.devmind.auth.IdentityService;
import com.devmind.security.dto.CveSuppressionRequest;
import com.devmind.security.model.SecurityCveSuppressionEntity;
import com.devmind.security.service.CveSuppressionService;
import com.devmind.security.service.SecurityAccessGuard;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CVE 抑制库 REST（CAP-66 FR-10-3）：构件×CVE 双轨抑制。
 * 平台级（projectId 空）建/删仅 ADMIN；项目级 = 项目写；列表 = 项目读（全量仅 ADMIN）。
 */
@RestController
@RequestMapping("/api/security/cve-suppressions")
public class SecurityCveSuppressionController {

    private final CveSuppressionService cveSuppressionService;
    private final SecurityAccessGuard accessGuard;
    private final IdentityService identityService;

    public SecurityCveSuppressionController(CveSuppressionService cveSuppressionService,
                                            SecurityAccessGuard accessGuard,
                                            IdentityService identityService) {
        this.cveSuppressionService = cveSuppressionService;
        this.accessGuard = accessGuard;
        this.identityService = identityService;
    }

    @PostMapping
    public SecurityCveSuppressionEntity create(@RequestBody CveSuppressionRequest req) {
        String actor = identityService.currentActor();
        if (req.projectId() == null || req.projectId().isBlank()) {
            accessGuard.requireAdmin();
        } else {
            accessGuard.requireWrite(actor, req.projectId());
        }
        return cveSuppressionService.create(req.groupId(), req.artifactId(), req.cve(),
                req.projectId(), req.type(), req.reason(), req.expiresAt(), actor);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        SecurityCveSuppressionEntity existing = cveSuppressionService.requireById(id);
        if (existing.getProjectId() == null || existing.getProjectId().isBlank()) {
            accessGuard.requireAdmin();
        } else {
            accessGuard.requireWrite(identityService.currentActor(), existing.getProjectId());
        }
        cveSuppressionService.delete(id);
    }

    @GetMapping
    public List<SecurityCveSuppressionEntity> list(@RequestParam(required = false) String projectId,
                                                   @RequestParam(required = false) String cve) {
        String actor = identityService.currentActor();
        if (projectId == null || projectId.isBlank()) {
            accessGuard.requireAdmin();
        } else {
            accessGuard.requireRead(actor, projectId);
        }
        return cveSuppressionService.list(projectId, cve);
    }
}
