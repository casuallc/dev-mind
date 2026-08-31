package com.devmind.artifact.controller;

import com.devmind.artifact.ArtifactService;
import com.devmind.artifact.dto.ArtifactView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** P1-2 制品查询：/api/projects/{projectId}/artifacts */
@RestController
@RequestMapping("/api/projects/{projectId}/artifacts")
public class ArtifactController {

    private final ArtifactService service;

    public ArtifactController(ArtifactService service) {
        this.service = service;
    }

    @GetMapping
    public List<ArtifactView> list(@PathVariable String projectId,
                                   @RequestParam(required = false) String taskId) {
        return service.list(projectId, taskId);
    }

    @GetMapping("/{id}")
    public ArtifactView get(@PathVariable String projectId, @PathVariable Long id) {
        return service.get(id);
    }
}
