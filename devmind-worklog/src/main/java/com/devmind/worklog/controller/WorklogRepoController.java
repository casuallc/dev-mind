package com.devmind.worklog.controller;

import com.devmind.common.agent.WorklogPushResult;
import com.devmind.worklog.service.WorklogRemoteBackupService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-74：工作日志服务端仓的仓级操作（冲突显式解决等）。
 */
@RestController
@RequestMapping("/api/worklog/repos")
public class WorklogRepoController {

    private final WorklogRemoteBackupService backupService;

    public WorklogRepoController(WorklogRemoteBackupService backupService) {
        this.backupService = backupService;
    }

    /**
     * push 409 冲突后的显式解决：strategy=ours（以平台仓为准）/ theirs（以远端为准）。
     * 锁内重新 fetch + merge -X strategy + push；actor 必须是仓成员（否则 403）。
     */
    @PostMapping("/{key}/resolve")
    public WorklogPushResult resolve(@PathVariable("key") String repoKey,
                                     @RequestParam("strategy") String strategy) {
        return backupService.resolveConflict(repoKey, strategy);
    }
}
