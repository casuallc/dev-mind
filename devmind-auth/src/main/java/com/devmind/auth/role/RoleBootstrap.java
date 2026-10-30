package com.devmind.auth.role;

import com.devmind.auth.RoleService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 内置角色种子必须先于菜单目录同步（菜单默认授权要读取角色清单）。 */
@Component
@Order(0)
public class RoleBootstrap implements ApplicationRunner {

    private final RoleService roleService;

    public RoleBootstrap(RoleService roleService) {
        this.roleService = roleService;
    }

    @Override
    public void run(ApplicationArguments args) {
        roleService.seedBuiltinRoles();
    }
}
