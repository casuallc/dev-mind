package com.devmind.worklog.controller;

import com.devmind.worklog.dto.WorkspaceView;
import com.devmind.worklog.service.WorklogWorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CAP-41：工作日志空间查询与懒初始化；CAP-74：事实源改服务端仓，push 改服务端直推（不再下发 runner）。 */
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

    /**
     * CAP-74：服务端直推个人仓到已绑定远端（fetch+merge+push；分叉冲突 409，
     * 走 POST /api/worklog/repos/{key}/resolve 显式解决）。未绑定远端 400、缺凭证 409。
     */
    @PostMapping("/push")
    public com.devmind.common.agent.WorklogPushResult push() {
        return backupService.pushToRemote();
    }
}
