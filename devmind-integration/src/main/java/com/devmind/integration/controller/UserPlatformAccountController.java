package com.devmind.integration.controller;

import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.dto.PlatformAccountUpsertRequest;
import com.devmind.integration.dto.PlatformAccountView;
import com.devmind.integration.service.UserPlatformAccountService;
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
 * CAP-35 我的第三方账号（FR-01/02）：按 ENABLED 实例列出绑定状态 + upsert 绑定 + 自检。
 * 任何登录用户可用（SecurityConfig 默认 authenticated），服务层以认证上下文 userId 隔离，
 * ADMIN 也不能读他人记录。
 */
@RestController
@RequestMapping("/api/me/platform-accounts")
public class UserPlatformAccountController {

    private final UserPlatformAccountService service;

    public UserPlatformAccountController(UserPlatformAccountService service) {
        this.service = service;
    }

    @GetMapping
    public List<PlatformAccountView> list() {
        return service.listOverview();
    }

    @PutMapping("/{integrationId}")
    public PlatformAccountView upsert(@PathVariable Long integrationId,
                                      @RequestBody PlatformAccountUpsertRequest req) {
        return service.upsert(integrationId, req);
    }

    @DeleteMapping("/{integrationId}")
    public Map<String, Object> unbind(@PathVariable Long integrationId) {
        service.unbind(integrationId);
        return Map.of("ok", true);
    }

    /** FR-02 自检：以我的凭据走实例对应 Connector 的 testConnection */
    @PostMapping("/{integrationId}/test")
    public IntegrationConnector.TestResult test(@PathVariable Long integrationId) {
        return service.test(integrationId);
    }
}
