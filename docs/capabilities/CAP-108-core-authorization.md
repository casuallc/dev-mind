# CAP-108 核心授权收敛

> 能力 ID：CAP-108 ｜ 分类：底座/管理 ｜ 状态：定稿未实施 ｜ 日期：2026-11-03
> 依赖：CAP-01/102/103（身份、角色与 ACTION 授权）、CAP-62（资源权限模型）、CAP-20（开放接口）；首批消费方为 [CAP-107 统一 AI 助手](CAP-107-unified-ai-assistant.md)，确认链复用 CAP-76。CAP-62 尚未实施，其项目可见性与成员模型作为本能力 M1 的交付项落地（见第 5 节与 R1），不等待 CAP-62 独立排期。
> 决策记录：
> 1. **核心授权独立立项**：服务人工 `/api`、内置工具、Open API 与受委托异步操作，拥有独立需求、迁移里程碑和验收；CAP-107 消费本能力，不承担全平台授权底座建设。
> 2. **业务操作目录单一维护规则**：以 operationId 表达能力，HTTP 路径和工具名只做映射；用户授权继续复用既有角色及 ACTION 表，不另建 AI 专属授权。
> 3. **应用 Service 强制授权**：页面隐藏、HTTP 过滤、工具预检和用户确认均不能替代最终检查；逐次核验当前身份、操作权限与资源范围。
> 4. **逐域迁移且缺失即拒绝**：先完成首批操作的实际资源策略再开放消费方；旧 HTTP 防线在等价覆盖前保留，缺策略不回退 local、ADMIN 或仅 RBAC。
> 5. 本文将原助手配套授权设计提升为独立能力档案，保留完整技术方案并补齐 FR、里程碑与验收；全部新增类型、端点与字段仍为目标设计，不修改当前运行行为。

## 1. 目的

采用**统一业务操作目录 + 核心授权引擎 + 应用 Service 强制检查 + 业务资源策略**。授权表继续复用 CAP-102/103，项目成员及个人归属遵循 CAP-62；不建立 AI 专属权限体系，不把 HTTP 路径作为业务权限的唯一标识。

需要统一的是：同一有效主体对同一操作及资源，所有入口得到同一业务授权结论。AI 确认、凭证 scope、预算和业务状态校验是额外约束，因此“业务授权通过”不表示立即执行。

### 1.1 当前源码核查

| 位置 | 当前行为 | 收敛要求 |
|---|---|---|
| `devmind-auth/.../menu/ApiPermissionCatalog.java`、`ApiPermissionFilter.java` | 按 HTTP 方法和 `/api/**` 路径匹配 ACTION，未登记规则沿用粗粒度角色检查 | 路径只映射业务操作，不独立维护第二份权限规则 |
| `devmind-auth/.../security/DevMindPrincipal.java` | 包含 username、基础 role、roleCode；二参构造将 roleCode 设为基础 role | 全链传递实际 roleCode，并由服务端读取当前有效角色，不以基础角色替代自定义角色 |
| `devmind-copilot/.../CopilotIdentityService.java`、`CopilotToolRunner.java` | 工具身份主要保留 actor/基础角色；执行时重建 SecurityContext | 复用统一主体解析，保留 roleCode、凭证范围和来源，停用账号不降级为可读 VIEWER |
| `devmind-common/.../agent/tool/ToolAccess.java` | 无 ToolAccessPolicy 或无项目 ID 时返回允许 | 已迁移操作缺必需资源策略/上下文即拒绝；缺 SPI 不得放宽为仅 RBAC |
| `devmind-auth/.../IdentityService.java` | 缺认证上下文时 `currentActor()` 回退 local | 已保护应用入口要求可信上下文；local 仅供显式登记的系统任务，不作为缺失用户的替代身份 |
| `devmind-open-api/.../security/OpenApiAuthFilter.java` | HMAC 成功后构造等价 ADMIN 的 `apikey:<name>` | 区分集成主体与用户委托，赋予受限 scope，不用 key 名称代替实际用户 |
| `docs/capabilities/CAP-62-data-permission.md` 与能力索引 | 正文标待实施，索引标已实现；当前生产源码未发现 ProjectAccessChecker/ToolAccessPolicy 实现 | 以实现及测试为准核实，项目数据授权作为实际交付项，不依赖索引状态推断 |

上述检查是设计依据，不等同于生产环境渗透或运行验收。

## 2. 功能需求

### M1 授权底座与首批应用边界

- **FR-01 业务操作目录**：建立唯一 operationId 目录，登记主体类型、基础角色、ACTION 表达式、资源策略、组合副作用及契约版本；HTTP/Tool 映射均引用目录，不分别维护权限条件。
- **FR-02 可信身份**：统一解析服务端认证的当前用户、真实 roleCode 与有效基础角色；支持 Web、机器人个人映射及异步主体传递。缺身份、停用、角色删除或委托失效即拒绝，不回退 local/ADMIN/VIEWER。
- **FR-03 资源策略**：核实并补齐首批项目可见性、成员关系、个人归属、对象关系与查询范围策略；反查真实对象归属，禁止以客户端传入项目或 owner 证明权限，分页与聚合也须过滤。
- **FR-04 Service 最终检查**：在所有首批公开用例及跨模块执行入口显式 require，覆盖绕开 Controller/工具预检的调用；必需 provider 缺失或授权异常不得放行。首批覆盖项目/需求读取、需求草稿、需求/工作单元创建与本人数据。
- **FR-05 规则兼容**：复用 users/roles/menu_items/role_menu_grants，保留 ADMIN-only、VIEWER 只读底线与已登记共享接口的 OR 语义；不得以基础角色代替自定义角色授权，不自动批量增授权限掩盖历史差异。
- **FR-06 确认与受理边界**：授权与用户同意分别判断；AI 写命令校验服务器确认凭据，消费时重验主体、参数、资源与风险。接收端按 operationKey 唯一受理，超时结果不明先对账，不盲目重写；确认交互仍由 CAP-76/107 承担。
- **FR-07 授权审计与错误**：记录全入口最终 ALLOW/DENY/ERROR，关联主体、操作、资源与执行命令；拒绝记录不随业务回滚丢失。不可见资源统一 404，缺策略/服务异常明确暂不可用，不能伪装为允许或用户权限不足。
- **FR-08 迁移门禁**：建立旧规则与新目录差异清单，影子判定不执行副作用；首批入口等价覆盖前保留原 HTTP 防线，未迁移能力不得因新助手上线自动开放。

### M2 全站业务域与异步链路迁移

- **FR-09 执行域扩展**：逐域覆盖会话、构建、部署、需求流程及跨模块入口。组合动作列出并预检全部具体操作，自动延续携带原主体和固定范围，每一步重新授权，不把后台线程当作系统特权。
- **FR-10 规则去重**：将已覆盖域的路径/工具规则转为 operationId 映射；证明所有应用入口受保护后才删除重复授权条件。启动/构建自检未知 ACTION、缺策略、重复或歧义映射。
- **FR-11 撤权与读取一致性**：下一条命令、确认消费、异步恢复和重试读取当前权限；查询、日志、文件与会话历史重放执行相同资源边界，说明已派发副作用的停止/撤销限制。

### M3 外部主体与开放接口收敛

- **FR-12 委托及服务主体**：外部用户委托的权限取用户当前权限与凭证 scope/资源范围交集；集成服务使用自身显式授权，不继承签发人权限或通过 actor 头冒充用户。外部 AI 写操作使用服务端确认凭据。
- **FR-13 历史 Key 退出**：清点等价 ADMIN 的历史 key 与调用方，按端点准备 scoped 凭证并切换；已迁移端点拒绝 LEGACY_ADMIN，未迁移端点有明确兼容范围和退役清单。

## 3. 否决方案

| 方案 | 否决理由 |
|---|---|
| 核心授权只作为助手设计附件 | 人工、工具和开放接口都存在同一问题，需要独立建设、迁移与验收 |
| 仅依赖 HTTP 过滤器或工具 minRole | 覆盖不了直接 Service 调用，且丢失自定义角色及资源权限 |
| 各入口维护各自的授权表和判断 | 相同业务操作会产生权限差异，后续变更难以同步 |
| 授权 Bean 缺失则退回仅 RBAC/local | 把部署或实现缺口变成权限放宽，不能保证拒绝边界 |
| 用户确认一次即获得长期通用授权 | 同意具体参数不等于有权限，也不代表后续目标与危险动作均已批准 |
| 所有 API Key 都赋予 ADMIN 或继承创建人 | 无法表达集成范围、撤销与当前用户身份，形成越权通道 |

## 4. 架构与模块归属

### 4.1 总体架构与执行边界

```text
人工 Controller ───────────┐
已确认的工具执行器 ─────────┼→ 应用 Service 公开用例方法
Open API Controller ───────┘       → AuthorizationService.require(operationId, target)
                                   → 业务校验 / 事务写入 / 外部派发
                                           ↓
                        auth：主体 + 基础角色 + ACTION + scope
                        业务策略 SPI：对象关系 + 项目/owner 数据范围
```

#### 4.1.1 业务边界执行规则

1. 在每个面向 Controller、Tool、事件及跨模块调用的应用用例方法内显式调用授权接口；授权必须发生在业务写入、外部派发、读取敏感正文之前。最小资源事实查询可在授权阶段完成。
2. Controller/工具预检用于提前反馈；应用 Service 内的 require 是最终检查，不接受客户端传入 `authorized=true` 或以前的 ALLOW 结果代替本次判定。
3. 不只依赖 `@PreAuthorize`/Spring AOP：同类内部调用可能不经代理。需要复用的领域函数与外部用例入口分离，底层辅助函数保持非公开且不能被 Tool 绑定；跨模块执行入口同样检查。
4. 新工具仅能绑定已登记、已保护的应用入口。启动检查拒绝未知 operationId、缺资源策略、重复/歧义映射及未登记的执行适配器；不开放任意类名/方法名反射执行。
5. 读取对象事实、版本检查和本次数据库变更尽量在同一事务内，采用版本/条件更新防止确认后目标变化。跨模块、网络与异步边界重新核验并记录检查时刻；不承诺授权撤销能中止已生效副作用。
6. 组合操作必须明确列出后续能力与资源；首期按各具体操作所需权限全部预检，执行到每一步仍重验。例如“规划后开发”需满足规划、工作单元写入、开发会话创建的权限。迁移时同步调整人工入口的反馈；不通过给角色批量追加缺失权限掩盖历史隐式越权。
7. 自动延续只消费服务端保存的已确认计划/业务配置允许的下一步，并携带原主体及固定资源约束。新的目标、危险动作或未确认参数回到确认链；不能靠事件处理线程缺身份后回退 local 获得权限。

#### 4.1.2 SPI 与归属

| 契约/实现 | 所属 | 职责 |
|---|---|---|
| ExecutionContext、OperationSpec、ResourceTarget、AuthorizationDecision | common 新授权包 | 无业务实现依赖的契约 |
| AuthorizationService | common SPI，auth 实现 | `require(operationId, target)`；内部只用可信上下文，失败抛稳定错误 |
| SubjectResolver | auth | 读取当前用户、实际 roleCode、基础权限、凭证与状态 |
| OperationRegistry | auth 装配，模块贡献声明 | 唯一操作目录，校验权限 key、执行映射、策略完整性 |
| ResourcePolicy / AuthorizedQueryScope | common SPI，各业务模块实现 | 单对象授权事实及查询范围，项目成员判定由 project 提供 |
| AuthorizationAuditSink | common SPI，auth 实现 | 记录决策，关联业务与工具审计 |
| HTTP/Tool/Open API 映射 | 各适配层 | 入口名 → operationId，不再独立维护角色与 ACTION 条件 |

业务模块仅消费 common 授权 SPI，auth 不反向依赖业务实现；app 组装所有 provider。资源 provider 不调用受保护用例来读取授权事实，避免授权引擎与业务 Service 互相递归。必需 provider 通过依赖与启动校验保证存在，单测显式提供测试策略，不以缺 Bean 自动放行。

现有 `ToolAccessPolicy` 迁移为核心引擎的兼容预检适配器；最终检查仍在应用 Service，不把旧接口只有 actor/projectId/risk 的字段当成完整授权上下文。

### 4.2 统一主体、操作与资源契约

#### 4.2.1 三类主体与可信上下文

| 主体 | 身份来源 | 有效权限 |
|---|---|---|
| USER | Web 已验证登录态、机器人个人绑定 | 用户当前基础角色、roleCode 对应授权及资源权限 |
| DELEGATED_USER | 经认证服务验证的用户委托凭证，包含实际用户与客户端标识 | 用户当前权限 ∩ 凭证操作 scope ∩ 凭证资源范围；委托到期/撤销即失效 |
| SERVICE | 已登记集成服务主体及其凭证 | 显式授予该主体的操作及资源范围，不继承签发人或 key 创建人的权限 |

SYSTEM 仅用于已有调度器/事件链的可信系统执行：必须有服务端登记的任务类型、允许操作和资源约束。它不属于用户请求的缺省身份，也不是供模型选择的第四种模式。用户发起的后台动作保存发起主体引用并在派发前重验，不转换为 SYSTEM。

建议 common 契约（名称为设计建议）：

```text
ExecutionContext
  subjectRef              稳定主体类型及 ID；username 用于展示/兼容 created_by
  clientId / credentialId 来源客户端与凭证引用，无 secret
  source                  WEB / COPILOT_WEB / BOT / OPEN_API / ASYNC / SYSTEM
  traceId / commandId      本次链路与执行命令标识
  conversationId / turnId / actionId / operationKey（可空）
```

- 上下文只能由认证/调度适配器构造；客户端提交的 projectId、ownerId 不能作为授权事实。`source` 只能增加约束，不能提升角色或绕过 ACTION。
- `SubjectResolver` 根据可信主体引用读取当前有效用户、roleCode、`roles.base_role`、授权及凭证状态。不存在、停用、角色删除、凭证撤销均拒绝；不信任旧 token/确认单中的角色快照。
- 异步线程显式传递上下文，任务恢复只凭持久化主体引用重建，不能沿用过期授权对象。新授权路径不调用具有 local 回退语义的方法来判断是否允许。
- 初期不做跨请求授权结果缓存；一次同步命令可复用短生命周期只读解析结果。下一条命令、每个异步步骤/重试、确认消费都重读；不承诺已派发作业可因撤权而自动回滚。

#### 4.2.2 业务操作目录

每个可公开执行的应用能力有稳定 `operationId`，如 `requirement.create`；工具名、HTTP 路径和 UI ACTION key 均不是它的替代物。

```text
OperationSpec
  id / ownerModule / contractVersion
  effect                  READ / GENERATE / WRITE / DANGEROUS
  allowedSubjectTypes     USER / DELEGATED_USER / SERVICE；SYSTEM 单独登记任务策略
  allowedBaseRoles        显式基础角色集合，保留当前 ADMIN-only 边界
  grantRule               NONE / ANY_OF(keys) / ALL_OF(keys)
  resourcePolicyId        PROJECT_READ / PROJECT_WRITE / OWNER_ONLY / PLATFORM 等
  targetType              PROJECT / REQUIREMENT / WORK_ITEM / SESSION / ...
  requiredPolicyProviders
  declaredEffects         组合操作及自动后续操作清单
```

- `allowedBaseRoles` 检查基础权限，`grantRule` 按真实 roleCode 查现有 `role_menu_grants`。有 ACTION 授权也不能使 VIEWER 获得写入能力；自定义 DEVELOPER 角色不会自动拥有内置 DEVELOPER 的全部 ACTION。
- `NONE` 必须显式登记且仅表示不需额外 ACTION，不跳过身份、资源与 scope 检查。不能用缺字段表示“允许”。菜单/TAB 只控制展示，后台不以能否看到页面代替资源检查。
- SERVICE 不伪造用户基础角色或 roleCode：仅可调用明确接纳 SERVICE 的操作，检查服务主体操作授权、资源策略与凭证 scope 的交集。用户专属能力默认不接纳 SERVICE；委托用户仍执行完整的用户角色/ACTION 检查。
- 初期用现有 ACTION key 作为操作目录的权限表达式，保留共享接口既有 OR 语义；不建 `permissions`、`role_permissions` 或工具授权表。若确需重分权限，另行迁移旧授权，不能悄悄按名称替换。
- HTTP 适配只登记 `method/path → operationId`，工具适配登记 `toolName → operationId`；角色、授权表达式与资源策略由 OperationSpec 单一维护。复杂组合工具由执行器拆到具体已登记操作，不能声明一个宽泛操作来代替所有步骤。

操作目录映射示例。M1 六行为本次落地范围（与 FR-04 首批一致）；M2 两行仅示例目录形态，随 FR-09 执行域迁移实施，不在 M1 开放：

| operationId | 批次 | 现有 ACTION / 基础角色 | 资源策略 | 入口举例 |
|---|---|---|---|---|
| `project.list` | M1 | NONE / 已认证用户 | 可读项目查询范围 | 页面项目列表、`list_projects`、开放项目列表 |
| `requirement.read` | M1 | NONE / 已认证用户 | 项目可读 | 需求详情、`get_requirement` |
| `requirement.draft.generate` | M1 | 新登记专用生成 ACTION，默认从需求创建/编辑授权按迁移规则派生 / ADMIN、DEVELOPER | 项目可读及草稿归属，另检查模型预算 | AI 扩充、助手草稿；不创建需求 |
| `requirement.create` | M1 | `project.requirements.action.create` / ADMIN、DEVELOPER | 项目可写 | POST 需求接口、`create_requirement` |
| `work_item.create` | M1 | `project.requirements.action.manage-workitem` / ADMIN、DEVELOPER | 反查需求所在项目可写，并校验路径关系 | 创建工作单元、`create_work_item` |
| `worklog.entry.create` | M1 | `top.worklog.action.manage-entry` / ADMIN、DEVELOPER | 本人归属 | 工作日志表单、`create_worklog_entry` |
| `session.create` | M2 | ANY_OF(`project.sessions.action.create`, `top.worklog.action.create-session`) / ADMIN、DEVELOPER | 项目可写或本人 WORKLOG，按服务端对象种类选择 | 创建开发会话、`create_session` |
| `build.trigger` | M2 | `project.builds.action.trigger` / ADMIN、DEVELOPER | 项目可写 | 构建按钮、`trigger_build` |

`requirement.draft.generate` 的权限编码在实施时加入 MenuCatalog；默认派生只针对原来已经可用的创建/编辑场景，并记录迁移证据。生成技术会话不等于创建需求，但需显示会消耗模型用量；新建业务对象仍经过单独 WRITE 确认。

#### 4.2.3 核心判定与资源策略

普通用户业务授权条件为：

```text
有效主体
AND 操作已登记且必需策略就绪
AND 基础角色满足要求
AND ACTION 表达式成立
AND 资源可见性 / 成员 / owner 条件成立
AND 凭证 scope 与资源范围允许（有凭证约束时）
```

用户主体（含 DELEGATED_USER）的内置 ADMIN 业务角色/ACTION/项目 ACL 绕过沿用平台运维语义；凭证 scope、个人秘密的专门限制、助手确认、参数与业务状态约束仍执行。`OWNER_ONLY`（本人工作日志等）默认不因 ADMIN 而扩展写入对象；如需代办，必须另有具名管理操作，不复用“本人”工具。

资源解析与策略由业务模块负责，核心引擎不直接查询各业务 Repository：

- 项目新建子对象：项目 Service 提供项目事实与成员关系；按 CAP-62，PUBLIC 全员可读，写入需成员/ADMIN；PRIVATE 仅成员/ADMIN 可读写。
- 已存在的需求/会话/构建：所属模块读取最小授权事实，得到真实 projectId/owner/environment 类型；校验对象与路径 projectId、requirementId 的关系。传一个可写项目 ID 不能授权另一个项目下的对象。
- 个人域：从主体推导 owner，忽略或拒绝非该操作支持的外部 owner 参数；WORKLOG 项目按本人归属策略处理。
- 平台域：明确登记 ADMIN-only 或服务主体 scope，不把 projectId 为空理解成全平台允许。
- 列表、搜索、统计、日志及文件：在分页/聚合前应用同一策略生成的数据范围。分页总数也必须过滤，不能先读全部数据后只在卡片层隐藏；可用参数化查询范围/批量可读 ID，不拼接客户端 SQL。
- 解析器只返回授权所需的最小事实，不把未经授权的实体正文或存在性交给工具/模型。项目策略模块可访问其自身数据，助手模块仍不得越层访问业务 Repository。

## 5. 数据模型

- 不新增业务权限授权表；角色定义与 ACTION 授权继续读取 `roles/users/menu_items/role_menu_grants`。
- CAP-62 项目可见性与 `project_members` 若尚未落地，作为资源策略实现交付；明确存量公开项目、成员及 WORKLOG 的迁移规则，不能以空成员表让所有写入突然失效或默认放行。
- M3 增加集成主体登记 `integration_subjects(id, name, active, allowed_operations, resource_constraints, created_by, created_at)`；扩展 `api_keys` 的认证模式、subject 引用、凭证 scope 和资源约束。这里保存的是集成自身授权与凭证上限，不复制用户角色授权表；委托凭证只引用用户并实时读取其权限。旧 key 显式标记 LEGACY_ADMIN，不能凭新字段缺省值获得新能力。
- 保留 `copilot_actions/tool_calls/turns` 业务审计；新增面向所有入口的 `authorization_decisions`，不把人工与 Open API 决策强塞进 copilot 表。
- 设计字段：`id, checked_at, trace_id, command_id, subject_type/id, actor, role_code, client_id, credential_id, source, operation_id, operation_version, resource_type/id, project_id, decision, reason_code, policy_version, action_id, operation_key`。不记录 token、secret 或完整敏感正文；已不可见资源的详细信息仅受限审计可查。
- 确认单增量字段见 CAP-107；接收端新增/扩展 `operation_receipts(subject_id, operation_id, operation_key, args_hash, status, result_ref, created_at, updated_at)`，以唯一键约束单次受理。迁移脚本、存量回填与保留期在实施时提交，不在本次文档变更执行。

## 6. API 概要

- 已有人工 `/api/**` 与 `/open-api/v1/**` 的业务 URL/DTO 原则上保持兼容，逐域改为调用受保护的应用 Service；外部认证语义切换按 M3 清单执行，不静默修改历史 key 身份。
- 内部 common SPI 提供 `AuthorizationService.require(operationId, target)`、主体解析、资源策略和查询范围契约；只能使用服务端可信上下文，不向模型开放可生成通用授权的工具或 `authorized=true` 参数。
- `ApiPermissionCatalog` 迁移为 HTTP 方法/路径到 operationId 的适配，工具注册表建立 toolName 到 operationId 的映射。OperationSpec 中角色、ACTION 和资源策略是唯一规则源。
- 如实施时需要前端能力预检接口，只返回当前用户所需操作的可用性与安全原因；预检结果不构成执行凭证。新端点及 DTO 随对应里程碑提交，不在本次文档变更开放。
- 用户委托协议、集成主体管理与凭证接口在 M3 明确，至少具备有效期、受众、scope、资源范围与撤销验证；不得用临时共享管理员 key 代替。

## 7. 安全

### 7.1 确认、幂等与授权

**有权限**回答“该用户可以执行吗”；**确认单**回答“该用户同意执行这一份具体操作吗”；**业务规则**回答“当前状态允许执行吗”。三者均通过才受理 AI 写操作。

执行顺序：

```text
模型提议 → schema 校验 → 真实目标/风险解析 → 核心授权预检
  → 生成确认快照 → 用户确认 → 原子认领并验证确认绑定
  → 重新读取当前主体/资源/风险 → 应用 Service 核心授权
  → 业务状态/版本/预算检查 → 幂等受理 → 执行/排队 → 结果与审计
```

- 确认快照绑定 `actor + conversationId + operationId + toolVersion + normalizedArgsHash + resolvedTarget + expectedVersion + declaredEffects + expiry`。适用的完整执行参数单独受保护保存；脱敏摘要不替代执行原文。
- Action ID、短码与确认结果由确定性后端生成。只有发起人可确认；确认接口不接收替换业务参数。危险操作保留 CAP-76 的文本短码规则，不因 Web 有卡片而放宽。
- 动态环境或风险升高、对象发生会影响操作的变化、工具契约语义变化时旧快照失效；权限减少直接拒绝，权限增加也不扩大原已确认范围。
- 组合计划的输出引用只允许解析已登记类型/字段，必须可在预览中表达其固定范围；实质性写内容在确认后才生成时重新确认，不把“按模型判断”当成可批准参数。
- 最终应用边界对来源为 AI/委托 AI 的写命令校验绑定的服务端确认凭据；不能仅在 UI/Tool 中判断。人工页面沿用既有交互规则，生产部署等平台原有审批/确认仍执行；不存在通用 `skipConfirm` 参数。
- 确认凭据与 operationKey 是服务端执行元数据，不进入模型参数 schema。合法的跨进程凭据也必须验证签发方、属主、过期、参数/目标绑定和单次消费，不能只信任传来的 actionId。

原子消费确认单只避免“同一确认重复执行”，不保证网络超时后的业务受理唯一。首批创建操作须由接收端持久化 operationKey 与业务受理结果，唯一约束建议 `(subject_id, operation_id, operation_key)`；同 key 不同参数返回冲突。业务记录与受理记录同库时同事务提交；远程系统需要自身幂等或按业务标识对账，结果不明进入待核实，禁止自动再写。

同一异步作业的终态查询仍核验当前权限；受理成功不表示作业成功。恢复执行、等待后的下一步和重试使用原主体重验，不以“以前确认过”跳过授权。

### 7.2 外部身份与凭证迁移

- 内置助手首期不需要用户委托 token；使用服务端可信执行上下文即可，不把长期登录 token 交给模型。
- 外部用户助手必须有用户委托认证，审计同时记录 user、client 与 credential。不能凭共享 HMAC key + `X-Actor` 获得用户身份；具体凭证协议在外部接入里程碑确定，必须能校验有效期、受众、scope、资源约束和撤销。
- 集成服务主体使用明确授予的操作 scope 与资源范围，可继续使用 HMAC 作为认证机制；HMAC 只证明凭证持有，不直接赋予 ADMIN。新增 scope 授权由管理员管理，签发人权限不自动复制给 key。
- scope 仅限制 OperationSpec 允许的业务操作，不能绕过服务主体本身策略。需要表达“代用户操作”时必须走委托流程，服务主体不得伪装成用户；外部助手写操作复用服务器管理的确认凭据。
- 旧 key 标记 `LEGACY_ADMIN`，只保留尚未迁移接口的既有兼容行为，不供统一助手使用。每个旧开放端点迁移前清点 CI/接入助手调用方、准备 scoped 凭证并安排切换；迁移端点拒绝 LEGACY_ADMIN，不能静默把 key 创建人当作授权用户。未迁移端点有清单和退役计划，不声称其已达到统一权限标准。

### 7.3 授权决策、错误与审计

- ALLOW、DENY、ERROR 分开：授权服务不可用或策略缺失返回 ERROR 并停止，不能把异常当作允许。
- 各应用入口记录最终决策；工具预检也可记录但标记阶段，避免统计成两次业务执行。拒绝记录不依赖随后会回滚的业务事务；受保护写命令在持久受理/审计不可用时不得派发外部副作用。
- 内部 reasonCode 可含 `SUBJECT_INACTIVE`、`OPERATION_UNKNOWN`、`ROLE_DENIED`、`ACTION_DENIED`、`RESOURCE_HIDDEN`、`OWNER_DENIED`、`SCOPE_DENIED`、`POLICY_UNAVAILABLE`、`CONFIRMATION_REQUIRED`、`TARGET_CHANGED`，便于定位，不默认向用户暴露授权明细。
- 对外复用现有 ApiError：未认证 401；可见但不可执行 403；不存在或不可见统一 404；版本/确认/参数绑定冲突 409。工具映射为对应稳定 ToolResult，前端展示可操作建议；策略不可用显示暂不可用并记录服务端原因，不伪装成用户权限不足。
- 记录“决策允许、动作受理、作业成功”三个不同事实；授权审计不能替代执行结果，也不能用模型最终回答作为完成证据。

## 8. 里程碑

| 里程碑 | FR 范围 | 实施与退出条件 |
|---|---|---|
| M1 | FR-01～08 | 完成现状盘点、核心引擎、首批资源策略与 Service 强制检查、确认绑定和审计；与 CAP-107 M1 联合验收后才开放助手写工具 |
| M2 | FR-09～11 | 会话/构建/部署/流程等逐域迁移，异步身份和每步重验就绪；证明应用边界覆盖后再移除该域重复 HTTP 条件 |
| M3 | FR-12～13 | 用户委托/服务主体、scope 与 legacy key 切换就绪；外部已接入操作与人工/工具业务权限等价，附加凭证约束仅收紧 |

CAP-107 不等待全站 M2/M3 完成，但它的每个开放工具必须先满足本 CAP 对应操作的 M1 门禁。授权底座可以独立推进；助手会话、卡片、入口迁移及模型成本交互归 CAP-107。

迁移细则：

1. 先建立新判定的影子比对，只比较决策，不重复调用业务 Service。发现差异区分历史绕过、新策略缺失与预期收紧；影子 ALLOW 不参与放行，不能把现有漏洞冻结为兼容标准。
2. 首批强制阶段保留旧 HTTP 规则作防线。新操作注册缺失、核心引擎异常和资源策略缺失均不得降级为旧的“仅 RBAC”。保留旧过滤器时还需使其使用当前有效主体，避免旧 token 角色过期导致误放行或误拒绝。
3. 核对 SecurityConfig 的 ADMIN-only、只读/写入规则与真实用例映射；不能因为已有 ACTION 就降低基础角色限制。路径歧义、同操作声明不一致、未知 ACTION 或 provider 缺失在启动/构建检查中失败。
4. 迁移完成的操作仅维护 OperationSpec，HTTP/Tool 旧规则生成/委托到该目录；证明该域所有应用入口受保护后才删除重复条件。未迁移域保留旧行为和缺口标识，不对助手开放。
5. 回滚关闭新助手/新操作映射并恢复上一版本入口，不通过关闭授权或缺策略自动放行来恢复可用性。授权表不另建副本，新增列/受理记录按向后兼容迁移保留。

## 9. 验收标准

用同一主体、操作与资源构造共用用例矩阵，验证人工 HTTP、直接应用 Service、工具以及外部接入后的 Open API；比较业务权限结论，再分别验证 AI 确认和凭证 scope 附加约束。相同业务参数在各入口的创建结果/created_by 一致，但测试分别创建独立资源，禁止为比较重复产生同一副作用。

| 场景 | 预期 |
|---|---|
| 自定义 DEVELOPER 角色拥有创建 ACTION 且是项目成员 | 三种已接入入口允许；roleCode 不变成 DEVELOPER；AI 仍需确认 |
| DEVELOPER 缺创建 ACTION，或 VIEWER 被勾选写 ACTION | 所有入口拒绝，直接调 Service 也不能写 |
| PUBLIC 非成员读/写，PRIVATE 非成员列表/详情 | 前者可读不可写；后者列表/计数无泄漏，详情 404 |
| 用可访问项目路径携带另一项目 requirement/session ID | 拒绝且不暴露真实归属，不执行副作用 |
| 用户访问他人工作日志，管理员调用“本人”工具传其他 owner | 拒绝或强制本人；不能扩大到任意个人对象 |
| 无认证上下文、停用用户、角色删除、provider 缺失 | 拒绝，不回退 local/ADMIN/VIEWER，不按 null policy 放行 |
| token/确认单保留旧角色，执行前撤销 ACTION/成员或停用 | 下一命令/确认/异步步骤重新验权并拒绝 |
| 规划自动接开发，但用户缺开发会话创建权限 | 人工与工具都在受理前拒绝组合流程，提示可用的较小操作 |
| 旧确认单改参数、切页、目标版本变化、风险升高 | 原参数不被替换；需变更时旧单失效并重新确认 |
| 并发/重复确认，写成功但响应超时，异步步骤恢复 | 最多一次受理；按 operationKey 查询；恢复时重新验权 |
| 委托用户权限足但 scope 不足/过期；服务 key 伪造 actor | 拒绝；scope 不能增权；伪造 actor 不影响实际主体 |
| 授权拒绝后业务事务回滚，或审计/受理存储异常 | 拒绝记录可查；必需记录失败不派发写副作用 |

- 拟增单测：`CoreAuthorizationServiceTest`、`SubjectResolverTest`、`OperationRegistryTest`、`ResourcePolicyTest`、`ApplicationAuthorizationBoundaryTest`、`CopilotConfirmationBindingTest`、`OpenApiDelegatedAuthorizationTest`。
- 模块集成测试直接调用真实 Spring 应用用例，证明绕开 Controller/Tool 仍受保护；同时覆盖内部调用、跨模块和异步恢复，不能只模拟授权接口返回 false。
- 正式 E2E 按 [统一规范](../core/e2e-testing-standard.md)与 [tests/README](../../tests/README.md)登记 `tests/e2e/catalog.json`，拟增 `regression.authorization-parity` 和 `regression.assistant-confirmation`。写入使用入口自起隔离实例；共享实例只跑 smoke；全部证据落 `tmp/`，不直接纳入未审核历史脚本。
- 本次只交付需求与设计文档；上述检查均为实施验收要求，未运行、未宣称通过。

## 10. 风险与开放问题

| 编号 | 风险/问题 | 口径 |
|---|---|---|
| R1 | CAP-62 文档与源码状态不一致 | 按代码和验收核实，资源策略作为实际交付项；未满足门禁不开放对应能力 |
| R2 | Service 内部调用、事件或跨模块路径漏保护 | 显式应用边界 require，领域辅助函数不公开绑定工具，以直接 Service 和异步集成测试验证 |
| R3 | 新旧权限差异导致放宽或误拒绝 | 先影子对比再逐域强制，区分历史绕过与预期收紧；不自动增授权限 |
| R4 | 撤权与已派发作业并发 | 新命令/步骤重验并记录时刻，已生效副作用不承诺回滚，停止尽力执行 |
| R5 | 历史管理员 key 影响 CI/项目接入 | 逐调用方准备 scoped 凭证后切换端点，兼容范围有清单，禁止进入用户助手链路 |
| O1 | 外部委托凭证具体协议 | M3 前依据部署与认证体系确定，不阻塞首批同进程授权底座 |
| O2 | 租户级和字段级隔离 | 不以本次项目/owner 授权代替，租户依赖 CAP-63，字段级能力另行规划 |
