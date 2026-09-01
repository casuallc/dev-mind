package com.devmind.openapi.controller;

import com.devmind.openapi.dto.ApiKeyView;
import com.devmind.openapi.dto.IssueKeyRequest;
import com.devmind.openapi.dto.IssuedKeyView;
import com.devmind.openapi.model.ApiKeyEntity;
import com.devmind.openapi.service.ApiKeyService;
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
import java.util.Map;

/**
 * API Key 管理端点（CAP-20）：走现有 JWT 会话认证，SecurityConfig 限定仅 ADMIN。
 * 注意与 /open-api/**（HMAC 认证）区分——这里是管控台管理面，不是开放面。
 */
@RestController
@RequestMapping("/api/open-keys")
public class ApiKeyAdminController {

    private final ApiKeyService service;

    public ApiKeyAdminController(ApiKeyService service) {
        this.service = service;
    }

    @GetMapping
    public List<ApiKeyView> list() {
        return service.list();
    }

    /** 签发：secret 明文仅此响应可见 */
    @PostMapping
    public IssuedKeyView issue(@Valid @RequestBody IssueKeyRequest req) {
        Object[] issued = service.issue(req.name(), req.expiresAt());
        return new IssuedKeyView(ApiKeyService.toView((ApiKeyEntity) issued[1]), (String) issued[0]);
    }

    @PutMapping("/{id}")
    public ApiKeyView setEnabled(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        return service.setEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
