package com.devmind.project.controller;

import com.devmind.project.EnvironmentService;
import com.devmind.project.dto.EnvironmentRequest;
import com.devmind.project.dto.EnvironmentView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** P1-1 项目环境 CRUD：/api/projects/{projectId}/environments */
@RestController
@RequestMapping("/api/projects/{projectId}/environments")
public class EnvironmentController {

    private final EnvironmentService service;

    public EnvironmentController(EnvironmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<EnvironmentView> list(@PathVariable String projectId) {
        return service.list(projectId);
    }

    @GetMapping("/{envId}")
    public EnvironmentView get(@PathVariable String projectId, @PathVariable Long envId) {
        return service.get(projectId, envId);
    }

    @PostMapping
    public EnvironmentView create(@PathVariable String projectId, @Valid @RequestBody EnvironmentRequest req) {
        return service.create(projectId, req);
    }

    @PutMapping("/{envId}")
    public EnvironmentView update(@PathVariable String projectId, @PathVariable Long envId,
                                  @Valid @RequestBody EnvironmentRequest req) {
        return service.update(projectId, envId, req);
    }

    @DeleteMapping("/{envId}")
    public void delete(@PathVariable String projectId, @PathVariable Long envId) {
        service.delete(projectId, envId);
    }
}
