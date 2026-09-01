package com.devmind.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * users 表（CAP-01）：登录账户 + 三角色粗粒度 RBAC（单角色列）。
 * role：ADMIN（全权限+用户管理）/ DEVELOPER（业务读写）/ VIEWER（只读）。
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

    /** BCrypt 哈希；null = 不可密码登录（系统身份） */
    @Column(name = "password_hash", length = 128)
    private String passwordHash;

    /** ADMIN / DEVELOPER / VIEWER */
    @Column(length = 32)
    private String role;

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
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
