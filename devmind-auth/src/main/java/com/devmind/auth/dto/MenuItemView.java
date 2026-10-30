package com.devmind.auth.dto;

/** CAP-61 菜单项视图；CAP-103 增节点类型/父级/写操作标记，前端据此建权限树与显隐。 */
public record MenuItemView(String menuKey, String title, String path, String zone,
                           String groupName, String icon, int sortNo, boolean retired,
                           String nodeType, String parentKey, boolean writeAction) {
}
