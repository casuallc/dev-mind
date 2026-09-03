package com.devmind.integration.controller;

import com.devmind.integration.dto.UserGitCredentialRequest;
import com.devmind.integration.dto.UserGitCredentialView;
import com.devmind.integration.service.UserGitCredentialService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CAP-24 我的 Git 凭证（FR-01/02）：本人作用域 CRUD + 连通性自检。
 * 任何登录用户可用（ SecurityConfig 默认 authenticated ），服务层以认证上下文 userId 隔离，
 * ADMIN 也不能读他人记录。
 */
@RestController
@RequestMapping("/api/me/git-credentials")
public class UserGitCredentialController {

    private final UserGitCredentialService service;

    public UserGitCredentialController(UserGitCredentialService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserGitCredentialView> list() {
        return service.listMine();
    }

    @PostMapping
    public UserGitCredentialView create(@RequestBody UserGitCredentialRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public UserGitCredentialView update(@PathVariable Long id, @RequestBody UserGitCredentialRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(id);
        return Map.of("ok", true);
    }

    /** FR-02 自检：body {"remoteUrl": "https://<host>/<group>/<repo>.git"}（host 须与凭证一致） */
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Map.of("ok", true, "message", service.test(id, body == null ? null : body.get("remoteUrl")));
    }
}
