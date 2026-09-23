# CAP-63 多租户隔离

> 能力 ID：CAP-63 ｜ 分类：平台层 ｜ 状态：需求定稿（2026-09-23），分阶段提上日程。
> M1（租户登记与用户绑定）先行，M2（业务数据租户列 + 全局过滤）随客户化部署节奏启动。

## 1. 目的

平台从「团队内部工具」走向「多团队/多客户共用一套部署」时，需要比项目成员（CAP-62）更硬的
边界——**租户**：租户间的项目、需求、会话、知识库、集成实例、节点、用户互不可见，
如同各自独占一套系统。本能力定义租户模型与分阶段落地路线，**把隔离切面一次设计对，
避免逐表返工**。

**隔离策略决策（定稿）**：**共享库共享表 + `tenant_id` 判别列**。

- 理由：单体 + `ddl-auto=update` 自动演进（每租户一库/一 schema 会把 schema 演进变成
  运维工程，与现有 H2/PG 双库便携性冲突）；部署目标是小中型团队，行级隔离的强度足够；
  CAP-62 的查询过滤切面（ProjectAccessChecker / 服务层过滤）已就位，租户条件在同一位置叠加；
- 明确否决：schema-per-tenant / database-per-tenant（运维与演进成本远超当前收益）、
  Hibernate @Filter 全局拦截（H2/PG 方言差异 + 隐式行为难测试，坚持显式服务层过滤）；
- 安全红线：租户过滤**不靠前端传参**（请求里的 tenantId 一律忽略），只认服务端从认证
  主体解析的 TenantContext。

### 非目标（本 CAP 全程不做）

- 跨租户资源共享/市场（模板、知识跨租户复用走导出导入，不做活共享）；
- 租户级配额/计费（节点数、存储配额留未来计量 CAP）；
- 租户自助开通（注册即建租户的管理门户），v1 租户由平台超管手工建；
- 单用户多租户切换（一个用户严格归属一个租户；跨租户协作 = 分别开户）。

## 2. 功能需求

### M1：租户登记与用户绑定（先行，改动可控、为 M2 打底）

- **FR-01 租户实体**：`tenants(id, code(32,unique), name(128), status[ACTIVE|DISABLED],
  created_at)`；启动种子内置租户 `default`，存量用户全部归属 `default`（行为零变化）；
- **FR-02 用户绑定**：`users` 增列 `tenant_code`（默认 `'default'`）；用户管理页（ADMIN）
  增租户字段；DISABLED 租户的用户登录 401 并提示「租户已停用」；
- **FR-03 TenantContext**：common 定义 `TenantContext`（`currentTenant()`，ThreadLocal），
  认证过滤器在角色解析同点从 JWT/principal 写入；**JWT 增 `tenant` claim**（存量 token
  无该 claim 视为 `default`，免强制重登）；异步线程/事件上下文回退 `default`（与
  `IdentityService.currentActor()` 的 `"local"` 回退同模式）；
- **FR-04 平台超管与租户管理员分层**：现状 `ADMIN` 语义收敛为**租户管理员**（管本租户
  用户/菜单/项目成员）；新增**平台超管**标记（`users.platform_admin`，或独立配置项指定
  超管名单），仅超管可建/停租户、跨租户查看（排障语义）、管平台级资源（Agent 节点、
  模型端点、分类服务实例——这些基础设施**租户共享**，不复制）；
- **FR-05 租户管理页**（仅超管）：租户列表/新建/停用；停用即该租户全员不可登录、数据保留。

### M2：业务数据租户列与全局过滤（提上日程，按部署节奏启动）

- **FR-06 租户列铺设**：业务表分批增 `tenant_code` 列（默认 `'default'` 回填）——
  项目/需求/文档/知识库/会话/问答/执行记录（构建/部署/测试/发版）/集成实例/通知/场景/附件；
  分批顺序：先挂载点（projects）后叶子，每批一个可独立验收的增量；
- **FR-07 过滤注入点收敛**：所有按租户隔离的查询走两个既有切面叠加 tenant 条件——
  (a) CAP-62 的 `ProjectAccessChecker`/服务层过滤点；(b) 个人数据 owner 校验点
  （owner 必同租户，跨租户用户名撞名也不互见）；**禁散落各处的 ad-hoc tenant 拼接**；
- **FR-08 写路径钉租户**：创建任何业务实体时 `tenant_code` 由服务端从 TenantContext 写入，
  请求体不传、传了忽略；跨租户外键引用（projectId 属他租户）在入口 404（同 CAP-62
  「不可见=不存在」语义）；
- **FR-09 平台级资源豁免清单**（不设 tenant 列，全员共享）：agent_nodes、model_endpoints、
  classify_instances/packages、tenants 自身、menu_items（菜单目录共享，授权按租户内角色）；
  `role_menu_grants` M2 增 tenant_code 使各租户菜单授权独立；
- **FR-10 唯一键与编码空间**：业务编码（项目 code、租户内用户名等）M2 起租户内唯一；
  用户名全局唯一保持不变（登录不选租户，tenant 从用户行解析——免登录页多一个字段）；
- **FR-11 runner/节点语义**：节点平台共享（FR-09），工作区路径已是
  `<projectId>/<owner>/...` 天然不撞；M2 不做节点按租户专属（未来配额 CAP 再议）。

### 阶段门（M1 → M2 的启动条件）

- M1 落地且无回归 + 出现真实「一套部署服务多团队/多客户」需求时启动 M2；
- M2 每批铺列后跑全量 E2E（default 租户语义 = 现状）+ 双租户冒烟（A 建项目 B 全链路不可见）。

## 3. 插件化接口

| SPI（devmind-common） | 实现方 | 消费方 |
|---|---|---|
| `TenantContext`（currentTenant/set/clear；javadoc 写明过滤器写入、异步回退 default） | devmind-auth（过滤器填充） | 全部业务模块（经静态接入点，与 IdentityService 同模式） |
| `TenantAdminLookup`（isPlatformAdmin(username)） | devmind-auth | 各管理端点守卫 |

- 租户过滤不新增逐模块 SPI：复用 CAP-62 的 `ProjectAccessChecker` 与 owner 校验点叠加
  tenant 条件，保持「数据可见性判定一处收口」的架构承诺。

## 4. 依赖关系

- CAP-01（JWT/principal/用户管理/过滤器链）；CAP-61（菜单授权 M2 起按租户独立）；
- **CAP-62 是硬前置**：数据可见性切面先收敛，租户条件才有统一注入点；M1 可与 CAP-62
  并行，M2 必须在其后；
- tenants/users 改动归 `devmind-auth`；业务表铺列归各业务模块（每批独立 PR）；
- 部署形态不变：一套后端 + 一个库（H2/PG/MySQL 皆适用），租户是行级语义非部署语义。

## 5. 数据模型

```
tenants(id, code(32,unique), name(128), status(16,@ColumnDefault("'ACTIVE'")), created_at)
users 增列: tenant_code(32, @ColumnDefault("'default'")), platform_admin(Boolean, 实体初始值 false)
M2 业务表增列: tenant_code(32, @ColumnDefault("'default'")) + (tenant_code, 原唯一键) 复合唯一按需
```

- Boolean 列禁 @ColumnDefault（红线，走实体初始值 + getter 兜底）；
- 字符串默认值必带引号 `@ColumnDefault("'default'")`（红线）；
- 索引：M2 每批铺列表按主查询模式补 `tenant_code` 前缀索引（列表查询必带 tenant 条件）。

## 6. API 概要

```
M1:
GET/POST /api/admin/tenants                 租户列表/新建（仅超管）
PUT      /api/admin/tenants/{code}/status   启停（仅超管；default 不可停用）
（users 管理端点增 tenant_code 字段，零新端点）

M2: 无新增端点——全量既有端点行为变化（按 TenantContext 过滤/钉入），
    请求体出现 tenant 字段一律忽略（OpenAPI 文档注明）。
```

## 7. 验收标准

- M1：存量用户登录零感知（JWT 无 tenant claim 按 default 放行）；新建租户 + 用户绑定 +
  停用租户登录 401；超管名单外用户调 tenants 端点 403；
- M2（每批）：default 租户全量 E2E 零改动通过；双租户冒烟——A 租户项目/需求/会话/知识库
  对 B 租户用户列表不可见、详情 404、写引用 404；A 租户 ADMIN 不可见 B 租户用户与菜单授权；
- 请求体伪造 tenant_code 创建实体：被忽略，落库为认证主体租户；
- 平台级资源（节点/模型端点）两租户均可用；
- 异步线程（事件监听/定时任务）TenantContext 回退 default 不炸。

## 8. MVP 范围（分阶段确认）

- M1 v1：FR-01~05（租户登记/用户绑定/TenantContext/超管分层/租户管理页）；
- M2 v1：FR-06~11 按批铺开，FR-06 批次计划在启动时按部署目标细化；
- 留后续：租户配额与计量、跨租户导出导入工具、用户多租户、节点租户专属化。
