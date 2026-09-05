package com.devmind.project.controller;

import com.devmind.project.GitRepoService;
import com.devmind.project.dto.GitRepoRequest;
import com.devmind.project.dto.GitRepoView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-29 全局代码仓库：列表/详情全认证用户可读（worklog 订阅页用），
 * 写操作仅 ADMIN（SecurityConfig 路由规则）。fetch/clone 触发端点在 integration 模块。
 */
@RestController
@RequestMapping("/api/repos")
public class GitRepoController {

    private final GitRepoService service;

    public GitRepoController(GitRepoService service) {
        this.service = service;
    }

    @GetMapping
    public List<GitRepoView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public GitRepoView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public GitRepoView create(@Valid @RequestBody GitRepoRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public GitRepoView update(@PathVariable Long id, @Valid @RequestBody GitRepoRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
