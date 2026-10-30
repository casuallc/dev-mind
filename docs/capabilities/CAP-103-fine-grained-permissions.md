# CAP-103 菜单/页签/按钮三级权限

> 能力 ID：CAP-103 ｜ 分类：管理 ｜ 状态：已实现（2026-10-30） ｜ 日期：2026-10-30
> 依赖：CAP-01（认证与角色）、CAP-61（菜单目录与授权）、CAP-102（角色管理）。
> 决策记录：
> 1. 继续复用 `menu_items` 与 `role_menu_grants`，不新建权限表：菜单、页签、按钮统一为「权限节点」，用 `node_type` 区分。
> 2. 节点层级为 MENU → TAB → ACTION，页签允许再挂页签；`parent_key` 指向上级，授权按节点编码平铺存储，子节点授权隐含祖先可见。
> 3. 默认授权沿用现有行为：非 ADMIN 区新节点按父节点已有授权复制，ADMIN 区新节点授予 ADMIN；升级不改变任何角色现有可见范围，管理员再按需收紧。
> 4. 生效方式为「前端显隐 + 后端拦截」：页签与按钮按授权显隐；有服务端对应写接口的 ACTION 节点由新增的 API 权限过滤器独立校验，越权返回 403。
> 5. 基础权限仍是最终底线：VIEWER 即使被授予写按钮也不显示、后端仍拒绝；ADMIN 始终短路全部权限。
> 6. 只读刷新/搜索/分页/切换视图不建按钮权限，避免权限树噪音。

## 1. 目的

CAP-61/CAP-102 的菜单权限只覆盖侧边导航与顶部导航，页面内的页签（如「项目管理」下的
项目/经验提案/命令模板）和按钮（新建、删除、推送、执行等）仍由基础角色粗粒度控制，
无法表达「同一开发角色、只能看项目不能看经验提案」「能进文档管理但不能删除」等诉求。

本能力把权限树扩展到全站三级：

- 一级/二级菜单（现有 MENU）；
- 页面顶部页签（TAB，含需求详情、项目设置等二级页签）；
- 操作按钮（ACTION，覆盖新增/编辑/删除/执行/推送/导入等有副作用操作）。

## 2. 功能需求

### M1 权限节点模型与授权语义（已交付）

- FR-01 `menu_items` 增加 `node_type`（MENU/TAB/ACTION）、`parent_key`、`write_action` 三列；
  `write_action=true` 表示该按钮会写数据，前端对 VIEWER 隐藏、后端参与拦截；
- FR-02 节点编码规则：MENU 保持现状；TAB 为 `<menuKey>.tab.<name>`；
  ACTION 为 `<父节点>.action.<name>`，允许挂在菜单或页签下；
- FR-03 页签/按钮节点的 `path` 为空、`zone` 与父节点一致；目录排序同区按 `sortNo`；
  目录由 `MenuCatalog` 单一登记，启动同步落库；父节点先于子节点登记；
- FR-04 授权表仍为 `(role, menu_key)` 平铺记录；授予子节点自动补齐祖先链，
  但不自动展开未勾选的子节点；
- FR-05 新建节点默认授权：MENU 沿用 CAP-61（ADMIN 区只给 ADMIN，其余区给全部角色）；
  TAB/ACTION 复制父节点当前授权；存量角色升级后可见范围不缩小；
- FR-06 `GET /api/auth/menus` 下发节点类型字段；内置 ADMIN 恒为全部节点，授权矩阵只读。

### M2 后端操作拦截（已交付）

- FR-07 新增 API 权限规则表（代码登记）：HTTP 方法 + Ant 路径模式 + 可放行的权限编码集合（OR）；
- FR-08 过滤器在 JWT 认证之后执行：未登录交给既有链；ADMIN 短路；命中规则时要求角色持有
  集合中任一项，否则 403 统一 `ApiError`；未登记规则的接口沿用 CAP-01 粗粒度规则；
- FR-09 同一接口被多个页面共用时使用 OR 集合（如建会话可由项目会话或工作日志发起）；
- FR-10 只对写操作与少量敏感读操作登记规则；页签本身是展示层，不单独拦截读接口；
- FR-11 启动时校验规则引用的权限编码都存在于目录，缺失直接失败，避免规则静默失效。

### M3 前端显隐与授权树（已交付）

- FR-12 菜单快照增加节点类型字段；顶部导航、项目二级导航、后台侧边只渲染 MENU；
- FR-13 新增 `usePermissions().can(key)` 与 `canAction(key)`：ADMIN 恒真；
  `writeAction` 节点额外要求基础权限可写；
- FR-14 所有页面顶部页签按授权过滤，URL 中的未授权页签回落到第一个可用页签；
- FR-15 所有有副作用按钮按 ACTION 授权显隐；已有 `canWrite()` 判断保留，二者同时成立才显示；
- FR-16 个人页签（工作台概览/用量、问答、工作日志、设置、收藏夹等）同样纳入；
- FR-17 角色「菜单权限」抽屉改为可勾选树：区 → 菜单 → 页签 → 按钮；勾选父节点级联勾选
  全部后代，取消父节点级联取消后代，取消子节点不强制取消父节点；支持区/菜单全选与关键字搜索。

## 3. 否决方案

| 方案 | 否决理由 |
|---|---|
| 新建独立 `permissions` / `role_permissions` 表 | 与 CAP-61/CAP-102 授权矩阵并存会形成两套数据源，迁移与回滚都更重；现有表只需加三列即可承载 |
| 用闭包表存层级 | 层级最多四层、节点规模数百，服务层递归足够；闭包表增加同步复杂度与一致性风险 |
| 页签也由后端强拦截对应读接口 | 读接口大量跨页复用，按页签切分会产生误杀；页签是导航层，敏感数据仍由数据权限（CAP-62）负责 |
| 逐按钮一对一建权限节点 | 权限树膨胀到数百项且大量按钮共享同一接口；按操作语义分组登记，前端同组按钮共用授权 |
| 前端只隐藏、不做后端校验 | 直接调接口即可绕过，不满足「拦截」要求；已选后端规则表方案 |

## 4. 架构与模块归属

```
devmind-auth
├── menu/MenuCatalog              权限节点目录（MENU/TAB/ACTION 单一数据源）
├── menu/MenuPermissionService    目录同步、授权读写、祖先补齐、hasAnyGrant
├── menu/ApiPermissionCatalog     HTTP 方法 + 路径模式 + 权限编码集合
├── menu/ApiPermissionFilter      JWT 之后的写操作权限校验
└── config/SecurityConfig         注册过滤器；保留既有粗粒度规则

devmind-web (frontend)
├── features/auth/usePermissions.ts        can/canAction
├── features/auth/components/RoleMenuDrawer.tsx  三级授权树
└── 各页面                                  页签过滤 + 按钮显隐
```

- 其他业务模块不依赖权限包，只暴露既有 URL；过滤器只按路径判定，不侵入控制器；
- 菜单权限仍是展示层，ACTION 中登记了服务端规则的写操作才构成安全边界。

## 5. 数据模型

```
menu_items(id, menu_key(64) 唯一, title, path, zone, group_name, icon, sort_no, retired,
           node_type(16), parent_key(64), write_action, created_at)
role_menu_grants(id, role(16) → roles.code, menu_key(64), created_at, 唯一键(role, menu_key))
```

- 新列均可空，启动同步对目录内节点全量回写；历史 retired 节点按 MENU 兼容；
- 不建闭包表：祖先链在服务层按 `parent_key` 计算；
- `ddl-auto=update` 自动加列，存量库无需手工 ALTER；默认授权由启动同步补齐。

## 6. API 概要

```
GET  /api/auth/menus          当前角色可见节点（含 nodeType/parentKey/writeAction）
GET  /api/auth/menus/catalog  全量目录，仅 ADMIN
GET  /api/auth/menus/grants   角色×节点授权矩阵，仅 ADMIN
PUT  /api/auth/menus/grants   {role, menuKeys[]}，仅 ADMIN；服务端补祖先链
```

写入类业务接口新增权限校验，不改变 URL 与请求/响应结构；越权统一 403 `ApiError`。

## 7. 安全

- 权限判定顺序：既有 JWT 认证 → 基础角色粗粒度规则（CAP-01）→ API 权限规则（本能力）；
  三层同时通过才放行，任何一层拒绝即 403；
- ADMIN 短路：内置 ADMIN 不查授权表；自定义角色最高只能选 DEVELOPER/VIEWER 基础权限（CAP-102），
  不会因授权变相获得管理接口；
- VIEWER 的写操作仍由 SecurityConfig 拒绝，前端写按钮即使被勾选也不渲染；
- 规则表启动自检：引用不存在的权限编码直接启动失败，避免规则静默失效；
- 越权响应不泄露角色授权详情，仅返回统一错误文案与请求路径。

## 8. 里程碑

| 里程碑 | FR 范围 | 状态 |
|---|---|---|
| M1 权限节点模型与授权语义 | FR-01 ~ FR-06 | 已交付 |
| M2 后端操作拦截 | FR-07 ~ FR-11 | 已交付 |
| M3 前端显隐与授权树 | FR-12 ~ FR-17 | 已交付 |

## 9. 验收标准

- 目录包含全部一级菜单、页面页签与登记的操作按钮，节点编码唯一且父节点存在
  （`MenuPermissionServiceTest`、`ApiPermissionCatalogTest`）；
- 新建角色默认获得非 ADMIN 区全部节点；ADMIN 区仅 ADMIN（`RoleServiceTest`）；
- 给角色只授予 `admin.projects` + `admin.projects.tab.proposals` 后：该角色能看到项目管理菜单
  与「经验提案」页签，看不到「项目」页签，页面自动落到经验提案；
- 给角色授予 `admin.docs` 但不授予 `admin.docs.action.delete` 后：文档页可见，删除按钮不显示；
  直接调用删除接口返回 403；
- 共享接口（建会话、沉淀经验）在任一来源页签被授权时可正常调用；
- VIEWER 角色即使勾选写按钮也不显示，接口仍被基础权限拒绝；
- ADMIN 登录看到全部菜单/页签/按钮，授权抽屉只读；
- 既有 E2E `regression.menu-grants` / `regression.role-management` 全绿，
  新增 `regression.fine-grained-permissions` 覆盖三级节点、页签过滤与越权 403。

## 10. 风险与开放问题

| 编号 | 风险/问题 | 口径 |
|---|---|---|
| R1 | 权限节点增多导致授权树较长 | 搜索 + 区/菜单全选 + 默认全部授予缓解；后续可加「只看已授权」 |
| R2 | 共享写接口用 OR 集合，授权粒度受接口设计限制 | 接受；新接口按页面语义拆分，避免一个接口承载过多场景 |
| R3 | 前端仍有少量按钮与接口未登记权限 | 缓解：新增按钮必须同步目录；启动自检保证已登记规则有效 |
| O1 | 字段级/数据行级权限 | 不在本能力范围，数据权限见 CAP-62 |
| O2 | 权限变更实时推送 | 未实施，登录/刷新菜单快照后生效 |
