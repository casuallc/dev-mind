# CAP-01 用户认证与权限

> 能力 ID：CAP-01 ｜ 分类：管理 ｜ 状态：草案 ｜ 日期：2026-08-30

## 1. 目的

为平台提供身份认证与角色权限基础。本地优先、单用户起步，但预留多角色与多人协作的扩展能力；所有写操作记录操作者（actor），作为审计基础。

## 2. 功能需求

- **FR-01 登录/登出**：用户名 + 密码登录，签发 JWT；登出作废 token。支持"记住登录"。
- **FR-02 当前用户**：`GET /auth/me` 返回当前用户信息与角色。
- **FR-03 角色定义**：预设 `ADMIN`（全部操作）、`DEVELOPER`（开发/验证/发版操作）、`VIEWER`（只读）。角色可后续扩充。
- **FR-04 权限校验**：后端接口按角色拦截（注解/切面），前端路由与按钮按角色渲染；未授权返回 403。
- **FR-05 actor 记录**：所有写操作在审计日志中记录当前用户（CAP-06 通知也以当前用户作为接收者解析依据）。
- **FR-06 用户管理**（仅 ADMIN）：增删用户、改角色、重置密码。
- **FR-07 令牌有效期与刷新**：access token 短期 + refresh token 长期，支持刷新。

## 3. 插件化接口

- 认证方式 SPI：`AuthenticationProvider`，默认实现=用户名密码 + JWT；后续可加 LDAP/SSO 实现。
- 权限注解：提供 `@RequireRole(...)` 注解，供所有能力接口复用。
- 当前用户上下文：`SecurityContext` 中提供 `CurrentUser(id, name, roles)`，供各能力取 actor。

## 4. 依赖关系

- 无外部依赖；被**全部**其它能力依赖（校验身份 + 提供 actor）。

## 5. 数据模型

```
users(id, username, password_hash, display_name, status[active|disabled], created_at)
roles(id, code, name, description)
user_roles(user_id, role_id)
refresh_tokens(id, user_id, token_hash, expires_at)
```

## 6. API 概要

```
POST /auth/login            登录，返回 access+refresh
POST /auth/refresh          刷新 token
POST /auth/logout           登出
GET  /auth/me               当前用户信息
ADMIN: GET/POST/PUT /admin/users, POST /admin/users/{id}/reset-password
```

## 7. 验收标准

- 未登录访问受保护接口返回 401；越权角色返回 403；
- 登录后可获取 `GET /auth/me` 正确返回角色；
- 前端未授权入口不可见；
- 每个写操作在审计日志中有 actor 记录。

## 8. MVP 范围（暂不做）

SSO / LDAP 集成、多租户、密码找回流程、操作级细粒度权限（先角色级）。
