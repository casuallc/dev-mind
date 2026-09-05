package com.devmind.worklog.controller;

import com.devmind.worklog.dto.RepoView;
import com.devmind.worklog.dto.SubscriptionRequest;
import com.devmind.worklog.service.CodeRepoService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-28 FR-02 本人勾选；CAP-29 起登记 CRUD 移到 /api/repos（project 模块，仅 ADMIN），
 * 这里只保留订阅页合并视图与勾选端点。
 */
@RestController
@RequestMapping("/api/worklog/repos")
public class CodeRepoController {

    private final CodeRepoService service;

    public CodeRepoController(CodeRepoService service) {
        this.service = service;
    }

    /** 全局仓库列表（附本人 subscribed 标记） */
    @GetMapping
    public List<RepoView> list() {
        return service.list();
    }

    /** 本人勾选/取消勾选（任意登录用户） */
    @PutMapping("/{id}/subscription")
    public void subscribe(@PathVariable Long id, @Valid @RequestBody SubscriptionRequest req) {
        service.setSubscription(id, req.subscribed());
    }
}
