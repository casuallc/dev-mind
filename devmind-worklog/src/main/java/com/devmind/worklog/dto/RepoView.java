package com.devmind.worklog.dto;

import com.devmind.common.integration.GitRepoCatalog;

/**
 * CAP-29 起 worklog 订阅页视图：全局登记数据经 GitRepoCatalog SPI 读（project 模块实现），
 * subscribed 为本模块勾选状态。cloneStatus 透出供前端提示「克隆中暂不可扫」。
 */
public record RepoView(Long id, String name, String remoteUrl, String defaultBranch,
                       String status, String cloneStatus, Boolean subscribed) {

    public static RepoView of(GitRepoCatalog.RepoRef r, boolean subscribed) {
        return new RepoView(r.id(), r.name(), r.remoteUrl(), r.defaultBranch(),
                r.status(), r.cloneStatus(), subscribed);
    }
}
