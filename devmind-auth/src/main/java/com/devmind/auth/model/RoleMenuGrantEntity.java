package com.devmind.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * CAP-61 角色×菜单可见性（role_menu_grants）：存在即该角色可见该菜单（白名单语义）。
 * ADMIN 恒见全部菜单，不走本表短路判定。
 */
@Entity
@Table(name = "role_menu_grants",
        uniqueConstraints = @UniqueConstraint(name = "uk_role_menu_grants_role_key",
                columnNames = {"role", "menu_key"}))
public class RoleMenuGrantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(name = "menu_key", nullable = false, length = 64)
    private String menuKey;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getMenuKey() { return menuKey; }
    public void setMenuKey(String menuKey) { this.menuKey = menuKey; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
