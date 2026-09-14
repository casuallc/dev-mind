package com.devmind.worklog.controller;

import com.devmind.worklog.dto.WorkspaceView;
import com.devmind.worklog.service.WorklogWorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CAP-41：工作日志空间（WORKLOG 特殊项目）查询与懒初始化；M3：远端备份手动推送。 */
@RestController
@RequestMapping("/api/worklog/workspace")
public class WorklogWorkspaceController {

    private final WorklogWorkspaceService service;
    private final com.devmind.worklog.service.WorklogRemoteBackupService backupService;

    public WorklogWorkspaceController(WorklogWorkspaceService service,
                                      com.devmind.worklog.service.WorklogRemoteBackupService backupService) {
        this.service = service;
        this.backupService = backupService;
    }

    @GetMapping
    public WorkspaceView get() {
        return service.get();
    }

    @PostMapping("/ensure")
    public WorkspaceView ensure() {
        return service.ensure();
    }

    /** CAP-41 M3：把本人工作日志空间 push 到设置里绑定的远端仓库（阻塞等 runner ack）。 */
    @PostMapping("/push")
    public com.devmind.common.agent.WorklogPushResult push() {
        return backupService.pushToRemote();
    }
}
