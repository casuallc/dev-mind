package com.devmind.worklog.controller;

import com.devmind.worklog.dto.WorkspaceView;
import com.devmind.worklog.service.WorklogWorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CAP-41：工作日志空间（WORKLOG 特殊项目）查询与懒初始化。 */
@RestController
@RequestMapping("/api/worklog/workspace")
public class WorklogWorkspaceController {

    private final WorklogWorkspaceService service;

    public WorklogWorkspaceController(WorklogWorkspaceService service) {
        this.service = service;
    }

    @GetMapping
    public WorkspaceView get() {
        return service.get();
    }

    @PostMapping("/ensure")
    public WorkspaceView ensure() {
        return service.ensure();
    }
}
