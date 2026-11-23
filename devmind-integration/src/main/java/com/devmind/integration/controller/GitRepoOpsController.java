package com.devmind.integration.controller;

import com.devmind.common.dto.PageView;
import com.devmind.integration.dto.RepoCommitDetailView;
import com.devmind.integration.dto.RepoCommitView;
import com.devmind.integration.dto.RepoFileContentView;
import com.devmind.integration.dto.RepoFileDiffView;
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
 * CAP-113：单文件 diff（file-diff）与原文（file-content）只读端点。
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

    /** CAP-113 FR-01 单文件 diff：unified patch + 增删行数（numstat）+ 二进制/截断标记。 */
    @GetMapping("/{id}/commits/{sha}/file-diff")
    public RepoFileDiffView fileDiff(@PathVariable Long id, @PathVariable String sha,
                                     @RequestParam String path,
                                     @RequestParam(required = false) String oldPath) {
        return opsService.fileDiff(id, sha, path, oldPath);
    }

    /** CAP-113 FR-02 单文件原文：parent=true 取父提交版本（删除文件看删除前内容）。 */
    @GetMapping("/{id}/commits/{sha}/file-content")
    public RepoFileContentView fileContent(@PathVariable Long id, @PathVariable String sha,
                                           @RequestParam String path,
                                           @RequestParam(required = false) Boolean parent) {
        return opsService.fileContent(id, sha, path, Boolean.TRUE.equals(parent));
    }
}
