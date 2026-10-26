package com.devmind.worklog.controller;

import com.devmind.worklog.dto.WorkspaceView;
import com.devmind.worklog.service.WorklogWorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-41：工作日志空间查询与懒初始化。
 * CAP-89：push/resolve 端点随 git 仓链路退役删除（远端推送整体下线，见 CAP-89 §4）。
 */
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
