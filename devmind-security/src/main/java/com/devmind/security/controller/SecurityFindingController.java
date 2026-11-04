package com.devmind.security.controller;

import com.devmind.auth.IdentityService;
import com.devmind.security.model.SecurityFindingEntity;
import com.devmind.security.repo.SecurityFindingRepository;
import com.devmind.security.service.FindingService;
import com.devmind.security.service.SecurityAccessGuard;
import com.devmind.security.service.SecurityAiService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 发现详情 + AI 复核/修复（CAP-66 FR-06/08/11-4）。
 * AI 端点异步起会话 → 202 + sessionId，产出经 session.completed 回写 finding。
 */
@RestController
@RequestMapping("/api/security/findings")
public class SecurityFindingController {

    private final FindingService findingService;
    private final SecurityFindingRepository findingRepo;
    private final SecurityAiService aiService;
    private final SecurityAccessGuard accessGuard;
    private final IdentityService identityService;

    public SecurityFindingController(FindingService findingService,
                                     SecurityFindingRepository findingRepo,
                                     SecurityAiService aiService,
                                     SecurityAccessGuard accessGuard,
                                     IdentityService identityService) {
        this.findingService = findingService;
        this.findingRepo = findingRepo;
        this.aiService = aiService;
        this.accessGuard = accessGuard;
        this.identityService = identityService;
    }

    /** 详情：实体（detail_json 含引入链/修复推荐/情报）+ 同 fingerprint 历史轨迹 */
    public record FindingDetail(SecurityFindingEntity finding, List<SecurityFindingEntity> history) {
    }

    @GetMapping("/{id}")
    public FindingDetail detail(@PathVariable long id) {
        SecurityFindingEntity f = findingService.get(id);
        accessGuard.requireRead(identityService.currentActor(), f.getProjectId());
        List<SecurityFindingEntity> history = findingRepo.findByFingerprintOrderByIdDesc(f.getFingerprint())
                .stream().filter(h -> !h.getId().equals(f.getId())).limit(20).toList();
        return new FindingDetail(f, history);
    }

    @PostMapping("/{id}/review")
    public ResponseEntity<Map<String, String>> review(@PathVariable long id) {
        SecurityFindingEntity f = findingService.get(id);
        String actor = identityService.currentActor();
        accessGuard.requireWrite(actor, f.getProjectId());
        String sessionId = aiService.startReview(id, actor);
        return ResponseEntity.accepted().body(Map.of("sessionId", sessionId));
    }

    @PostMapping("/{id}/fix")
    public ResponseEntity<Map<String, String>> fix(@PathVariable long id) {
        SecurityFindingEntity f = findingService.get(id);
        String actor = identityService.currentActor();
        accessGuard.requireWrite(actor, f.getProjectId());
        String sessionId = aiService.startFix(id, actor);
        return ResponseEntity.accepted().body(Map.of("sessionId", sessionId));
    }
}
