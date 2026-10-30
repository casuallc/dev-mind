# CAP-102 角色管理与用户管理双页签

> 能力 ID：CAP-102 ｜ 分类：管理 ｜ 状态：已实现（2026-10-30） ｜ 日期：2026-10-30
> 依赖：CAP-01（认证、用户管理）、CAP-61（菜单目录与角色菜单授权）。
> 决策记录：
> 1. 角色改为可管理实体（`roles` 表），不再写死枚举；内置 ADMIN/DEVELOPER/VIEWER 保留且不可删改。
> 2. 自定义角色只能选择 DEVELOPER/VIEWER 两档基础权限，ADMIN 仅限内置角色——避免自定义角色变相获得管理员接口权限。
> 3. `users.role`（后端接口权限）与新增 `users.role_code`（角色标识）双字段并存：既有过滤器链与数据可见性判断零改动，菜单可见性按 `role_code` 判定。
> 4. 用户管理与角色管理合并为一个后台菜单「用户管理」，页内两个页签；原独立菜单权限矩阵页并入角色页签。

## 1. 目的

CAP-01 的三角色（ADMIN/DEVELOPER/VIEWER）与 CAP-61 的菜单授权矩阵都建立在固定角色枚举上：
新增岗位（如测试负责人、只读审计）只能复用三角色之一，无法表达「同为基础读写、但菜单不同」的诉求；
菜单权限矩阵也只认三个固定角色。本能力将角色提升为一等资源：

- 角色可增删改，每个角色绑定一档基础接口权限 + 一组可见菜单；
- 用户分配角色编码，登录后菜单按角色授权渲染；
- 用户管理与角色管理收拢到同一后台菜单，减少管理入口分散。

**边界声明**：角色管理改变的是「用户的角色与菜单可见性」，不改变后端接口鉴权口径——
接口权限仍由角色的基础权限（ADMIN/DEVELOPER/VIEWER）通过既有过滤器链判定；
菜单权限仍是展示层管控，不是安全边界（同 CAP-61）。

## 2. 功能需求

### FR-01 角色实体与内置种子

- 新表 `roles`：`code`（稳定标识，主键）、`name`、`description`、`built_in`、`base_role`、
  创建/更新时间；
- 角色编码规则：`^[A-Z][A-Z0-9_]{1,15}$`（2~16 位大写字母/数字/下划线，字母开头），
  与 `role_menu_grants.role` 列宽一致；提交小写自动转大写返回；
- 内置 ADMIN/DEVELOPER/VIEWER 由启动种子保证存在：`RoleBootstrap`（`@Order(0)`）校正名称、
  说明、`built_in=true` 与固定基础权限（管理员手改会被纠正），并先于菜单目录同步
  （`MenuBootstrap` `@Order(10)`）执行；
- 存量用户迁移：`role_code` 为空的用户按 `users.role` 回填对应内置角色编码。

### FR-02 角色 CRUD（仅 ADMIN）

- `GET /api/auth/roles`：全部角色（内置在前），每项带基础权限、用户数、当前菜单编码集合
  （内置 ADMIN 恒为全部在架菜单）；
- `POST /api/auth/roles`：创建自定义角色。编码重复返回 409；基础权限仅允许
  DEVELOPER/VIEWER，ADMIN 返回 400；新建角色默认授予 TOP + PROJECT 区全部在架菜单
  （不含 ADMIN 区，避免默认进入后台）；
- `PUT /api/auth/roles/{code}`：修改名称、说明、基础权限。内置角色拒绝（400）；
  基础权限变更会同步该角色下所有用户的 `users.role`，令牌在下次登录/刷新后生效；
- `DELETE /api/auth/roles/{code}`：内置角色拒绝；仍有用户使用时拒绝并返回人数（400）；
  删除成功同时清理该角色的 `role_menu_grants`，不留孤儿授权。

### FR-03 用户与角色绑定

- `users` 增加 `role_code` 列引用 `roles.code`；`users.role` 继续保存该角色对应的基础权限，
  供既有过滤器链与数据可见性判断使用；
- 用户创建/编辑请求的 `role` 字段语义改为「角色编码」，服务端按 `RoleService.require`
  大小写不敏感解析，写入 `role_code` 与解析出的基础权限；
- JWT 增加 `roleCode` claim；旧 token 无该 claim 时回退到 `role`，避免升级即全员掉线；
- `GET /api/auth/me` 与用户列表返回 `role`（基础权限）、`roleCode`、`roleName`；
- 防锁死规则保持：不允许禁用/降级最后一个可登录 ADMIN；判定按角色的基础权限而非编码。

### FR-04 角色菜单权限

- 菜单授权复用 CAP-61 的 `role_menu_grants(role, menu_key)`，`role` 列引用角色编码；
- `PUT /api/auth/menus/grants` 保存前校验角色存在（未知编码 400）、拒绝 ADMIN
  （恒见全部菜单，不可编辑）；
- `GET /api/auth/menus` 判定链：内置 ADMIN → 全部在架菜单；其他角色（含自定义）→
  在架目录 ∩ 该角色授权；自定义角色与内置角色走同一条路径；
- 菜单目录移除 `admin.menus`（独立矩阵页下线），其余菜单不变。

### FR-05 用户管理双页签（前端）

- 后台「系统」分组菜单改为「用户管理」（`admin.users`），页面内两个页签：
  - **用户**：原用户管理功能（新建/编辑/重置密码/启停/删除），角色列显示角色名称 +
    基础权限标签，分配角色用下拉选择（含自定义角色，ADMIN 排前）；
  - **角色**：角色列表（内置标记、基础权限、说明、用户数、菜单数、更新时间），
    新建/编辑角色（编码创建后不可改），删除二次确认（有用户时确认按钮禁用并提示人数），
    「菜单权限」抽屉按 TOP/PROJECT/ADMIN 三区勾选（支持整区全选/半选），保存即生效；
- 页签状态写入 `?tab=roles`，支持深链与刷新保持；切换页签关闭未完成的新建弹窗；
- 原 `/admin/menus` 路由重定向到 `/admin/users?tab=roles`，`MenuPermissionsPage` 删除；
- 顶部用户菜单与个人资料页展示角色名称（附基础权限），不再映射固定角色枚举文案。

## 3. 插件化接口

无新增 SPI。角色清单由 `RoleService` 统一提供；`MenuPermissionService` 通过
`RoleRepository` 读取角色，不再依赖固定枚举。其他模块判断接口权限继续读 `users.role`
（或 `DevMindPrincipal.role()`），不受本能力影响。

## 4. 数据模型

```
roles(code(32) PK, name(64), description(512), built_in, base_role(16), created_at, updated_at)
users(..., role[ADMIN|DEVELOPER|VIEWER], role_code(32) → roles.code, ...)
role_menu_grants(id, role(16) → roles.code, menu_key(64), created_at, 唯一键(role, menu_key))
```

- 启动顺序：`RoleBootstrap`（@Order(0)）→ `IdentityService` 迁移回填 → `MenuBootstrap`（@Order(10)）；
- 不建 user_roles 关联表：单角色模型满足现状，多角色属后续演进。

## 5. API 概要

```
GET    /api/auth/roles             角色清单（含基础权限/用户数/菜单编码），仅 ADMIN
POST   /api/auth/roles             {code, name, description?, baseRole}，仅 ADMIN
PUT    /api/auth/roles/{code}      {name?, description?, baseRole?}，仅 ADMIN；内置拒绝
DELETE /api/auth/roles/{code}      仅 ADMIN；内置/仍在使用拒绝
PUT    /api/auth/menus/grants      {role, menuKeys[]}，仅 ADMIN；拒绝 ADMIN 与未知角色
```

用户相关端点沿用 CAP-01：`POST/PUT /api/auth/users` 的 `role` 字段现为角色编码。

## 6. 验收标准

- 内置三角色存在且不可编辑/删除；手工改库后重启被校正；
- 创建自定义角色（如 `QA_LEAD`，VIEWER 基础权限）：默认获得 TOP/PROJECT 菜单、无 ADMIN 菜单；
- 分配该角色的用户登录后 `/auth/me` 返回 `role=VIEWER`、`roleCode=QA_LEAD`、角色名称；
  可见菜单与授权一致；后台接口仍按 VIEWER 拦截写操作；
- 修改角色基础权限为 DEVELOPER 后，该角色用户重新登录可执行写操作；
- 删除仍有用户的角色被拒；调整用户后删除成功，角色从清单与授权表消失；
- 自定义角色尝试 ADMIN 基础权限返回 400；对内置 ADMIN 保存菜单授权返回 400；
- 非 ADMIN 访问角色管理、用户管理、菜单目录/授权端点一律 403；
- 旧 `/admin/menus` 地址重定向到用户管理 → 角色页签；菜单目录不再下发 `admin.menus`。

## 7. 实施记录（2026-10-30）

- **后端**（devmind-auth）：新增 `RoleEntity`/`RoleRepository`/`RoleService`/`RoleController`
  与 `RoleBootstrap`；`UserEntity` 增 `role_code`，`UserRepository` 增按角色编码查询/计数；
  `AuthService` 视图补角色名称、JWT 增 `roleCode`；`UserAdminService` 按角色编码落双字段；
  `MenuPermissionService` 角色清单改读 `roles` 表并拒绝给 ADMIN 保存授权；
  `SecurityConfig` 将 `/api/auth/roles/**` 纳入 ADMIN 匹配；`MenuCatalog` 移除 `admin.menus`。
- **前端**：`UserManagementPage` 改为双页签容器（Segmented + `?tab=`），新增 `UsersPanel`
  （原用户管理迁入）、`RolesPanel`（角色 CRUD）、`RoleMenuDrawer`（菜单授权）、`roleMeta.ts`
  （基础权限文案/颜色、下拉选项）；删除 `MenuPermissionsPage`，`/admin/menus` 重定向；
  用户菜单与个人资料展示角色名称。
- **测试**：新增 `RoleServiceTest`（9 例）；重写 `MenuPermissionServiceTest`、
  `UserAdminServiceTest`、`AuthServiceTest`、`JwtCodecTest`、`JwtAuthFilterTest` 适配角色编码，
  `mvn -pl devmind-auth test` 全绿；E2E 新增 `regression.role-management`
  （角色创建→用户分配→登录权限→菜单授权→基础权限变更→删除保护全链）。
- **未落地**（非目标）：按钮级权限、角色继承/多角色、数据权限（CAP-62）、角色变更 WS 推送。
