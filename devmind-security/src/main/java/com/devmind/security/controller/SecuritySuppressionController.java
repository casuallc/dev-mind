package com.devmind.security.controller;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.security.dto.SuppressionRequest;
import com.devmind.security.model.SecurityFindingEntity;
import com.devmind.security.model.SecuritySuppressionEntity;
import com.devmind.security.service.FindingService;
import com.devmind.security.service.SecurityAccessGuard;
import com.devmind.security.service.SuppressionService;
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
 * 抑制规则 REST（CAP-66 FR-05）：建抑制（findingId 带出四元组）/解除/列表。
 * 建/删 = 项目写，列表 = 项目读（无 projectId 的全量列表仅 ADMIN）。
 */
@RestController
@RequestMapping("/api/security/suppressions")
public class SecuritySuppressionController {

    private final SuppressionService suppressionService;
    private final FindingService findingService;
    private final SecurityAccessGuard accessGuard;
    private final IdentityService identityService;

    public SecuritySuppressionController(SuppressionService suppressionService,
                                         FindingService findingService,
                                         SecurityAccessGuard accessGuard,
                                         IdentityService identityService) {
        this.suppressionService = suppressionService;
        this.findingService = findingService;
        this.accessGuard = accessGuard;
        this.identityService = identityService;
    }

    @PostMapping
    public SecuritySuppressionEntity create(@RequestBody SuppressionRequest req) {
        if (req.findingId() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "findingId 必填（抑制从发现发起）");
        }
        SecurityFindingEntity f = findingService.get(req.findingId());
        String actor = identityService.currentActor();
        accessGuard.requireWrite(actor, f.getProjectId());
        return suppressionService.create(f.getProjectId(), f.getRepoId(), f.getEngine(),
                f.getFingerprint(), req.type(), req.reason(), req.expiresAt(), actor);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        SecuritySuppressionEntity existing = suppressionService.requireById(id);
        accessGuard.requireWrite(identityService.currentActor(), existing.getProjectId());
        suppressionService.delete(id);
    }

    @GetMapping
    public List<SecuritySuppressionEntity> list(@RequestParam(required = false) String projectId) {
        String actor = identityService.currentActor();
        if (projectId == null || projectId.isBlank()) {
            accessGuard.requireAdmin(); // 跨项目全量列表仅 ADMIN
        } else {
            accessGuard.requireRead(actor, projectId);
        }
        return suppressionService.list(projectId);
    }
}
