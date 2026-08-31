package com.devmind.project.controller;

import com.devmind.project.TaskService;
import com.devmind.project.dto.TaskRequest;
import com.devmind.project.dto.TaskStatusRequest;
import com.devmind.project.dto.TaskView;
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
 * Task 主线 REST API：项目内主线工作项（身份 + 状态 + 关联）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/tasks")
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @GetMapping
    public List<TaskView> list(@PathVariable String projectId,
                               @RequestParam(required = false) String status) {
        return service.list(projectId, status);
    }

    @PostMapping
    public TaskView create(@PathVariable String projectId, @Valid @RequestBody TaskRequest req) {
        return service.create(projectId, req);
    }

    @GetMapping("/{taskId}")
    public TaskView get(@PathVariable String projectId, @PathVariable String taskId) {
        return service.get(projectId, taskId);
    }

    @PutMapping("/{taskId}")
    public TaskView update(@PathVariable String projectId, @PathVariable String taskId,
                           @Valid @RequestBody TaskRequest req) {
        return service.update(projectId, taskId, req);
    }

    /** 状态推进（人工/API 驱动，不限制转换路径） */
    @PutMapping("/{taskId}/status")
    public TaskView updateStatus(@PathVariable String projectId, @PathVariable String taskId,
                                 @Valid @RequestBody TaskStatusRequest req) {
        return service.updateStatus(projectId, taskId, req.status());
    }

    @DeleteMapping("/{taskId}")
    public void delete(@PathVariable String projectId, @PathVariable String taskId) {
        service.delete(projectId, taskId);
    }
}
