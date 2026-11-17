package com.devmind.integration.controller;

import com.devmind.integration.dto.RepoCheckoutRequest;
import com.devmind.integration.dto.RepoCommitView;
import com.devmind.integration.service.GitRepoOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CAP-109 FR-04/05 代码库 git 操作端点（/api/repos 前缀与 project 模块 GitRepoController 相同）：
 * 提交历史查看（GET，全认证用户可读）+ 分支切换（POST，ADMIN 由 SecurityConfig 路由规则约束）。
 */
@RestController
@RequestMapping("/api/repos")
public class GitRepoOpsController {

    private final GitRepoOpsService opsService;

    public GitRepoOpsController(GitRepoOpsService opsService) {
        this.opsService = opsService;
    }

    /** 分支提交历史：branch 缺省 = 实体默认分支；limit 缺省 30、硬上限 200。 */
    @GetMapping("/{id}/commits")
    public List<RepoCommitView> commits(@PathVariable Long id,
                                        @RequestParam(required = false) String branch,
                                        @RequestParam(required = false) Integer limit) {
        return opsService.listCommits(id, branch, limit);
    }

    /** 切换服务端克隆的本地检出分支（仅 CLONE + READY；LOCAL 行 400）。 */
    @PostMapping("/{id}/checkout")
    public Map<String, Object> checkout(@PathVariable Long id, @RequestBody RepoCheckoutRequest req) {
        opsService.checkout(id, req.branch());
        return Map.of("ok", true);
    }
}
