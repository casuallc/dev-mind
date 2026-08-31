package com.devmind.serveradapter.controller;

import com.devmind.serveradapter.dto.TemplateRequest;
import com.devmind.serveradapter.dto.TemplateView;
import com.devmind.serveradapter.service.ScriptTemplateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-07 FR-05 命令模板白名单管理。
 */
@RestController
@RequestMapping("/api/script-templates")
public class ScriptTemplateController {

    private final ScriptTemplateService service;

    public ScriptTemplateController(ScriptTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public List<TemplateView> list(@RequestParam(required = false) String projectId) {
        return service.list(projectId);
    }

    @PostMapping
    public TemplateView create(@Valid @RequestBody TemplateRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public TemplateView update(@PathVariable Long id, @RequestBody TemplateRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
