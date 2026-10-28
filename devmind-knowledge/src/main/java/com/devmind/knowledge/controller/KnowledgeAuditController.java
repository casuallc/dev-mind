package com.devmind.knowledge.controller;
import com.devmind.common.knowledge.KnowledgeIdentityProvider;
import com.devmind.common.exception.*;
import com.devmind.knowledge.model.KnowledgeAuditEntity;
import com.devmind.knowledge.repo.KnowledgeAuditRepository;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/knowledge/audits")
public class KnowledgeAuditController {
    private final KnowledgeAuditRepository repo;
    private final KnowledgeIdentityProvider identities;
    public KnowledgeAuditController(KnowledgeAuditRepository repo,KnowledgeIdentityProvider identities){this.repo=repo;this.identities=identities;}
    @GetMapping public List<KnowledgeAuditEntity> list(@RequestParam(defaultValue="false") boolean securityAudit) {
        var subject=identities.current();
        if(securityAudit && !"ADMIN".equals(subject.role()))throw new DevMindException(ErrorCode.FORBIDDEN);
        // Audit rows contain IDs, reason codes and digests; never document names, queries, bodies or credentials.
        return securityAudit?repo.findTop100ByOrderByCreatedAtDesc():repo.findTop100ByActorOrderByCreatedAtDesc(subject.actor());
    }
}
