# CAP-01 用户认证与权限

> 能力 ID：CAP-01 ｜ 分类：管理 ｜ 状态：已实现 ｜ 日期：2026-08-30（2026-09-01 落地）

## 1. 目的

为平台提供身份认证与角色权限基础。本地优先、单用户起步，但预留多角色与多人协作的扩展能力；所有写操作记录操作者（actor），作为审计基础。

## 2. 功能需求

- **FR-01 登录/登出**：用户名 + 密码登录，签发 JWT（HS256 access token 2h + refresh token 14d）；登出作废 refresh token。
- **FR-02 当前用户**：`GET /api/auth/me` 返回当前用户信息与角色。
- **FR-03 角色定义**：预设 `ADMIN`（全部操作 + 用户管理）、`DEVELOPER`（业务读写）、`VIEWER`（只读）。角色可后续扩充。
- **FR-04 权限校验**：后端过滤器链按角色拦截——`/api/**` 读方法三角色均可、写方法需 ADMIN/DEVELOPER、`/api/auth/users/**` 仅 ADMIN；未认证 401、越权 403（统一 `ApiError` 结构）。前端路由守卫 + 菜单/按钮按角色渲染。
- **FR-05 actor 记录**：`IdentityService.currentActor()` 从 SecurityContext 取当前用户名；无认证上下文（异步线程/事件/启动种子）回退 `"local"`。各模块写操作 actor 统一走该接入点。
- **FR-06 用户管理**（仅 ADMIN）：增删用户、改角色/状态、重置密码；用户可自助改密码。启动时无 ADMIN 则种子 `admin`（初始密码 `devmind.auth.admin-password`，默认 `admin123`，日志提示修改）。
- **FR-07 令牌有效期与刷新**：`/api/auth/refresh` 轮换（旧 refresh 一次性作废）。

## 3. 插件化接口

- 认证方式 SPI：`AuthenticationProvider`，默认实现=用户名密码 + JWT；后续可加 LDAP/SSO 实现。
- 权限注解：提供 `@RequireRole(...)` 注解，供所有能力接口复用（MVP 用过滤器链 HTTP 方法粗粒度拦截，注解留给例外端点）。
- 当前用户上下文：SecurityContext 中放 `DevMindPrincipal(username, role)`，`IdentityService.currentActor()` 是唯一 actor 接入点。

## 4. 依赖关系

- 无外部依赖；被**全部**其它能力依赖（校验身份 + 提供 actor）。
- JWT 编解码自实现（JDK `javax.crypto` HmacSHA256，~60 行），不引第三方 JWT 库——规避公司 Nexus 缺包风险；密钥取 `devmind.auth.jwt-secret`，空则生成并持久化到 `data/auth.key`（仿 CAP-07 CredentialCrypto）。

## 5. 数据模型

```
users(id, username, password_hash, display_name, role[ADMIN|DEVELOPER|VIEWER], status[ACTIVE|DISABLED], created_at)
refresh_tokens(id, user_id, token_hash, expires_at, revoked)
```

说明：三角色粗粒度下用 `users.role` 单角色列（@Enumerated STRING），不建 roles/user_roles 独立表；旧枚举值启动迁移（OWNER→ADMIN、MEMBER→DEVELOPER）。`local` 用户保留为系统身份（无密码不可登录）。

## 6. API 概要

```
POST /api/auth/login            登录，返回 access+refresh+user
POST /api/auth/refresh          刷新（轮换，旧 refresh 作废）
POST /api/auth/logout           登出（作废 refresh）
GET  /api/auth/me               当前用户信息
POST /api/auth/change-password  自助改密码
ADMIN: GET/POST/PUT /api/auth/users, POST /api/auth/users/{id}/reset-password
```

permitAll：`/api/auth/login`、`/api/auth/refresh`、`/health`、`/h2-console/**`、`/ws/**`。

## 7. 验收标准

- 未登录访问受保护接口返回 401（DEV-401）；越权角色返回 403；
- 登录后 `GET /api/auth/me` 正确返回角色；VIEWER 写操作 403、DEVELOPER 用户管理 403；
- refresh 轮换后旧 token 复用 401；logout 后 refresh 401；
- 前端未登录跳 /login；非 ADMIN 不见用户管理入口；
- 写操作 actor 为真实用户名；异步线程/事件回退 "local" 不炸。

## 8. MVP 范围（暂不做）

- SSO / LDAP 集成、多租户、密码找回流程、操作级细粒度权限（先角色级）。
- WebSocket 握手鉴权（MVP `/ws/**` permitAll；后续方案：握手 query param 带 token + HandshakeInterceptor 校验）。
- 通知按用户分发（通知偏好仍 keyed "local"）；API token 的 HTTP 端点（ApiTokenEntity 已有签发/校验能力，留给脚本集成）。
