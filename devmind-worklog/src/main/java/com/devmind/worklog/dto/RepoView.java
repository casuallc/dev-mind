package com.devmind.worklog.dto;

import com.devmind.common.integration.GitRepoCatalog;

import java.util.List;

/**
 * CAP-29 起 worklog 订阅页视图：全局登记数据经 GitRepoCatalog SPI 读（project 模块实现），
 * subscribed 为本模块勾选状态。cloneStatus 透出供前端提示「克隆中暂不可扫」。
 * branches 为仓库远程分支列表（订阅分支选择的选项来源）；
 * subscribedBranches 为本人勾选扫描的分支（空 = 跟随默认分支）。
 */
public record RepoView(Long id, String name, String remoteUrl, String defaultBranch,
                       String status, String cloneStatus, Boolean subscribed,
                       List<String> branches, List<String> subscribedBranches) {

    public static RepoView of(GitRepoCatalog.RepoRef r, boolean subscribed, List<String> subscribedBranches) {
        return new RepoView(r.id(), r.name(), r.remoteUrl(), r.defaultBranch(),
                r.status(), r.cloneStatus(), subscribed,
                r.branches() == null ? List.of() : r.branches(),
                subscribedBranches == null ? List.of() : subscribedBranches);
    }
}
