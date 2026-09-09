package com.devmind.execution.template;

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
 * 命令模板白名单管理（原 CAP-07 FR-05，CAP-36 起由执行底座承接；路由保持不变）。
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
