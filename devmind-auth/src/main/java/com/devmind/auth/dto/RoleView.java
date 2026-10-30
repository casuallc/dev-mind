package com.devmind.auth.dto;

import java.time.Instant;
import java.util.List;

/** 角色视图：基础权限级别 + 用户数 + 当前菜单授权（ADMIN 恒为全部在架菜单）。 */
public record RoleView(String code, String name, String description, boolean builtin, String baseRole,
                       long userCount, List<String> menuKeys, Instant createdAt, Instant updatedAt) {
}
