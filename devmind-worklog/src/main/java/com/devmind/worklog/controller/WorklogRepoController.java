package com.devmind.worklog.controller;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.WorklogPushResult;
import com.devmind.worklog.service.WorklogRemoteBackupService;
import com.devmind.worklog.service.WorklogRepoService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-74：工作日志服务端仓的仓级操作——冲突显式解决（M1）、团队仓成员管理与仓列表（M2）。
 */
@RestController
@RequestMapping("/api/worklog/repos")
public class WorklogRepoController {

    private final WorklogRemoteBackupService backupService;
    private final WorklogRepoService repoService;
    private final IdentityService identity;

    public WorklogRepoController(WorklogRemoteBackupService backupService,
                                 WorklogRepoService repoService, IdentityService identity) {
        this.backupService = backupService;
        this.repoService = repoService;
        this.identity = identity;
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

    /** 我所属的仓列表（设置页「活动仓」切换数据源）。 */
    @GetMapping("/mine")
    public List<WorklogRepoService.RepoSummary> mine() {
        return repoService.listMyRepos(identity.currentActor());
    }

    /** 仓成员列表（成员可见；owner 标记供前端控制删除按钮显隐）。 */
    @GetMapping("/{key}/members")
    public List<WorklogRepoService.RepoMemberView> members(@PathVariable("key") String repoKey) {
        return repoService.listMembers(repoKey, identity.currentActor());
    }

    /** 仓主加成员（按 username；锁内先写成员骨架子目录 commit 再落成员行）。 */
    @PostMapping("/{key}/members")
    public List<WorklogRepoService.RepoMemberView> addMember(@PathVariable("key") String repoKey,
                                                             @RequestParam("username") String username) {
        String me = identity.currentActor();
        repoService.addMember(repoKey, me, username);
        return repoService.listMembers(repoKey, me);
    }

    /** 仓主移除成员（仅删成员行，仓内历史文件保留）。 */
    @DeleteMapping("/{key}/members/{username}")
    public List<WorklogRepoService.RepoMemberView> removeMember(@PathVariable("key") String repoKey,
                                                                @PathVariable("username") String username) {
        String me = identity.currentActor();
        repoService.removeMember(repoKey, me, username);
        return repoService.listMembers(repoKey, me);
    }
}
