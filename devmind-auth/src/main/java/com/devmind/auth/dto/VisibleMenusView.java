package com.devmind.auth.dto;

import java.util.List;

/**
 * CAP-61 FR-03 当前用户可见菜单。
 * knownPaths = 全量在架菜单路径（不分角色），供前端弱守卫区分
 * 「本来就不是菜单的页面」（放行）与「属于菜单但无权」（提示页）。
 */
public record VisibleMenusView(List<MenuItemView> items, List<String> knownPaths) {
}
