package com.devmind.auth.controller;

import com.devmind.auth.RoleService;
import com.devmind.auth.dto.CreateRoleRequest;
import com.devmind.auth.dto.RoleView;
import com.devmind.auth.dto.UpdateRoleRequest;
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

/** 角色管理（仅 ADMIN，过滤器链控制）；菜单授权仍走 /api/auth/menus/grants。 */
@RestController
@RequestMapping("/api/auth/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    public List<RoleView> list() {
        return roleService.list();
    }

    @PostMapping
    public RoleView create(@Valid @RequestBody CreateRoleRequest req) {
        return roleService.create(req);
    }

    @PutMapping("/{code}")
    public RoleView update(@PathVariable String code, @Valid @RequestBody UpdateRoleRequest req) {
        return roleService.update(code, req);
    }

    @DeleteMapping("/{code}")
    public void delete(@PathVariable String code) {
        roleService.delete(code);
    }
}
