package com.devmind.worklog.controller;

import com.devmind.worklog.dto.RepoRequest;
import com.devmind.worklog.dto.RepoView;
import com.devmind.worklog.dto.SubscriptionRequest;
import com.devmind.worklog.service.CodeRepoService;
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
 * CAP-28 FR-01/02：全局代码仓库登记（写操作仅 ADMIN，SecurityConfig 路由规则）与本人勾选。
 */
@RestController
@RequestMapping("/api/worklog/repos")
public class CodeRepoController {

    private final CodeRepoService service;

    public CodeRepoController(CodeRepoService service) {
        this.service = service;
    }

    @GetMapping
    public List<RepoView> list() {
        return service.list();
    }

    @PostMapping
    public RepoView create(@Valid @RequestBody RepoRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public RepoView update(@PathVariable Long id, @Valid @RequestBody RepoRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** 本人勾选/取消勾选（非 ADMIN 可用，SecurityConfig 里 PUT /repos/* 只匹配单段） */
    @PutMapping("/{id}/subscription")
    public void subscribe(@PathVariable Long id, @Valid @RequestBody SubscriptionRequest req) {
        service.setSubscription(id, req.subscribed());
    }
}
