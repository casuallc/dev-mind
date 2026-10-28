package com.devmind.knowledge.controller;

import com.devmind.knowledge.access.KnowledgeAccessService;
import com.devmind.knowledge.dto.KnowledgeAccessView;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeAccessController {
    private final KnowledgeAccessService access;
    public KnowledgeAccessController(KnowledgeAccessService access) { this.access = access; }

    @ExceptionHandler({org.springframework.dao.OptimisticLockingFailureException.class,
            jakarta.persistence.OptimisticLockException.class})
    public org.springframework.http.ResponseEntity<com.devmind.common.exception.ApiError> concurrentChange(
            jakarta.servlet.http.HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.status(409).body(com.devmind.common.exception.ApiError.of(
                com.devmind.common.exception.ErrorCode.CONFLICT, "授权已变更，请刷新后重试", request.getRequestURI()));
    }

    @GetMapping("/bases/{id}/access")
    public KnowledgeAccessView base(@PathVariable Long id) { return access.baseAccess(id); }
    @PutMapping("/bases/{id}/access")
    public KnowledgeAccessView update(@PathVariable Long id, @RequestBody KnowledgeAccessView.Update request) {
        return access.updateBaseAccess(id, request);
    }
    @GetMapping("/entries/{id}/access")
    public KnowledgeAccessView entry(@PathVariable Long id) { return access.entryAccess(id); }
    @PutMapping("/entries/{id}/access")
    public KnowledgeAccessView restrict(@PathVariable Long id, @RequestBody KnowledgeAccessView.Update request) {
        return access.restrictEntry(id, request);
    }
    @GetMapping("/admin/access-migration")
    public List<KnowledgeAccessView.PendingBase> pending() { return access.pendingBases(); }
    @PostMapping("/admin/access-migration/{id}")
    public KnowledgeAccessView assign(@PathVariable Long id, @RequestBody KnowledgeAccessView.AssignOwner request) {
        return access.assignLegacyOwner(id, request.username());
    }
    @GetMapping("/admin/policy-control/{id}") public KnowledgeAccessView control(@PathVariable Long id){return access.policyControl(id);}
    @PostMapping("/admin/policy-control/{id}/transfer") public KnowledgeAccessView transfer(@PathVariable Long id,@RequestBody KnowledgeAccessService.Transfer request){return access.transferOwner(id,request);}
    @PostMapping("/admin/policy-control/{id}/debug-read") public KnowledgeAccessView debug(@PathVariable Long id,@RequestBody KnowledgeAccessService.DebugRead request){return access.debugRead(id,request);}
}
