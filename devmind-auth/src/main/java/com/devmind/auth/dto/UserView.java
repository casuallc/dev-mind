package com.devmind.auth.dto;

import java.time.Instant;

/** 用户视图：role 为基础权限级别，roleCode/roleName 为可管理角色（菜单权限按 roleCode 判定）。 */
public record UserView(String id, String username, String displayName, String description, String role,
                       String roleCode, String roleName, String status, Instant createdAt) {
}
