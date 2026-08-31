package com.devmind.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * users 表（P0-2 Identity 最小版）：先有 actor 身份，登录鉴权后续在 CAP-01 补齐。
 * 本地单用户阶段只有一行种子用户 local。
 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "display_name", length = 128)
    private String displayName;

    /** OWNER / MEMBER / VIEWER（最小版仅记录，不做鉴权拦截） */
    @Column(length = 32)
    private String role;

    @Column(name = "created_at")
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
