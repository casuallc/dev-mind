package com.devmind.project.controller;

import com.devmind.project.WorkItemService;
import com.devmind.project.dto.StatusRequest;
import com.devmind.project.dto.WorkItemRequest;
import com.devmind.project.dto.WorkItemView;
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
 * Work Item REST API（CAP-13 研发主线）：需求下的工作单元。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/requirements/{requirementId}/work-items")
public class WorkItemController {

    private final WorkItemService service;

    public WorkItemController(WorkItemService service) {
        this.service = service;
    }

    @GetMapping
    public List<WorkItemView> list(@PathVariable String projectId, @PathVariable String requirementId) {
        return service.list(projectId, requirementId);
    }

    @PostMapping
    public WorkItemView create(@PathVariable String projectId, @PathVariable String requirementId,
                               @Valid @RequestBody WorkItemRequest req) {
        return service.create(projectId, requirementId, req);
    }

    @GetMapping("/{workItemId}")
    public WorkItemView get(@PathVariable String projectId, @PathVariable String requirementId,
                            @PathVariable String workItemId) {
        return service.get(projectId, requirementId, workItemId);
    }

    @PutMapping("/{workItemId}")
    public WorkItemView update(@PathVariable String projectId, @PathVariable String requirementId,
                               @PathVariable String workItemId, @Valid @RequestBody WorkItemRequest req) {
        return service.update(projectId, requirementId, workItemId, req);
    }

    /** 状态推进（人工/API 驱动，不限制转换路径；推进后触发需求 rollup） */
    @PutMapping("/{workItemId}/status")
    public WorkItemView updateStatus(@PathVariable String projectId, @PathVariable String requirementId,
                                     @PathVariable String workItemId, @Valid @RequestBody StatusRequest req) {
        return service.updateStatus(projectId, requirementId, workItemId, req.status());
    }

    @DeleteMapping("/{workItemId}")
    public void delete(@PathVariable String projectId, @PathVariable String requirementId,
                       @PathVariable String workItemId) {
        service.delete(projectId, requirementId, workItemId);
    }
}
