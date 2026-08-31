package com.devmind.docs.controller;

import com.devmind.docs.DocumentService;
import com.devmind.docs.dto.DiffView;
import com.devmind.docs.dto.DocDetail;
import com.devmind.docs.dto.DocRequest;
import com.devmind.docs.dto.DocVersionView;
import com.devmind.docs.dto.DocView;
import com.devmind.docs.dto.SaveVersionRequest;
import com.devmind.docs.dto.TemplateView;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档 REST（CAP-03）：CRUD / 版本化 / diff / 状态机 / 检索 / 模板 / git push。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @PostMapping
    public DocDetail create(@RequestBody DocRequest req) {
        return service.create(req);
    }

    @GetMapping
    public List<DocView> list(@RequestParam(required = false) String kind,
                              @RequestParam(required = false) String projectId,
                              @RequestParam(required = false) String status) {
        return service.list(kind, projectId, status);
    }

    @GetMapping("/search")
    public List<DocView> search(@RequestParam String q) {
        return service.search(q);
    }

    @GetMapping("/templates")
    public List<TemplateView> templates() {
        return service.templates();
    }

    @GetMapping("/repo")
    public Map<String, String> repo() {
        return service.repoInfo();
    }

    @PostMapping("/push")
    public Map<String, String> push() {
        return Map.of("message", service.push());
    }

    @GetMapping("/{id}")
    public DocDetail get(@PathVariable Long id,
                         @RequestParam(required = false) Integer version) {
        return service.get(id, version);
    }

    @GetMapping("/{id}/versions")
    public List<DocVersionView> versions(@PathVariable Long id) {
        return service.versions(id);
    }

    @PostMapping("/{id}/versions")
    public DocDetail saveVersion(@PathVariable Long id, @RequestBody SaveVersionRequest req) {
        return service.saveVersion(id, req);
    }

    @GetMapping("/{id}/versions/{v}/diff")
    public DiffView diff(@PathVariable Long id, @PathVariable int v) {
        return service.diff(id, v);
    }

    @PostMapping("/{id}/versions/{v}/revert")
    public DocDetail revert(@PathVariable Long id, @PathVariable int v) {
        return service.revert(id, v);
    }

    @PostMapping("/{id}/status")
    public DocDetail transition(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return service.transition(id, body.get("action"));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
