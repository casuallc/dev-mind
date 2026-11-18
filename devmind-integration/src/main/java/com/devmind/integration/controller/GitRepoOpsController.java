package com.devmind.integration.controller;

import com.devmind.common.dto.PageView;
import com.devmind.integration.dto.RepoCommitDetailView;
import com.devmind.integration.dto.RepoCommitView;
import com.devmind.integration.service.GitRepoOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-111 代码库提交日志端点（/api/repos 前缀与 project 模块 GitRepoController 相同，
 * GET 全认证用户可读）：提交日志分页+搜索（FR-03/05）+ 提交详情（FR-04）。
 * CAP-109 FR-05 的分支切换端点已随 CAP-111 下线。
 */
@RestController
@RequestMapping("/api/repos")
public class GitRepoOpsController {

    private final GitRepoOpsService opsService;

    public GitRepoOpsController(GitRepoOpsService opsService) {
        this.opsService = opsService;
    }

    /**
     * 分支提交日志分页：branch 缺省 = 实体默认分支；keyword 匹配提交信息（包含）或
     * 提交哈希（前缀）；page 从 0 起，size 默认 20、硬上限 100。
     */
    @GetMapping("/{id}/commits")
    public PageView<RepoCommitView> commits(@PathVariable Long id,
                                            @RequestParam(required = false) String branch,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return opsService.listCommits(id, branch, keyword, page, size);
    }

    /** 提交详情：完整提交信息 + 变更文件清单。 */
    @GetMapping("/{id}/commits/{sha}")
    public RepoCommitDetailView commitDetail(@PathVariable Long id, @PathVariable String sha) {
        return opsService.commitDetail(id, sha);
    }
}
