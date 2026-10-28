package com.devmind.auth.dto;

/** CAP-61 菜单项视图：前端按 path 渲染、按 menuKey 授权。 */
public record MenuItemView(String menuKey, String title, String path, String zone,
                           String groupName, String icon, int sortNo, boolean retired) {
}
