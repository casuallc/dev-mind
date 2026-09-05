package com.devmind.integration.controller;

import com.devmind.integration.service.GitRepoSyncService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CAP-29 全局仓库同步端点（/api/repos 前缀与 project 模块 GitRepoController 相同，
 * 写操作 ADMIN 由 SecurityConfig 路由规则约束）：手动抓取 / 克隆重试。
 */
@RestController
@RequestMapping("/api/repos")
public class GitRepoSyncController {

    private final GitRepoSyncService syncService;

    public GitRepoSyncController(GitRepoSyncService syncService) {
        this.syncService = syncService;
    }

    /** 手动立即抓取远端分支/默认分支（CLONE 且 READY；同步执行） */
    @PostMapping("/{id}/fetch")
    public Map<String, Object> fetch(@PathVariable Long id) {
        syncService.fetchOne(id);
        return Map.of("ok", true);
    }

    /** 克隆失败重试 / 强制重新克隆（异步，CLONING 中返回 409） */
    @PostMapping("/{id}/clone")
    public Map<String, Object> clone(@PathVariable Long id) {
        syncService.requestClone(id);
        return Map.of("ok", true, "async", true);
    }
}
