package com.devmind.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * roles 表：可管理的角色定义。code 为稳定标识（用户 role_code、菜单授权都引用它）；
 * base_role 是该角色对应的后端接口权限级别（ADMIN/DEVELOPER/VIEWER）。
 * 内置 ADMIN/DEVELOPER/VIEWER 不可删除；自定义角色只能选择 DEVELOPER/VIEWER 级别。
 */
@Entity
@Table(name = "roles")
public class RoleEntity {

    public static final String CODE_ADMIN = "ADMIN";
    public static final String CODE_DEVELOPER = "DEVELOPER";
    public static final String CODE_VIEWER = "VIEWER";

    @Id
    @Column(length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 512)
    private String description;

    /** 内置角色不可编辑/删除 */
    @Column(name = "built_in", nullable = false)
    private boolean builtin;

    /** 后端接口权限级别：ADMIN / DEVELOPER / VIEWER */
    @Column(name = "base_role", nullable = false, length = 16)
    private String baseRole;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }
    public String getBaseRole() { return baseRole; }
    public void setBaseRole(String baseRole) { this.baseRole = baseRole; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
