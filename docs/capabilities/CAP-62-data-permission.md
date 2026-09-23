# CAP-62 数据权限：项目成员体系与个人数据归属

> 能力 ID：CAP-62 ｜ 分类：管理 ｜ 状态：需求定稿（2026-09-23），待实施。

## 1. 目的

平台现状的数据可见性是「**登录即全量**」：任何角色（含 VIEWER）都能看到全部项目、全部需求、
全部会话、全部构建/部署/测试/发版记录；归属字段（`created_by`/`owner_id`）只用于展示与 actor
审计，不参与鉴权。多人使用铺开前必须补上数据权限：

1. **项目级数据权限**：项目引入可见性与成员体系——公开项目全员可读，私有项目仅成员+ADMIN
   可见，项目内写操作要求成员身份；
2. **个人数据归属落实**：问答、工作日志、通知、个人设置、第三方账号等本就 keyed 到人的数据，
   在接口层强制 owner 校验（防直敲 URL 越权读他人数据）；
3. **鉴权点收敛**：数据可见性判定收敛到一个 SPI 接入点，各模块调用而非各自实现，
   为 CAP-63 租户隔离预留同一个切面。

**原则**：菜单权限（CAP-61）管「入口可见」，本能力管「数据可见」，两者正交叠加；
后端查询过滤与 403 拦截是安全边界，前端隐藏只是体验。

### 非目标（v1 不做）

- 需求/会话/构建单条记录的细粒度分享（资源级 ACL、分享链接）；
- 项目内角色细分（评审人/测试等），v1 项目角色仅 OWNER/MEMBER 两档；
- 知识库/文档库的成员化（知识库已有 global/projects 层级与 scope，沿用现状）；
- 数据脱敏（字段级可见性）；
- 租户级隔离（CAP-63）。

## 2. 功能需求

### FR-01 项目可见性与成员模型

- 项目实体新增 `visibility`（`PUBLIC`/`PRIVATE`，@ColumnDefault("'PUBLIC'")，存量项目全部
  PUBLIC = 行为零变化）与 `owner_id`（创建者，存量取 created_by）；
- 新表 `project_members(project_id, user_id, project_role[OWNER|MEMBER], created_at, created_by)`，
  唯一键 (project_id, user_id)；项目创建时创建者自动落 OWNER 行；
- 可见性规则：
  - PUBLIC 项目：全部登录用户**可读**；**写**（建需求/起会话/执行构建部署等）要求成员或 ADMIN；
  - PRIVATE 项目：读写均仅成员或 ADMIN；非成员在列表/详情/聚合查询中完全不可见（不是灰显）；
  - ADMIN 全局绕过（平台运维语义，同 CAP-61）；
- WORKLOG 特殊项目（CAP-41）恒为「仅归属用户+ADMIN」可见，不受 visibility 影响
  （落库即 PRIVATE + 唯一成员 = 归属用户，语义由特例规则保证而非依赖管理员配置）。

### FR-02 成员管理

- 项目 OWNER 与 ADMIN 可增删成员、改成员角色、转让 OWNER（同时自身降为 MEMBER）；
  MEMBER 只读成员列表；
- 成员管理入口：项目概览页「成员」Tab + 后台项目管理页内嵌（ADMIN 通道）；
- 删除项目：成员行级联删除；删除用户（CAP-01）：其成员行同步清理，其 OWNER 的项目
  转交 ADMIN（不允许删到无 OWNER）。

### FR-03 数据可见性判定 SPI 与查询过滤

- common 新增 `ProjectAccessChecker` SPI：`canRead(username, projectId)` /
  `canWrite(username, projectId)` / `filterReadable(username, projectIds)`；实现方
  devmind-project（持有成员表），消费方经 `ObjectProvider<ProjectAccessChecker>` 探测
  （缺席 = 不过滤，保测试与模块独立可跑）；
- **查询过滤落在服务层**：会话/需求/文档/知识/构建/部署/测试/发版等按项目列表的端点，
  统一经 checker 过滤（先查可见 projectId 集合再带 IN 条件，禁逐行内存过滤的 N+1）；
- 详情类端点（`/api/sessions/{id}` 等）按其实体的 projectId 判定：不可见返回 404
  （不暴露存在性），可见但不可写返回 403；
- 聚合端点（指挥中心/工作台概览）同样经 checker 过滤，非成员项目不出现在卡片与统计中。

### FR-04 个人数据 owner 强制

- 盘点并落实「keyed 到人」的资源在接口层的 owner 校验（现状多为前端隐藏、后端未拦）：
  - 问答 chat_sessions/chat_events：仅 owner 可读可写可续（ADMIN 绕过用于排障）；
  - 工作日志条目/报告：仅归属用户；
  - 通知（站内）：仅接收人；用户平台账号（CAP-35 PAT）、个人设置、用户级 git 身份：仅本人；
  - 会话 sessions：项目会话按 FR-03 项目可见性判定（创建者无特权豁免——离开项目即不可见，
    工作区归属另由 CAP-51 workspace_owner 保证）；个人问答会话见上条；
- 校验位置：controller 入口取 `DevMindPrincipal` 比对 owner 列；统一返回 404（防探测）。

### FR-05 项目切换器与前端适配

- `useProjectBootstrap` 的项目列表即后端过滤后的可见集合；私有项目对非成员
  「看不见=不存在」（切换器、深链、面包屑一致）；
- 直敲无权限项目页 URL：项目上下文 gate（ProjectContextGate）按 404 语义渲染「项目不存在
  或无权限」提示页；
- 无写权限的只读用户进入项目：写操作按钮按成员判定禁用（读接口照常），与 CAP-01 角色
  渲染正交（角色管「能不能写这类资源」，成员管「能不能写这个项目」）。

### FR-06 存量数据迁移语义

- 存量项目：visibility=PUBLIC、owner_id=created_by、创建者补 OWNER 成员行（启动幂等种子，
  无 created_by 的项目 owner 落 `local`，成员行留空——PUBLIC 下无行为差异）；
- 全部校验对 ADMIN 短路，运维排障不受成员体系阻塞；
- 行为回归基线：未做任何成员配置时，现有 E2E 套件应零改动通过（默认 PUBLIC 全可读、
  写仍按 CAP-01 角色链）。

## 3. 插件化接口

| SPI（devmind-common） | 实现方 | 消费方 |
|---|---|---|
| `ProjectAccessChecker`（canRead/canWrite/filterReadable，javadoc 写明「缺席=不过滤」契约） | devmind-project | session/docs/knowledge/build/deploy/test/release/flow/dashboard 等按项目过滤的模块（ObjectProvider 探测） |
| `ProjectMembershipAdmin`（成员增删/转让，供未来 open-api/流程层调用） | devmind-project | 预留 |

- 判定结果**不做全局缓存**（成员变更低频，每次请求直查表；若成热点再加短 TTL 缓存并随
  成员变更事件失效）。

## 4. 依赖关系

- CAP-01（DevMindPrincipal/角色/过滤器链）；CAP-02（项目实体挂载点）；CAP-61（正交：
  菜单可见性不影响数据判定，反之亦然）；
- 成员表归 `devmind-project`；checker SPI 归 common；各业务模块改动 = 列表查询带过滤 +
  详情入口加判定，**不动各自数据模型**；
- CAP-63 复用本能力的判定切面：租户隔离在同一 checker/过滤点叠加 tenant 条件，
  不另开一套过滤链。

## 5. 数据模型

```
projects 增列: visibility(16, @ColumnDefault("'PUBLIC'")), owner_id(64)
project_members(id, project_id, user_id(64), project_role(16), created_at, created_by(64),
                唯一键(project_id, user_id))
```

- `user_id` 存 username（与 actor 口径一致，不建 users 外键——auth 模块反向依赖红线）；
- 成员变更（增删/角色/转让）发 DomainEvent `project.membership.changed`，通知中心
  （CAP-06）消费发站内信「你已被加入项目 X」。

## 6. API 概要

```
GET    /api/projects                      （既有，列表按可见性过滤，零签名变化）
GET    /api/projects/{id}                 （非可见 404）
GET    /api/projects/{id}/members         成员列表（成员/ADMIN）
POST   /api/projects/{id}/members         {username, role}（OWNER/ADMIN）
PUT    /api/projects/{id}/members/{username}  改角色（OWNER/ADMIN）
DELETE /api/projects/{id}/members/{username}  移除（OWNER/ADMIN；OWNER 不可自删）
POST   /api/projects/{id}/transfer-owner  {username}（OWNER/ADMIN）
PUT    /api/projects/{id}/visibility      {visibility}（OWNER/ADMIN）
```

## 7. 验收标准

- 零配置回归：全量现有 E2E 在默认 PUBLIC 下通过（成员体系对存量透明）；
- PRIVATE 项目：非成员列表不可见、详情 404、聚合统计不含；加入成员后立即可见；
  ADMIN 始终可见可写；
- PUBLIC 项目：非成员可读列表/详情，写操作（建需求/起会话/构建）403 且错误信息引导
  「联系项目 OWNER 加成员」；
- 个人数据：A 的问答/工作日志/PAT 配置，B 直敲 URL 一律 404；ADMIN 可读（排障语义）；
- 转让 OWNER 后原 OWNER 降 MEMBER；删除用户后其成员行清空、OWNER 项目转 ADMIN；
- checker 缺席（模块独立测试上下文）时全部端点行为与现状一致（不过滤）；
- E2E：建私有项目 → 非成员 404 → 加成员 → 可读 → 移除成员 → 404 闭环。

## 8. MVP 范围（v1 确认做 / 留后续）

- v1：FR-01~06 全做；项目角色仅 OWNER/MEMBER；
- 留后续：资源级分享、项目内细分角色、成员变更多通道通知、checker 结果缓存、
  按成员批量导入（对接组织架构）。
