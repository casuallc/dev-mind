package com.devmind.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * users 表（CAP-01）：登录账户 + 基础权限级别（单角色列）。
 * role：ADMIN（全权限+用户管理）/ DEVELOPER（业务读写）/ VIEWER（只读），由所分配角色的 base_role 决定；
 * roleCode：所分配的可管理角色编码（roles.code），菜单权限按它判定。
 * 无密码的用户（如系统身份 local）不可登录。
 */
@Entity
@Table(name = "users")
public class UserEntity {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_DEVELOPER = "DEVELOPER";
    public static final String ROLE_VIEWER = "VIEWER";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "display_name", length = 128)
    private String displayName;

    /** 描述/备注（如人员职责），管理页可编辑 */
    @Column(length = 512)
    private String description;

    /** BCrypt 哈希；null = 不可密码登录（系统身份） */
    @Column(name = "password_hash", length = 128)
    private String passwordHash;

    /** ADMIN / DEVELOPER / VIEWER（角色对应的基础接口权限级别） */
    @Column(length = 32)
    private String role;

    /** 所分配角色的编码（roles.code）；老数据启动时回填为 role */
    @Column(name = "role_code", length = 32)
    private String roleCode;

    /** ACTIVE / DISABLED */
    @Column(length = 16)
    private String status;

    @Column(name = "created_at")
    private Instant createdAt;

    public boolean isActive() {
        return status == null || STATUS_ACTIVE.equals(status);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
