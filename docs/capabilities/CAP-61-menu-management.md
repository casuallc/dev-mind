# CAP-61 菜单管理与角色菜单权限

> 能力 ID：CAP-61 ｜ 分类：管理 ｜ 状态：需求定稿（2026-09-23），待实施。

## 1. 目的

平台的三个菜单位（工作台顶部导航、项目二级页签、后台侧边菜单）目前是**前端硬编码常量**
（`AppLayout.tsx` / `ProjectSubNav.tsx` / `AdminLayout.tsx`），除「后台管理入口仅 ADMIN 可见」外
全员同构。随着用户与角色增多，需要：

1. **菜单目录注册表**：全站可管控菜单有一个稳定清单（菜单 key → 路由/标题/归属区），
   新能力上线即登记，不再散落三个前端文件；
2. **角色 × 菜单可见性矩阵**：管理员在页面上勾选「哪些角色能看到哪些菜单」，
   用户登录后只见自己有权的菜单项。

**边界声明（重要）**：菜单权限是**展示层/导航层**管控，不是安全边界。后端 API 的鉴权仍由
CAP-01 的过滤器链（角色 + HTTP 方法粗粒度）与本能力补充的菜单路由守卫承担；
「菜单不可见」≠「接口不可达」，敏感接口必须保证后端拦截独立成立（现状已成立，本能力不削弱）。

### 非目标（v1 不做）

- 按钮/操作级权限（页内按钮仍按 CAP-01 角色渲染，不进菜单矩阵）；
- 自定义角色（角色仍是 CAP-01 预设三角色，矩阵按角色授权；自定义角色另立 CAP）；
- 菜单的增删改/排序/图标配置（菜单目录由代码登记，管理页只调可见性，不做菜单编辑器）；
- 数据级权限（项目成员可见性等，属 CAP-62）。

## 2. 功能需求

### FR-01 菜单目录注册表

- 菜单项模型：`menuKey`（稳定字符串，分级命名：`top.*` / `project.*` / `admin.*`，如
  `top.worklog`、`project.sessions`、`admin.projects`）/ 标题 / 路由 path / 归属区
  （TOP 顶部导航、PROJECT 项目页签、ADMIN 后台侧边）/ 分组（ADMIN 区的分组名）/ 排序号 / 图标 key；
- **目录由后端代码登记**：启动时各持有菜单的模块向注册表（`MenuCatalog`，auth 模块内）注册
  或集中登记一处常量清单；启动比对 DB 已落行，**新增菜单自动落库并补默认授权、下架菜单标记
  retired（不物理删，保历史授权可读）**；
- 初始目录 = 现状三个前端文件的完整清单（TOP 5 项：工作台/项目/AI 问答/工作日志/设置；
  PROJECT 8 项：概览/会话/需求/知识/构建/部署/测试/发版；ADMIN 19 项含分组结构）；
- ADMIN 区的「分组」行（项目与资源/智能决策/内容/系统）不是菜单项，不落库；分组内全部子项
  对某角色不可见时，前端隐藏该分组标题。

### FR-02 角色菜单授权模型

- 新表 `role_menu_grants(role, menu_key)`，唯一键 (role, menu_key)：存在即「该角色可见该菜单」
  （白名单语义，不是黑名单）；
- **ADMIN 恒见全部菜单**（短路绕过矩阵），防止管理员把自己锁死在管控页之外；
- 启动种子：按现状行为补默认授权——`top.*`/`project.*` 授予 ADMIN+DEVELOPER+VIEWER，
  `admin.*` 仅授 ADMIN；种子幂等（已有行不覆盖管理员后续调整）；
- VIEWER 的只读语义不变：本能力只管可见性，VIEWER 看到菜单但写操作仍被 CAP-01 后端链 403。

### FR-03 当前用户可见菜单下发

- `GET /api/auth/menus`：返回当前用户的可见菜单项列表（menuKey/path/title/zone/分组/排序），
  已登录任意角色可调；前端登录后拉取并缓存，路由变化不重复拉；
- 判定链：ADMIN → 全量目录（去 retired）；其他角色 → 目录 ∩ role_menu_grants；
- 前端三个菜单位改为**按下发清单渲染**，本地硬编码常量删除；选中高亮逻辑
  （menuSelectedKey）按 path 前缀匹配，不依赖菜单是否在清单中（无权限直敲 URL 高亮为空即可）。

### FR-04 菜单权限管理页（仅 ADMIN）

- 后台「系统」分组新增 `/admin/menus`：角色（列）× 菜单（行，按 zone 分组展示）勾选矩阵，
  勾选即调保存端点；ADMIN 列禁用勾选（恒全选，防误操作）；
- 保存：`PUT /api/auth/menus/grants` 全量提交某角色的 menuKey 集合（先删后插，幂等）；
  retired 菜单行灰显不可勾选；
- 变更即时生效：其他用户下次拉 `/api/auth/menus` 即新集合（前端登录/刷新时重拉，
  不做 WS 实时推送）。

### FR-05 前端路由守卫补强

- 现有 `RequireAdmin` 守卫保留；新增基于下发菜单清单的弱守卫：已登录用户直敲无权限菜单的
  URL 时，前端给「无权限访问」提示页并引导回首页（防呆，不是安全边界）；
- 项目二级页签条（ProjectSubNav）同样按清单过滤：某角色 `project.builds` 不可见时，
  该页签不出现。

## 3. 插件化接口

| SPI（devmind-common） | 实现方 | 消费方 |
|---|---|---|
| `MenuCatalogProvider`（可选：`menuItems()` 供献登记项；v1 由 auth 模块集中常量登记，SPI 留给「能力模块自登记菜单」的演进点，不强制落地） | 各能力模块 | devmind-auth 目录装配 |

- 判定服务 `MenuPermissionService`（auth 模块内，`visibleMenus(username, role)`）供 controller
  与未来其他消费方（如指挥中心按菜单裁剪卡片）复用。

## 4. 依赖关系

- CAP-01（用户/角色/登录链路、`/api/auth/**` 端点位、DevMindPrincipal）；
- 新表归 `devmind-auth` 模块（与 users/refresh_tokens 同域）；前端改动集中在 `src/app/`
  （三个菜单渲染点）+ `src/features/auth/`（管理页 + api），不改各 feature 内部；
- 与 CAP-62 的关系：本能力管「菜单能不能看见」；「项目数据能不能看见」由 CAP-62 项目成员
  体系承担，两者正交（菜单可见但无项目数据 = 空列表，属正常状态）。

## 5. 数据模型

```
menu_items(id, menu_key(64,unique), title(64), path(128), zone(16),  -- TOP|PROJECT|ADMIN
           group_name(64,null), icon(64,null), sort_no, retired, created_at)
role_menu_grants(id, role(16), menu_key(64), created_at, 唯一键(role, menu_key))
```

- `menu_key` 为对外稳定标识（前端/授权/种子都用它），path 变不动 key；
- 种子授权写在启动装配（ApplicationRunner，幂等），不写迁移脚本（ddl-auto 演进红线不变）。

## 6. API 概要

```
GET  /api/auth/menus                 当前用户可见菜单清单（登录即可）
GET  /api/auth/menus/catalog         全量目录（含 retired 标记），仅 ADMIN
GET  /api/auth/menus/grants          角色→menuKey 集合全量，仅 ADMIN
PUT  /api/auth/menus/grants          {role, menuKeys[]} 全量覆盖某角色授权，仅 ADMIN
```

## 7. 验收标准

- 新装环境启动后：`menu_items` 落全量目录、三角色默认授权与现状行为一致（回归零变化）；
- ADMIN 在矩阵页去掉 DEVELOPER 的 `admin.knowledge` → DEVELOPER 刷新后后台侧边无该项、
  直敲 `/admin/knowledge` 看到无权限提示页；恢复勾选即恢复；
- ADMIN 列不可编辑；把某角色 `project.*` 全部取消后其项目页签条为空条（项目切换器仍在）；
- 新增菜单 key 登记后重启：自动落库 + 按 zone 默认授权（admin.* 仅 ADMIN、其余全角色），
  已存在授权不被种子覆盖；
- VIEWER 被授予 `admin.dashboard` 后可进入指挥中心页（后端读接口本就对三角色放行），
  未授予的 admin 写接口仍 403（后端边界不受菜单影响）；
- E2E：登录 → 拉 menus → 渲染 → 改授权 → 重拉断言差异。

## 8. MVP 范围（v1 确认做 / 留后续）

- v1：FR-01~05 全做；菜单目录集中常量登记（不强制 SPI 化）；
- 留后续：能力模块自登记菜单（SPI）、菜单变更 WS 推送、按钮级权限、自定义角色、
  指挥中心卡片按菜单裁剪。
