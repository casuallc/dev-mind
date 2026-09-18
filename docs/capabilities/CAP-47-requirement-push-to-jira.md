# CAP-47 自建需求手动推送到 Jira

> 能力 ID：CAP-47 ｜ 分类：底座 ｜ 状态：已实现 ｜ 日期：2026-09-18

## 1. 目的

CAP-19 把 Jira 打通成**单向拉取**（JQL 轮询 → DRAFT 需求），CAP-35 又把可归因到人的写操作收敛到个人身份链，
但平台**自建（`source=LOCAL`）的需求仍然没有出站路径**——用户只能在 Jira 里把同一份需求重敲一遍，
两边彻底失联：需求列表没有 Jira Key、详情页没有「Jira 操作」、AI 后续开发也无法回看 issue。

本能力补上这一段：需求详情页对自建需求提供**手动**「推送到 Jira」入口，弹窗选实例/项目/任务类型等参数，
调用 Jira 创建 issue，登记 `external_links(REQUIREMENT ↔ ISSUE)`，并把需求转为 JIRA 托管。

```
dev-mind（自建需求）                        Jira Server/DC
────────────────────                       ──────────────
Requirement(source=LOCAL)
      │  人工点「推送到 Jira」（选实例/项目/任务类型/优先级/标签/经办人/截止日期）
      │  POST /rest/api/2/issue  ← 描述尾部自动附平台回链
      ▼
Requirement(source=JIRA, externalKey=PROJ-123)
      └─→ external_links（REQUIREMENT ↔ ISSUE，幂等真相源）
             ↓ 此后
      同步刷新托管字段（CAP-19）/ 「Jira 操作」状态转换·工时（CAP-19/27）/
      列表 Jira Key 徽标与来源筛选 / AI 会话可回看 issue
```

顺带落地 CAP-28 FR-07 早已标注为「未实现依赖」的 `IntegrationConnector.createIssue`：
SPI 从「只读 issue」扩到「可创建 issue」，后续工作日志未关联条目一键建 issue 可直接复用本能力的写通道。

> 修订（2026-09-18，FR-08）：首版把「Jira 因必填字段拒我」当成配置问题，指引用户「换任务类型或去 Jira
> 配默认值」。实测不成立——`components`/`versions`/`fixVersions`/`timetracking`/`duedate` 都是 Jira
> **标准字段**，平台对「模块」「影响版本」根本没有数据源，固定表单**不可能**覆盖所有任务类型。
> 改为**推送前问 Jira 要什么**：选完任务类型即拉 createmeta 的动态必填字段并在弹窗内渲染
> （候选值也用 Jira 给的），渲染不了的提前列明并禁用提交——见 FR-08。

> 修订（2026-09-18，FR-10）：推送默认值第一版做成**项目级单行**（`jira_push_defaults`，一个项目一行，
> 配置页挂在后台项目设置），两个硬伤：① 非 ADMIN 进不了 `/admin/*`，而「我常推哪个项目的哪个类型」
> 本就是个人偏好，普通用户根本没有配置入口；② 粒度不对——动态必填字段按「Jira 项目 + 任务类型」
> 组合变化，一行存不下多组合，换个类型就得重配一次。改为**个人推送模板**：唯一键 = 用户 + 实例 +
> Jira 项目 + 任务类型，入口收进「个人设置 → Jira 推送模板」；项目级那份连同表、端点、配置页一并废弃
> ——见 FR-10（**首次为 CAP-47 引入表结构变更**，本节末句「零表结构变更」自本次修订起不再成立）。

与 CAP-19 的关系：共用 `integrations` 表（TYPE_JIRA）、`IntegrationCipher` 凭据、`IntegrationConnector` SPI、
`external_links`（沿用 `REQUIREMENT ↔ ISSUE` 幂等键）、`integration_calls` 审计、CAP-35 的 `resolveWriteIdentity` 身份链。
除 FR-10 新增的 `jira_push_templates` 外不改任何被依赖方表结构。

## 2. 功能需求

- **FR-01 创建 issue 连接器能力**：`IntegrationConnector` 新增 `createIssue`（**写操作**，与 transitions/worklog
  同属回放通道）、`getIssue`、`listIssueTypes`、`listPriorities`、`listAssignableUsers`、`listCreateFields`
  （均 default 抛「不支持」，git 平台零改动）。`JiraConnector` 实现：`POST /issue`（`fields.project.key`/`issuetype.id`/`summary` 必填，
  `description`/`priority.name`/`assignee.name`/`labels[]`/`duedate` 可选，**空值字段一律不写进 payload**——
  写 `null` 会显式清空 Jira 侧默认值）；`GET /issue/{key}?fields=` 单条读取；
  `GET /issue/createmeta/{projectIdOrKey}/issuetypes`（**8.4+ 新路径**，按 `total/isLast` 翻页，
  404 时兜底旧版 `?projectKeys=&expand=projects.issuetypes`，过滤 `subtask=true`）；
  `GET /priority`；`GET /user/assignable/search`（`query` 参数，遇 GDPR 严格模式 400 时退 `username` 重试一次）；
  `GET /issue/createmeta/{key}/issuetypes/{id}` 创建字段元数据（见 FR-08，同为 8.4+ 新路径 + 旧端点兜底）。
  错误响应统一走 `extractMessage`：`errorMessages` 与 `errors` **都要取**（创建 issue 时 Jira 常同时给
  「工作流校验失败」+ 逐字段明细），`errors` 逐条展开为 `字段: 原因`——原样 `toString()` 的 JSON 挤成一行，
  用户既看不出哪几个字段必填，也读不到「用户 '刘长青' 不存在」这类取值错。
- **FR-02 推送目标与选项**：`GET /api/projects/{pid}/requirements/{rid}/jira/push-targets` 一次给齐
  候选实例（TYPE_JIRA + ENABLED）、默认实例/项目 key（取本项目 `jira_sync_configs` 首条）、
  任务类型/优先级列表、各字段默认值、**当前用户的全部个人推送模板（FR-10）**、
  `identitySource(PERSONAL|BOT|NONE)`、`syncCovered`；
  `GET /push-options?integrationId=&jiraProjectKey=` 在切实例/项目后重拉；`GET /assignable-users?…&q=` 供经办人搜索；
  任务类型选定后再拉 `GET /create-fields`（FR-08）补齐 Jira 侧要求的必填字段。均为只读，不审计。
  **默认值只回填与 Jira 同域的字段**：标题/描述/标签/截止日期直接取需求当前值；优先级**命中实例词表才回填**
  （平台优先级是固定英文枚举 Highest…Lowest，Jira 词表随实例语言包与项目配置，两套只是偶尔重合）；
  **不回填经办人**——平台 `assignee` 是人名（「刘长青」），Jira `assignee.name` 要的是登录名，
  回填要么 400「用户 '刘长青' 不存在」，要么在人名恰好等于某登录名时静默指派给错误的人。
  经办人一律由用户在弹窗内搜索/填写（搜索候选项回填的就是登录名），或由个人推送模板带入（FR-10）——
  模板里存的正是用户自己搜出来的登录名，不存在跨域猜测。
- **FR-03 推送（核心）**：`POST /api/projects/{pid}/requirements/{rid}/jira/push`，
  请求 `{integrationId, jiraProjectKey, issueTypeId, summary, description, backlinkUrl, priorityName,
  assigneeName, labels[], dueDate, extraFields{}}`（`extraFields` 见 FR-08）。步骤：
  1. **先**查幂等：已有 ISSUE link 或 `externalKey` 非空 → **409 幂等**并回既有 key；
     同一 issue key 已被**别的需求**关联 → 409 并指路既有需求。
     幂等检查**先于**来源守卫——推送成功会同时置 `source=JIRA`，若先查来源，重复推送只会拿到
     「已是 JIRA 来源」（没有 key 可追查）；来源守卫（`source≠LOCAL` → 400）只兜「JIRA 来源但没有 link」的异常态；
  2. 集成须 TYPE_JIRA + ENABLED；参数校验（`summary` ≤255、`labels` 无空格与逗号、
     `priority` 命中实例词表、`dueDate` 可解析、`extraFields` 过 FR-08 护栏）；
  3. 身份走 CAP-35 `resolveWriteIdentity`（个人账号 → 机器人凭证 → 400 引导「个人设置 → 第三方账号」绑定）；
  4. `createIssue` 建 issue；**服务端强制在描述尾部追加平台回链** `"\n\n" + 需求编号 + " · " + backlinkUrl`
     （`backlinkUrl` 由前端按 `window.location.origin` 拼——平台没有自身 base-url 配置，服务端造不出正确 origin）；
  5. `getIssue(key)` **单条回读**（不用 `/search`：新建 issue 后 Jira 搜索索引有延迟，可能返回 0 条）；
  6. 登记 `external_links(REQUIREMENT ↔ ISSUE, external_key=key, external_url=<base>/browse/<key>, status=issue 状态)`；
  7. 需求转托管：`source=JIRA` + `externalKey=key`（**只改这两列**，不套用托管字段——见 FR-04）；
  8. `recordCall("create_issue")` + 审计 + 领域事件 `integration.jira.pushed`（→ 通知中心）。
- **FR-04 托管字段边界（与 CAP-19 一致，但推送当刻不覆盖）**：推送**不调** `syncFromJira`，
  只落 `source`/`externalKey`。原因：`syncFromJira` 无条件覆盖 12 个托管字段，其中
  `fixVersions`/`reporter` **不在推送参数内**，瞬间套用会把本地已填值静默清空。
  托管字段的收敛交给同步（配置覆盖该 issue 时）或 FR-05 的手动刷新；本地 `status/ownerId/docId` 任何路径都不动。
- **FR-05 按 issue 手动刷新**：`POST /api/projects/{pid}/requirements/{rid}/jira/refresh`
  （**只要求存在 ISSUE link，不要求 `source=JIRA`**）——
  按 link 的 `externalKey` 走 `getIssue` 拉回，刷新 `link.status` + 托管字段（复用 `RequirementService.syncFromJira`），
  失败按调用失败上报（`integration_calls` 记 `jira_refresh` 失败 + 错误原文透出，不静默成功），已刷新的字段不回滚。
  这是 JQL 不覆盖该 issue 时的兜底通道。
  `push-targets.syncCovered`（存在同实例同项目 key 且 enabled 的 `jira_sync_configs`）为 false 时，
  前端提示「托管字段不会自动刷新，可用『从 Jira 刷新』手动拉取」。
- **FR-06 并发与幂等**：`push` 为 `synchronized` 且**锁内重查 link**（`external_links` 无唯一约束，
  需求级并发会产出双 issue/双 link）；新增 `JiraWriteGuard` 锁，与 `JiraSyncService.upsertIssue`
  的「新建需求」分支共抢同一把锁，关掉「同步 tick 在 push 存 link 之前判定 link 不存在 →
  `createFromJira` 建重复需求」的竞态。
  「回读失败」也是安全态：link 已登记、`source=JIRA` 且 `externalKey` 已落，抛 500 并在 message 内嵌 issueKey
  引导用户走 FR-05 刷新——**杜绝 `source=JIRA 且 externalKey 为空` 的半吊子态**。
- **FR-07 前端闭环**：需求详情页头卡「推送到 Jira」按钮（仅 `source=LOCAL` 且非终态渲染），
  弹窗内实例/项目/任务类型/优先级/标签/经办人/截止日期/标题/描述可编辑，
  任务类型选定后按 FR-08 渲染出 Jira 要求的必填字段（渲染不了的列出并禁用提交），
  描述下方固定只读展示将自动追加的回链；确认文案写明「转为 Jira 托管、**不可撤销**」。
  切实例时除标题/描述外全部重置（类型 id、username、优先级词表都是实例内的值，跨实例会静默推错），
  切项目时只重置任务类型。经办人搜索三段式降级（`query` → GDPR 退 `username` → 搜索不可用时退纯文本输入），
  任何一段失败都不阻断提交。
  推送失败的错误**常驻弹窗内**（`Alert`，不是一闪而过的 toast）：Jira 一次会回 8~9 条字段级错误，
  用户要边改参数边对照；表单不清空。仅带堆栈的本地排错模式仍走 `showError` 的 Modal。
- **FR-08 动态必填字段（createmeta 驱动，本能力第二次修订的核心）**：Jira 的 issue 类型可以配一堆**必填**
  字段，其中模块/影响版本/修复版本/到期日/时间跟踪都是 **Jira 标准字段**——平台侧对「影响版本」「模块」
  根本没有数据源，因此**不存在一份固定表单能覆盖所有任务类型**：用户换个任务类型就得吃一次 400
  「components: 模块是必需的。；versions: 影响版本是必需的。…」，而这条错误既没说清「在哪儿填」，
  也没给可选值。修法不是「换任务类型」或「去 Jira 配默认值」，而是**在推送前问 Jira 要什么**：

  | 方法 | 行为 |
  |---|---|
  | `GET …/jira/create-fields?integrationId=&jiraProjectKey=&issueTypeId=` | 拉 `createmeta/{key}/issuetypes/{id}`（8.4+；404 退旧版 `?projectKeys=&issueTypeIds=`，旧端点的字段表以 fieldId 为键），只取**必填且 `hasDefaultValue=false`** 的字段，按可渲染性分三区返回 |

  1. **`requiredFixed`**：固定表单里已有输入项的字段 id → 前端给对应控件补必填校验，不重复渲染
     （`summary` 本就必填，不在此列；`duedate` 之类平时可选、Jira 要求必填的才下发）；
  2. **`fields`**：需要新渲染的字段，服务端按 Jira schema 算出**控件类型**（前端不解析 Jira schema）：
     `array` + `items∈{component,version,string,option}` → `MULTI_SELECT`、`option` → `SELECT`、
     `date` → `DATE`、`string` → `TEXT`、`number` → `NUMBER`、`timetracking` → `TIMETRACKING`（两个时长输入）。
     **枚举类字段必须带 `allowedValues` 才算可渲染**：级联选择在 createmeta 里就是「option 类型但无候选值」，
     塞成自由文本框会误导用户，归入下一区。组件/版本/影响的候选值直接来自 Jira 的 `allowedValues`
     （展示名取 `value`，回传值取 `id`）；
  3. **`unsupported`**：必填但平台渲染不了的（用户选择器、级联选择等），**列出来并禁用提交**——
     让用户在提交前就知道「这个任务类型本弹窗推不了」，好过提交后吃一条读不完的 400；

  另附 `prefill`：只回填**与 Jira 同域且命中实例候选值**的本地值（目前只有 `fixVersions`，按 name 或 id
  全等匹配，不做模糊匹配——猜错版本比留空更糟）。

  **取值语义由前端按控件类型组装成 Jira 形态**（服务端不重解释、只护栏）：枚举回传 `{id}` 或 `[{id}]`、
  日期回传 `'yyyy-MM-dd'`、时间跟踪回传 `{originalEstimate, remainingEstimate}`；空值/空数组条目不写进
  payload（沿用固定参数口径）。服务端两条护栏：**不许覆盖平台管理的字段**（`project`/`issuetype` +
  固定字段，否则回链与目标项目可被静默改写）、**取值形态限两层**（标量、数组、一层扁平对象；
  放开等于把任意 JSON 转手发给 Jira）。

  **降级**：元数据拉取失败不抛错，回 `error` + 空清单且**不禁用提交**——读接口抖动不该把原本能推的
  任务类型也堵死（与 FR-02 的 `optionsError` 同口径）。

- **FR-10 个人推送模板（推送默认值的重设计，取代项目级单行）**：一个用户可为每个「实例 + Jira 项目 +
  任务类型」组合各存一行默认值（优先级/经办人/标签/动态字段），推送弹窗选定同组合时自动带入。
  入口在**个人设置 → Jira 推送模板**（`/me/settings/jira-templates`）——非 ADMIN 用户此前没有任何
  配置入口，而「我常推哪个项目的哪个类型」本就是个人偏好，不该挂后台管理。

  | 端点（`/api/me/jira-push-templates`） | 行为 |
  |---|---|
  | `GET` | 我的模板列表（更新时间倒序） |
  | `PUT` | upsert：按四元组找，命中覆盖整行、未命中新建；`integrationId`/`jiraProjectKey`/`issueTypeId` 必填否则 400 |
  | `DELETE /{id}` | 删我的某行；**非本人的 id 回 404 而不是 403**（403 等于承认「这行存在」） |
  | `GET /options?integrationId=&jiraProjectKey=` | 项目/任务类型/优先级候选 |
  | `GET /assignable-users?…&q=` | 经办人候选（回填登录名） |
  | `GET /create-fields?integrationId=&jiraProjectKey=&issueTypeId=` | 该类型的动态字段清单（FR-08 同一套元数据） |

  后三个是**需求/项目无关版**的选项端点：配置页没有需求上下文，故 `defaults`/`requirement` 入参为空
  （与 FR-08 的 `prefill` 同源逻辑，无需求即无本地值可填）；降级口径不变（失败回 `error` 不抛错）。
  个人作用域照 CAP-35 惯例：**无 `@PreAuthorize`，任何登录用户可用；隔离靠服务层
  `IdentityService.currentUser()`，ADMIN 也读不到他人模板**；未登录（异步线程）→ 401。

  **下发与匹配**：`push-targets` 一次带回当前用户的**全部**模板（量小，且免去前端二次请求），
  前端在弹窗内按三元组本地匹配。服务端**不裁剪**被禁用实例上的模板——实例清单本就在同一响应里，
  匹配是前端的事。（无登录用户上下文时回空数组，不因此失败。）

  **应用语义（弹窗侧）**：优先级/经办人/标签**只补缺**——表单里已有值的字段不覆盖（需求本体的值
  优先于模板，用户的输入优先于两者）；**动态字段例外**：它随任务类型而换，换类型总是先重置再按模板
  重填（否则旧类型的取值会错位到新类型上）。默认实例 + 默认项目下**恰好只有一个模板时预选其任务类型**
  （多个则留给用户选，不瞎猜）。模板值在弹窗内仍可临时改。

  **取值口径**：模板里存的动态字段就是 **Jira API 形态**（`{id}` / `[{id}]`，与推送 payload 同构），
  保存时走 FR-08 的同一套护栏（取值形态限两层、不许覆盖平台管理字段），推送时原样复用、**不重解释**。
  存的是取值本身而非字段清单——字段清单随 Jira 配置变化，一律实时拉取（缓存下来只会过期骗人）。

  **删除的旧实现**：`jira_push_defaults` 表、`/projects/{pid}/jira-push-defaults*` 端点、
  后台项目设置里的「Jira 推送」配置页整体废弃（唯一调用方就是那个页面）。
  **DB 残留**：`ddl-auto=update` 只增不删，旧表需**手工 `DROP TABLE jira_push_defaults`**
  （本机 H2 与 PG `156/devmind`——140.143 与 140.224 共用该库）。

## 3. 插件化接口

```java
interface IntegrationConnector {
    // CAP-47 写：创建 issue（与 transitions/worklog 同属回写通道）
    default IssueRef createIssue(IntegrationEntity cfg, String token, IssueSpec spec);
    // CAP-47 读：单条读取 / 任务类型 / 优先级 / 可指派用户 / 创建字段元数据
    default JiraIssue getIssue(IntegrationEntity cfg, String token, String issueKey, String fields);
    default List<IssueTypeRef> listIssueTypes(IntegrationEntity cfg, String token, String projectKey);
    default List<PriorityRef>  listPriorities(IntegrationEntity cfg, String token);
    default List<UserRef>      listAssignableUsers(IntegrationEntity cfg, String token, String projectKey, String q);
    default List<CreateFieldRef> listCreateFields(IntegrationEntity cfg, String token,
                                                  String projectKey, String issueTypeId);
}
record IssueSpec(String projectKey, String issueTypeId, String summary, String description,
                 String priorityName, String assigneeName, List<String> labels, LocalDate dueDate,
                 Map<String, Object> extraFields) {}   // FR-08 动态字段：键为 Jira 字段 id，值已按字段类型成形
record IssueRef(String id, String key, String url) {}
record IssueTypeRef(String id, String name, boolean subtask) {}
record PriorityRef(String id, String name) {}
record UserRef(String name, String displayName) {}
record CreateFieldRef(String id, String name, boolean required, String type, String items,
                      List<FieldOption> allowedValues, boolean hasDefault) {}
record FieldOption(String id, String value) {}   // id 为回传值，value 为展示名
```

- 凭据统一走 `CredentialResolver` / `resolveWriteIdentity`（FR-03 第 3 步），连接器只收明文 token、不接触密文；
- 任务类型**不做平台侧映射**：Jira issue type 名称随实例语言包与项目配置变化（中文实例返回「任务/缺陷/子任务」），
  一律动态拉取原样展示；需求 type → 任务类型仅在拉取失败时按名称关键字尽力给默认值。

## 4. 依赖关系

- 依赖：CAP-18（Integration/凭据/审计/External Link/SPI）、CAP-13（Requirement 落点）、
  CAP-19（external_links 幂等键与托管字段边界）、CAP-35（写身份链）；
  CAP-06 经领域事件间接触达，无模块依赖边。
- 被依赖：无。CAP-28 FR-07（工作日志未关联条目一键建 issue）可直接复用 FR-01 的写通道。
- **不改被依赖方表结构**（零迁移）。

## 5. 数据模型

新增一张表（FR-10 修订引入）：

```
jira_push_templates(id, user_id, integration_id, jira_project_key, issue_type_id,
                    priority_name, assignee_name, labels(逗号拼接), extra_fields(JSON),
                    created_at, updated_at)
  UNIQUE (user_id, integration_id, jira_project_key, issue_type_id)   -- 四元组即业务主键
```

唯一约束落在 **DB 层**（不像 `user_platform_accounts` 那样只靠应用层判重）：四元组就是这行的身份，
应用层判重在并发下会漏（同一组合存出两行，弹窗匹配到哪行取决于排序）。`extra_fields` 是
`@JdbcTypeCode(SqlTypes.LONGVARCHAR)` 的 JSON 列（照 CLAUDE.md 红线，禁裸 `@Lob`）。

复用：

```
external_links(id, project_id, integration_id,
               internal_type=REQUIREMENT, internal_id=<rid>,
               external_type=ISSUE, external_key=<issue key>,
               external_url=<base>/browse/<key>, status=<issue 状态>, created_at)
integration_calls(id, integration_id, action='create_issue'|'jira_refresh', …)
```

**已废弃的表**：`jira_push_defaults`（项目级推送默认值的旧实现，FR-10 里被 `jira_push_templates` 取代）。
`ddl-auto=update` 不会删表，需**手工清理**：

```sql
DROP TABLE jira_push_defaults;   -- H2（本机 ./data/devmind）与 PG 156/devmind（140.143/140.224 共用）
```

需求侧复用既有 `requirements.source`（LOCAL→JIRA）与 `requirements.external_key` 冗余列；
`externalUrl`/`remoteStatus` 不落库，由 `JiraRequirementRefLookup` 端口运行期反查 `external_links` 补进 `RequirementView`
（**不限 source**，故推送后自动带出）。

## 6. API 概要

```
GET  /projects/{pid}/requirements/{rid}/jira/push-targets                 推送候选实例/默认值/身份来源/覆盖判断
GET  /projects/{pid}/requirements/{rid}/jira/push-options?integrationId=&jiraProjectKey=   Jira 项目/任务类型/优先级
GET  /projects/{pid}/requirements/{rid}/jira/assignable-users?…&q=         经办人候选
GET  /projects/{pid}/requirements/{rid}/jira/create-fields?integrationId=&jiraProjectKey=&issueTypeId=
                                                                           选定实例+项目+类型后的必填字段清单（FR-08）
POST /projects/{pid}/requirements/{rid}/jira/push                          创建 issue + 关联 + 转托管
POST /projects/{pid}/requirements/{rid}/jira/refresh                       按 link 的 key 手动刷新托管字段

GET    /me/jira-push-templates                                             我的推送模板列表（FR-10）
PUT    /me/jira-push-templates                                             保存模板（四元组 upsert）
DELETE /me/jira-push-templates/{id}                                        删除我的模板
GET    /me/jira-push-templates/options?integrationId=&jiraProjectKey=      模板配置用项目/类型/优先级候选
GET    /me/jira-push-templates/assignable-users?…&q=                       模板配置用经办人候选
GET    /me/jira-push-templates/create-fields?integrationId=&jiraProjectKey=&issueTypeId=
                                                                           模板配置用动态字段清单
```

`/me/*` 为个人作用域：无 `@PreAuthorize`，任何登录用户可用，隔离在服务层（ADMIN 亦读不到他人）。
原 `/projects/{pid}/jira-push-defaults*` 已随 FR-10 删除。

## 7. 验收标准

- 自建需求详情页可点「推送到 Jira」，弹窗列出 ENABLED 的 Jira 实例与目标项目的**动态**任务类型；
- 弹窗默认值不跨域：**经办人留空**（平台人名 ≠ Jira 登录名），优先级不在实例词表内时留空；
- 推送成功后：Jira 侧出现 issue（标题/描述/类型/优先级/标签/经办人/截止日期按填写落地，描述尾部带回链），
  需求转为 `source=JIRA` 且 `externalKey`/`externalUrl`/`remoteStatus` 就位，详情页出现「Jira 操作」；
- 重复推送同一需求返回 409 且给出既有 issue key；
- 未绑定个人账号且实例无机器人凭证时，推送 400 并引导去「个人设置 → 第三方账号」绑定；
- 同一 issue key 不会被两个需求关联；同步轮询不会为已推送的 issue 再建一条需求；
- **任务类型要必填字段时弹窗能自己渲染出来**：选完类型后，模块/影响版本/修复版本以下拉多选（候选值来自
  Jira）、时间跟踪给两个时长输入、到期日给日期选择（并标必填）；`createmeta` 里渲染不了又必填的字段
  （用户选择器/级联选择）会列出来并**禁用提交**，而不是等提交后吃一条 400；
- 有默认值的必填字段（Jira 自填）与非必填字段不出现；`fixVersions` 命中实例候选值时预填；
- 填了动态字段后推送成功，Jira 侧这些字段按枚举 id/时长/日期落地（`extraFields` 不许覆盖
  `description`/`project` 等平台管理字段，取值形态超两层被 400 拦在本地）；
- Jira 拒绝创建时（必填字段缺失/取值非法），`errorMessages` 与 `errors` 的错误**逐条**显示在弹窗内
  （`字段: 原因` 一行一条，不 dump 原始 JSON），需求不落任何本地状态、远端不建 issue；
- 元数据接口抖动（createmeta 不可用）时弹窗仍可用：只提示一行错误，不阻塞本就能推的任务类型；
- 修复版本等未推送字段不会在推送瞬间被清空；JQL 未覆盖时可用「从 Jira 刷新」补齐托管字段；
- **非 ADMIN 用户**可在「个人设置 → Jira 推送模板」按「实例 + 项目 + 任务类型」维护多行默认值：
  推送弹窗选到同一组合自动带入（优先级/经办人/标签**只补缺**，动态字段随类型重置后重填），
  选到没有模板的组合不带入任何值；默认组合下只有一个模板时任务类型被预选；
- 模板**按用户隔离**：A 的模板 B 既看不到也删不掉（他人 id 回 404）；未登录访问 `/api/me/jira-push-templates*` 回 401；
- 后台项目设置（`/admin/projects/{id}`）不再有「Jira 推送」菜单，旧路由 `/me/accounts` 正常重定向到个人设置；
- 旧 `jira_push_defaults` 表手工 DROP 后，推送行为与「从未配过项目级默认值」一致（默认目标回到同步配置 → 候选首条）。

## 8. MVP 范围（暂不做）

- **Jira Cloud**：认证与 `assignee` 需 accountId 而非 username，按 Server/DC 设计（与 CAP-19 同口径）；
- **解除托管 / 反悔通道**：转 JIRA 后本地永久不可编辑托管字段，弹窗已明示不可撤销，反悔属后续能力；
- **推 `reporter` 作为固定参数**：reporter 需 Modify Reporter 权限。FR-08 里该任务类型把它列为必填时，
  平台**既不推它、也不放进 `unsupported`**——静默跳过，由 Jira 按写身份自动回填（早先按「渲染不了的
  必填字段」处理会把整个弹窗的提交禁掉，实际没人能推，见 511b99e）。同样不做「谁来推就填谁」的猜测：
  写身份是机器人时语义就错了；
- **Jira 侧 fixVersion 自由文本**：Jira 侧 fixVersion 必须已存在于项目，自由文本会被拒，
  FR-08 只按实例候选值预填/选择，不做猜测映射；
- **自动推送策略**（需求创建/确认时自动建 issue）——本期一律手动。

## 9. 排错

| 现象 | 排查 |
|---|---|
| 弹窗任务类型为空 | 区分「失败」与「确实没有」：新路径 `createmeta/{key}/issuetypes` 已按当前账号可创建过滤，空 = 该项目下当前账号无创建权限或项目无可用类型；报错则看 HTTP 状态与 `extractMessage` |
| 任务类型 404 | 实例 < 8.4：已自动兜底旧版 `?projectKeys=`；两者都 404 说明 token 无 browse 权限 |
| 推送 400「未配置可用凭据」 | 该实例既无机器人凭证、当前用户也没绑个人账号（CAP-35）；去「个人设置 → 第三方账号」绑定 |
| 推送 400 且错误里有「…是必需的」 | 该任务类型有必填字段而本地没填（FR-08 之前的老路径）。正常流程下这些字段会在选完类型后由弹窗渲染出来（有默认值的 Jira 自填）；若仍出现，看该字段是否落在弹窗顶部的「本弹窗渲染不了」清单里——那就换任务类型，或在 Jira 侧把该字段移出必填/配默认值 |
| 弹窗顶部提示「该任务类型有 N 个必填字段本弹窗渲染不了」 | createmeta 里这些字段是用户选择器/级联选择等平台没有控件的类型（FR-08 第 3 区）。这是**提前**告知，不是失败：换一个任务类型，或让 Jira 管理员给这些字段配默认值/移出必填 |
| 弹窗没有任何动态字段 | 该任务类型的必填字段都已在固定表单里（`requiredFixed`，如到期日）或都有默认值。若确认 Jira 侧确有必填自定义字段却不在清单里，看一行 `Jira 创建字段元数据拉取失败` 的 warn：新端点 404 已自动退旧端点，两端点都失败说明 token 无 browse 权限（此时弹窗只提示、不阻塞提交） |
| 推送 400「动态字段取值非法」/「动态字段不能覆盖由平台管理的字段」 | 本地护栏（FR-08）：前者是 `extraFields` 里塞了超两层的结构，后者试图覆盖 `description`/`project` 等平台字段。正常从弹窗提交不会触发；出现即为前端组装逻辑或有人直接调接口 |
| 推送 400「用户 'X' 不存在」 | 经办人填的是**显示姓名**，Jira `assignee.name` 要**登录名**（username）。用弹窗搜索候选项（回填的就是登录名）或手填登录名；中文名通常不是登录名 |
| 弹窗里经办人默认是空的 | 平台 assignee 是人名，与 Jira 登录名不同域，**不从需求回填**（见 FR-02）。想要自动带入就在「个人设置 → Jira 推送模板」里为该组合配一个 |
| 弹窗没带出我在个人设置里配的模板值 | 三个键都要对上：**实例 + Jira 项目 + 任务类型**——模板列表里看组合是否一致（改组合等于另建一行，不是改一行）；其次模板值**只补缺**，表单里已有值（需求本体的优先级/标签）不会被覆盖；动态字段随任务类型重置后重填，若该类型本次没拉回元数据（只看得到一行 `createFields.error` 的提示）则不带入 |
| 推送 400「优先级不在实例词表内」 | 平台优先级（Highest…Lowest）与实例词表不同域；弹窗只在命中词表时回填，其余留空由用户从词表里选 |
| 推送成功但需求页 Jira 状态迟迟不刷新 | 该项目在此实例上没有 enabled 的 `jira_sync_configs`（`syncCovered=false`），或附加 JQL 排除了该 issue → 用详情页「从 Jira 刷新」手动拉 |
| 推送 500 但 message 里带 issue key | issue 已建成、link 已登记，只是回读失败（Jira 抖动/权限）：用「从 Jira 刷新」补齐托管字段，勿重复推送（再点会 409） |
| 描述里的 `!xxx!` 在详情页显示成图片占位 | Jira wiki 语法与平台描述的既有差异（CAP-19 FR-09 渲染链），已在弹窗提示 |
| 推送后本地附件不再被 AI 会话投送 | 已随本能力修复（需求附件引用识别不再按 `source` 二选一）；若仍复现，查 `RequirementAttachmentProvider` 的日志行 `需求附件装配` |
