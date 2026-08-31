package com.devmind.project.controller;

import com.devmind.project.RequirementService;
import com.devmind.project.dto.RequirementRequest;
import com.devmind.project.dto.RequirementView;
import com.devmind.project.dto.StatusRequest;
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
 * Requirement REST API（CAP-13 研发主线）：业务目标（身份 + 状态 + 关联）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/requirements")
public class RequirementController {

    private final RequirementService service;

    public RequirementController(RequirementService service) {
        this.service = service;
    }

    @GetMapping
    public List<RequirementView> list(@PathVariable String projectId,
                                      @RequestParam(required = false) String status) {
        return service.list(projectId, status);
    }

    @PostMapping
    public RequirementView create(@PathVariable String projectId, @Valid @RequestBody RequirementRequest req) {
        return service.create(projectId, req);
    }

    @GetMapping("/{requirementId}")
    public RequirementView get(@PathVariable String projectId, @PathVariable String requirementId) {
        return service.get(projectId, requirementId);
    }

    @PutMapping("/{requirementId}")
    public RequirementView update(@PathVariable String projectId, @PathVariable String requirementId,
                                  @Valid @RequestBody RequirementRequest req) {
        return service.update(projectId, requirementId, req);
    }

    /** 人工状态翻转（验收 DONE / 取消 CANCELLED 等；派生状态由 rollup 自动重算） */
    @PutMapping("/{requirementId}/status")
    public RequirementView updateStatus(@PathVariable String projectId, @PathVariable String requirementId,
                                        @Valid @RequestBody StatusRequest req) {
        return service.updateStatus(projectId, requirementId, req.status());
    }

    @DeleteMapping("/{requirementId}")
    public void delete(@PathVariable String projectId, @PathVariable String requirementId) {
        service.delete(projectId, requirementId);
    }
}
