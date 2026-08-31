package com.devmind.knowledge.controller;

import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.knowledge.dto.EntryRequest;
import com.devmind.knowledge.dto.EntryView;
import com.devmind.knowledge.dto.PreviewResult;
import com.devmind.knowledge.dto.ProposalRequest;
import com.devmind.knowledge.dto.ProposalView;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库 REST（CAP-04）：条目 CRUD/检索、注入预览、经验提案流转。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeBaseService service;

    public KnowledgeController(KnowledgeBaseService service) {
        this.service = service;
    }

    // ---------------- 条目 ----------------

    @GetMapping("/entries")
    public List<EntryView> list(@RequestParam(required = false) String scope,
                                @RequestParam(required = false) String projectId,
                                @RequestParam(required = false) String status) {
        return service.list(scope, projectId, status);
    }

    @GetMapping("/entries/search")
    public List<EntryView> search(@RequestParam String q,
                                  @RequestParam(required = false) String projectId) {
        return service.search(q, projectId);
    }

    @GetMapping("/entries/{id}")
    public EntryView get(@PathVariable Long id) {
        return service.getEntry(id);
    }

    @PostMapping("/entries")
    public EntryView create(@RequestBody EntryRequest req) {
        return service.createEntry(req);
    }

    @PutMapping("/entries/{id}")
    public EntryView update(@PathVariable Long id, @RequestBody EntryRequest req) {
        return service.updateEntry(id, req);
    }

    @DeleteMapping("/entries/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteEntry(id);
    }

    // ---------------- 注入预览（FR-04） ----------------

    @GetMapping("/preview")
    public PreviewResult preview(@RequestParam(required = false) String projectId,
                                 @RequestParam(required = false) String taskSpec) {
        return service.preview(projectId, taskSpec);
    }

    // ---------------- 提案（FR-05/FR-06） ----------------

    @GetMapping("/proposals")
    public List<ProposalView> listProposals(@RequestParam(required = false) String status) {
        return service.listProposals(status);
    }

    @PostMapping("/proposals")
    public ProposalView createProposal(@RequestBody ProposalRequest req) {
        return service.createProposal(req);
    }

    @PostMapping("/proposals/{id}/adopt")
    public ProposalView adopt(@PathVariable Long id,
                              @RequestParam String target,
                              @RequestParam(required = false) String projectId) {
        return service.adopt(id, target, projectId);
    }

    @PostMapping("/proposals/{id}/reject")
    public ProposalView reject(@PathVariable Long id) {
        return service.reject(id);
    }
}
