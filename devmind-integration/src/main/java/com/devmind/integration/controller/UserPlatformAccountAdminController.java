package com.devmind.integration.controller;

import com.devmind.integration.dto.AdminPlatformAccountView;
import com.devmind.integration.service.UserPlatformAccountService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CAP-75 FR-14 用户平台账号管理端点（跨用户查看/解绑）。
 * 仅 ADMIN（SecurityConfig 集中登记）；主要服务「机器人运维」页的用户映射视图
 * （处置冒填 open_id、离职账号清理）。
 */
@RestController
@RequestMapping("/api/admin/user-platform-accounts")
public class UserPlatformAccountAdminController {

    private final UserPlatformAccountService service;

    public UserPlatformAccountAdminController(UserPlatformAccountService service) {
        this.service = service;
    }

    /** 全量绑定列表；?type=FEISHU 按集成类型过滤 */
    @GetMapping
    public List<AdminPlatformAccountView> list(@RequestParam(required = false) String type) {
        return service.listAll(type);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> unbind(@PathVariable Long id) {
        service.adminUnbind(id);
        return Map.of("ok", true);
    }
}
