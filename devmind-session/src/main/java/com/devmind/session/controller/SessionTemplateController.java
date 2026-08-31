package com.devmind.session.controller;

import com.devmind.session.dto.TemplateView;
import com.devmind.session.service.SessionManagerService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话模板 CRUD + 渲染预览。
 */
@RestController
@RequestMapping("/api/session-templates")
public class SessionTemplateController {

    private final SessionManagerService service;

    public SessionTemplateController(SessionManagerService service) {
        this.service = service;
    }

    @GetMapping
    public List<TemplateView> list() {
        return service.listTemplates();
    }

    @PostMapping
    public TemplateView create(@RequestBody TemplateView req) {
        return service.saveTemplate(null, req.code(), req.name(), req.prompt(),
                req.sortOrder(), req.enabled());
    }

    @PutMapping("/{id}")
    public TemplateView update(@PathVariable Long id, @RequestBody TemplateView req) {
        return service.saveTemplate(id, req.code(), req.name(), req.prompt(),
                req.sortOrder(), req.enabled());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteTemplate(id);
    }

    @PostMapping("/{code}/preview")
    public Map<String, String> preview(@PathVariable String code, @RequestBody(required = false) Map<String, String> body) {
        String task = body == null ? "" : body.getOrDefault("task", "");
        String project = body == null ? "" : body.getOrDefault("project", "");
        String branch = body == null ? "" : body.getOrDefault("branch", "");
        String rendered = service.previewTemplate(code, task, project, branch);
        return Map.of("rendered", rendered);
    }
}
