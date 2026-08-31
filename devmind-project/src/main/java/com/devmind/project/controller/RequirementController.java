package com.devmind.project.controller;

import com.devmind.project.RequirementService;
import com.devmind.project.dto.RequirementRequest;
import com.devmind.project.dto.RequirementStatusRequest;
import com.devmind.project.dto.RequirementView;
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
 * P0-5 需求 REST API：项目内主线（身份 + 状态 + 关联）。
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

    @GetMapping("/{reqId}")
    public RequirementView get(@PathVariable String projectId, @PathVariable String reqId) {
        return service.get(projectId, reqId);
    }

    @PutMapping("/{reqId}")
    public RequirementView update(@PathVariable String projectId, @PathVariable String reqId,
                                  @Valid @RequestBody RequirementRequest req) {
        return service.update(projectId, reqId, req);
    }

    /** 状态推进（人工/API 驱动，不限制转换路径） */
    @PutMapping("/{reqId}/status")
    public RequirementView updateStatus(@PathVariable String projectId, @PathVariable String reqId,
                                        @Valid @RequestBody RequirementStatusRequest req) {
        return service.updateStatus(projectId, reqId, req.status());
    }

    @DeleteMapping("/{reqId}")
    public void delete(@PathVariable String projectId, @PathVariable String reqId) {
        service.delete(projectId, reqId);
    }
}
