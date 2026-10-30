package com.devmind.auth.menu;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** FR-01 启动装配：目录落库与默认授权在 JPA 就绪后执行（幂等）。 */
@Component
@Order(10)
public class MenuBootstrap implements ApplicationRunner {

    private final MenuPermissionService menuService;

    public MenuBootstrap(MenuPermissionService menuService) {
        this.menuService = menuService;
    }

    @Override
    public void run(ApplicationArguments args) {
        menuService.syncCatalog();
    }
}
