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

与 CAP-19 的关系：共用 `integrations` 表（TYPE_JIRA）、`IntegrationCipher` 凭据、`IntegrationConnector` SPI、
`external_links`（沿用 `REQUIREMENT ↔ ISSUE` 幂等键）、`integration_calls` 审计、CAP-35 的 `resolveWriteIdentity` 身份链。
**零表结构变更**——不新增列、不新增表。

## 2. 功能需求

- **FR-01 创建 issue 连接器能力**：`IntegrationConnector` 新增 `createIssue`（**写操作**，与 transitions/worklog
  同属回放通道）、`getIssue`、`listIssueTypes`、`listPriorities`、`listAssignableUsers`（均 default 抛「不支持」，
  git 平台零改动）。`JiraConnector` 实现：`POST /issue`（`fields.project.key`/`issuetype.id`/`summary` 必填，
  `description`/`priority.name`/`assignee.name`/`labels[]`/`duedate` 可选，**空值字段一律不写进 payload**——
  写 `null` 会显式清空 Jira 侧默认值）；`GET /issue/{key}?fields=` 单条读取；
  `GET /issue/createmeta/{projectIdOrKey}/issuetypes`（**8.4+ 新路径**，按 `total/isLast` 翻页，
  404 时兜底旧版 `?projectKeys=&expand=projects.issuetypes`，过滤 `subtask=true`）；
  `GET /priority`；`GET /user/assignable/search`（`query` 参数，遇 GDPR 严格模式 400 时退 `username` 重试一次）。
- **FR-02 推送目标与选项**：`GET /api/projects/{pid}/requirements/{rid}/jira/push-targets` 一次给齐
  候选实例（TYPE_JIRA + ENABLED）、默认实例/项目 key（取本项目 `jira_sync_configs` 首条）、
  任务类型/优先级列表、各字段默认值、`identitySource(PERSONAL|BOT|NONE)`、`syncCovered`；
  `GET /push-options?integrationId=&jiraProjectKey=` 在切实例/项目后重拉；`GET /assignable-users?…&q=` 供经办人搜索。
  均为只读，不审计。
  **默认值只回填与 Jira 同域的字段**：标题/描述/标签/截止日期直接取需求当前值；优先级**命中实例词表才回填**
  （平台优先级是固定英文枚举 Highest…Lowest，Jira 词表随实例语言包与项目配置，两套只是偶尔重合）；
  **不回填经办人**——平台 `assignee` 是人名（「刘长青」），Jira `assignee.name` 要的是登录名，
  回填要么 400「用户 '刘长青' 不存在」，要么在人名恰好等于某登录名时静默指派给错误的人。
  经办人一律由用户在弹窗内搜索/填写（搜索候选项回填的就是登录名）。
- **FR-03 推送（核心）**：`POST /api/projects/{pid}/requirements/{rid}/jira/push`，
  请求 `{integrationId, jiraProjectKey, issueTypeId, summary, description, backlinkUrl, priorityName,
  assigneeName, labels[], dueDate}`。步骤：
  1. **先**查幂等：已有 ISSUE link 或 `externalKey` 非空 → **409 幂等**并回既有 key；
     同一 issue key 已被**别的需求**关联 → 409 并指路既有需求。
     幂等检查**先于**来源守卫——推送成功会同时置 `source=JIRA`，若先查来源，重复推送只会拿到
     「已是 JIRA 来源」（没有 key 可追查）；来源守卫（`source≠LOCAL` → 400）只兜「JIRA 来源但没有 link」的异常态；
  2. 集成须 TYPE_JIRA + ENABLED；参数校验（`summary` ≤255、`labels` 无空格与逗号、
     `priority` 命中实例词表、`dueDate` 可解析）；
  3. 身份走 CAP-35 `resolveWriteIdentity`（个人账号 → 机器人凭证 → 400 引导「我的 → 第三方账号」绑定）；
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
  描述下方固定只读展示将自动追加的回链；确认文案写明「转为 Jira 托管、**不可撤销**」。
  切实例时除标题/描述外全部重置（类型 id、username、优先级词表都是实例内的值，跨实例会静默推错），
  切项目时只重置任务类型。经办人搜索三段式降级（`query` → GDPR 退 `username` → 搜索不可用时退纯文本输入），
  任何一段失败都不阻断提交。

## 3. 插件化接口

```java
interface IntegrationConnector {
    // CAP-47 写：创建 issue（与 transitions/worklog 同属回写通道）
    default IssueRef createIssue(IntegrationEntity cfg, String token, IssueSpec spec);
    // CAP-47 读：单条读取 / 任务类型 / 优先级 / 可指派用户
    default JiraIssue getIssue(IntegrationEntity cfg, String token, String issueKey, String fields);
    default List<IssueTypeRef> listIssueTypes(IntegrationEntity cfg, String token, String projectKey);
    default List<PriorityRef>  listPriorities(IntegrationEntity cfg, String token);
    default List<UserRef>      listAssignableUsers(IntegrationEntity cfg, String token, String projectKey, String q);
}
record IssueSpec(String projectKey, String issueTypeId, String summary, String description,
                 String priorityName, String assigneeName, List<String> labels, LocalDate dueDate) {}
record IssueRef(String id, String key, String url) {}
record IssueTypeRef(String id, String name, boolean subtask) {}
record PriorityRef(String id, String name) {}
record UserRef(String name, String displayName) {}
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

无新增表 / 无新增列。复用：

```
external_links(id, project_id, integration_id,
               internal_type=REQUIREMENT, internal_id=<rid>,
               external_type=ISSUE, external_key=<issue key>,
               external_url=<base>/browse/<key>, status=<issue 状态>, created_at)
integration_calls(id, integration_id, action='create_issue'|'jira_refresh', …)
```

需求侧复用既有 `requirements.source`（LOCAL→JIRA）与 `requirements.external_key` 冗余列；
`externalUrl`/`remoteStatus` 不落库，由 `JiraRequirementRefLookup` 端口运行期反查 `external_links` 补进 `RequirementView`
（**不限 source**，故推送后自动带出）。

## 6. API 概要

```
GET  /projects/{pid}/requirements/{rid}/jira/push-targets                 推送候选实例/默认值/身份来源/覆盖判断
GET  /projects/{pid}/requirements/{rid}/jira/push-options?integrationId=&jiraProjectKey=   Jira 项目/任务类型/优先级
GET  /projects/{pid}/requirements/{rid}/jira/assignable-users?…&q=         经办人候选
POST /projects/{pid}/requirements/{rid}/jira/push                          创建 issue + 关联 + 转托管
POST /projects/{pid}/requirements/{rid}/jira/refresh                       按 link 的 key 手动刷新托管字段
```

## 7. 验收标准

- 自建需求详情页可点「推送到 Jira」，弹窗列出 ENABLED 的 Jira 实例与目标项目的**动态**任务类型；
- 弹窗默认值不跨域：**经办人留空**（平台人名 ≠ Jira 登录名），优先级不在实例词表内时留空；
- 推送成功后：Jira 侧出现 issue（标题/描述/类型/优先级/标签/经办人/截止日期按填写落地，描述尾部带回链），
  需求转为 `source=JIRA` 且 `externalKey`/`externalUrl`/`remoteStatus` 就位，详情页出现「Jira 操作」；
- 重复推送同一需求返回 409 且给出既有 issue key；
- 未绑定个人账号且实例无机器人凭证时，推送 400 并引导去「我的 → 第三方账号」绑定；
- 同一 issue key 不会被两个需求关联；同步轮询不会为已推送的 issue 再建一条需求；
- 修复版本等未推送字段不会在推送瞬间被清空；JQL 未覆盖时可用「从 Jira 刷新」补齐托管字段。

## 8. MVP 范围（暂不做）

- **Jira Cloud**：认证与 `assignee` 需 accountId 而非 username，按 Server/DC 设计（与 CAP-19 同口径）；
- **解除托管 / 反悔通道**：转 JIRA 后本地永久不可编辑托管字段，弹窗已明示不可撤销，反悔属后续能力；
- **推 `reporter` / `fixVersions`**：reporter 需 Modify Reporter 权限；Jira 侧 fixVersion 必须已存在于项目，
  自由文本会被拒，不做猜测映射；
- **必填自定义字段预检**：任务类型若配了必填自定义字段，创建会被 Jira 400 拒绝，错误原样透出给用户
  （不预拉 `createmeta/{key}/issuetypes/{id}` 做字段预检）；
- **自动推送策略**（需求创建/确认时自动建 issue）——本期一律手动。

## 9. 排错

| 现象 | 排查 |
|---|---|
| 弹窗任务类型为空 | 区分「失败」与「确实没有」：新路径 `createmeta/{key}/issuetypes` 已按当前账号可创建过滤，空 = 该项目下当前账号无创建权限或项目无可用类型；报错则看 HTTP 状态与 `extractMessage` |
| 任务类型 404 | 实例 < 8.4：已自动兜底旧版 `?projectKeys=`；两者都 404 说明 token 无 browse 权限 |
| 推送 400「未配置可用凭据」 | 该实例既无机器人凭证、当前用户也没绑个人账号（CAP-35）；去「我的 → 第三方账号」绑定 |
| 推送 400 且 errorMessages 提到必填字段 | Jira 侧该任务类型配了必填自定义字段（如经办人/自定义字段），错误已原样透出；先在 Jira 项目里配默认值或用别的任务类型 |
| 推送 400「用户 'X' 不存在」 | 经办人填的是**显示姓名**，Jira `assignee.name` 要**登录名**（username）。用弹窗搜索候选项（回填的就是登录名）或手填登录名；中文名通常不是登录名 |
| 弹窗里经办人默认是空的 | 设计如此：平台 assignee 是人名，与 Jira 登录名不同域，不回填（见 FR-02） |
| 推送 400「优先级不在实例词表内」 | 平台优先级（Highest…Lowest）与实例词表不同域；弹窗只在命中词表时回填，其余留空由用户从词表里选 |
| 推送成功但需求页 Jira 状态迟迟不刷新 | 该项目在此实例上没有 enabled 的 `jira_sync_configs`（`syncCovered=false`），或附加 JQL 排除了该 issue → 用详情页「从 Jira 刷新」手动拉 |
| 推送 500 但 message 里带 issue key | issue 已建成、link 已登记，只是回读失败（Jira 抖动/权限）：用「从 Jira 刷新」补齐托管字段，勿重复推送（再点会 409） |
| 描述里的 `!xxx!` 在详情页显示成图片占位 | Jira wiki 语法与平台描述的既有差异（CAP-19 FR-09 渲染链），已在弹窗提示 |
| 推送后本地附件不再被 AI 会话投送 | 已随本能力修复（需求附件引用识别不再按 `source` 二选一）；若仍复现，查 `RequirementAttachmentProvider` 的日志行 `需求附件装配` |
