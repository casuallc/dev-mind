package com.devmind.artifact.controller;

import com.devmind.artifact.ArtifactService;
import com.devmind.artifact.dto.ArtifactView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CAP-13 工作产物查询：/api/projects/{projectId}/artifacts */
@RestController
@RequestMapping("/api/projects/{projectId}/artifacts")
public class ArtifactController {

    private final ArtifactService service;

    public ArtifactController(ArtifactService service) {
        this.service = service;
    }

    @GetMapping
    public List<ArtifactView> list(@PathVariable String projectId,
                                   @RequestParam(required = false) String workItemId,
                                   @RequestParam(required = false) String requirementId) {
        return service.list(projectId, workItemId, requirementId);
    }

    @GetMapping("/{id}")
    public ArtifactView get(@PathVariable String projectId, @PathVariable Long id) {
        return service.get(id);
    }
}
