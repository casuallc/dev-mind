package com.devmind.project.controller;

import com.devmind.project.DesignService;
import com.devmind.project.dto.DesignRequest;
import com.devmind.project.dto.DesignView;
import com.devmind.project.dto.StatusRequest;
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

/**
 * Design REST API（CAP-13 研发主线）：需求下的解决方案。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/requirements/{requirementId}/designs")
public class DesignController {

    private final DesignService service;

    public DesignController(DesignService service) {
        this.service = service;
    }

    @GetMapping
    public List<DesignView> list(@PathVariable String projectId, @PathVariable String requirementId) {
        return service.list(projectId, requirementId);
    }

    @PostMapping
    public DesignView create(@PathVariable String projectId, @PathVariable String requirementId,
                             @RequestBody DesignRequest req) {
        return service.create(projectId, requirementId, req);
    }

    @PutMapping("/{designId}")
    public DesignView update(@PathVariable String projectId, @PathVariable String requirementId,
                             @PathVariable String designId, @RequestBody DesignRequest req) {
        return service.update(projectId, requirementId, designId, req);
    }

    /** 状态流转（DRAFT / CONFIRMED / DISCARDED） */
    @PutMapping("/{designId}/status")
    public DesignView updateStatus(@PathVariable String projectId, @PathVariable String requirementId,
                                   @PathVariable String designId, @Valid @RequestBody StatusRequest req) {
        return service.updateStatus(projectId, requirementId, designId, req.status());
    }

    @DeleteMapping("/{designId}")
    public void delete(@PathVariable String projectId, @PathVariable String requirementId,
                       @PathVariable String designId) {
        service.delete(projectId, requirementId, designId);
    }
}
