package com.devmind.project.controller;

import com.devmind.project.WorkItemService;
import com.devmind.project.dto.WorkItemBrief;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目级工作单元只读端点：执行器（构建/部署/测试/发版）触发表单的「关联工作单元」选择器数据源。
 * 写操作仍在需求作用域端点（{@link WorkItemController}）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/work-items")
public class ProjectWorkItemController {

    private final WorkItemService service;

    public ProjectWorkItemController(WorkItemService service) {
        this.service = service;
    }

    /** 项目内全部工作单元摘要（含所属需求编号/标题），按 seq 倒序。 */
    @GetMapping
    public List<WorkItemBrief> list(@PathVariable String projectId) {
        return service.briefsByProject(projectId);
    }
}
