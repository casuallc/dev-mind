package com.devmind.auth.controller;

import com.devmind.auth.dto.MenuGrantsView;
import com.devmind.auth.dto.MenuItemView;
import com.devmind.auth.dto.SaveMenuGrantsRequest;
import com.devmind.auth.dto.VisibleMenusView;
import com.devmind.auth.menu.MenuPermissionService;
import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-61 菜单端点：/menus 登录即可（驱动前端渲染）；
 * /menus/catalog 与 /menus/grants 仅 ADMIN（过滤器链 + 本类不重复判角色）。
 */
@RestController
@RequestMapping("/api/auth/menus")
public class MenuController {

    private final MenuPermissionService menuService;

    public MenuController(MenuPermissionService menuService) {
        this.menuService = menuService;
    }

    @GetMapping
    public VisibleMenusView menus(@AuthenticationPrincipal DevMindPrincipal principal) {
        if (principal == null) {
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return new VisibleMenusView(menuService.visibleMenus(principal.role()), menuService.knownPaths());
    }

    @GetMapping("/catalog")
    public List<MenuItemView> catalog() {
        return menuService.catalog();
    }

    @GetMapping("/grants")
    public MenuGrantsView grants() {
        return menuService.grants();
    }

    @PutMapping("/grants")
    public MenuGrantsView saveGrants(@Valid @RequestBody SaveMenuGrantsRequest req) {
        return menuService.saveGrants(req.role(), req.menuKeys());
    }
}
