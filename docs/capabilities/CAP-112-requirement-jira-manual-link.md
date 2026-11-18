# CAP-112 需求手动关联已有 Jira issue

> 能力 ID：CAP-112 ｜ 分类：集成（需求主线） ｜ 状态：已交付 ｜ 日期：2026-11-18
> 决策记录：关联的**终态与推送/同步完全一致**（一条 `external_links` 行
> internalType=REQUIREMENT/externalType=ISSUE + 需求 source=JIRA + externalKey），
> 不引入第三套关联形态；**关联即托管**——用户选的就是 Jira 上已有的 issue，其字段值才是权威，
> 故关联成功立即套用托管字段（区别于推送的「本地为准、首次同步才刷新」）；
> 权限**复用** `project.requirements.action.push-jira`，不新增权限点。

## 1. 目的

CAP-47 覆盖「平台自建需求 → 推送新建 Jira issue」，CAP-19 覆盖「Jira issue → 同步导入平台」。
中间缺一口：**需求已在平台、issue 也已在 Jira 上存在**（线下建的、别的系统迁来的、
推送失败后在 Jira 侧手工补建的）——此时既不该推送（会建重复 issue），同步也接不上
（同步只按 JQL 整批导入，不会认领既有本地需求）。本能力提供手动关联通道：
选定 Jira 实例 + 输入已有 issue key，一键把 LOCAL 需求挂到该 issue 上并转为 Jira 托管。

顺带根治关联链路的脏数据死角（REQ-27 同一需求的修复单元）：Jira 来源需求被删除后，
`external_links` 残留行让同步幂等判定永远命中「已导入」而永久跳过——
删除时清理 + 同步侧自愈双管齐下。

## 2. 功能需求

### 手动关联（M1）

- **FR-01 关联端点**：`POST /api/projects/{pid}/requirements/{rid}/jira/link`，
  入参 `{integrationId, issueKey}`，响应复用 `JiraPushResultView`。
  守卫顺序与推送同口径：幂等检查先于来源守卫——已有 ISSUE link 或 externalKey 非空
  → 409 报既有 key；source ≠ LOCAL → 400。issueKey 服务端归一化（trim + 大写）并校验
  形态 `^[A-Z][A-Z0-9_]*-\d+$`，非法 400 并给示例（如 PROJ-123）。
- **FR-02 关联前回读**：写身份链（CAP-35：个人账号 → 机器人凭证 → 400 引导绑定）解析后
  先 `getIssue` 回读——issue 不存在/不可读直接透原始错误（key 打错是最常见的失败，
  fail-visible），不落任何本地状态。
- **FR-03 跨需求去重**：持锁段（JiraWriteGuard，与同步导入互斥）内查同实例同 key 的
  既有 link：指向**存活**需求 → 409「Jira issue {key} 已关联需求 {id}」；指向**已删**
  需求的残留行就地删除后继续（与 FR-05 同步自愈同口径）。
- **FR-04 关联即托管**：登记 link 后立即 `syncFromJira`——一步置 source=JIRA +
  externalKey + 以 Jira 值刷新标题/描述/类型/优先级等托管字段（弹窗已显著警示
  本地字段将被覆盖）。可观测三件套与推送同款：`integration_calls`（action=jira_link）、
  审计、`integration.jira.linked` 领域事件。
- **FR-05 前端入口**：需求详情页「推送到 Jira」旁新增「关联 Jira」按钮
  （仅 `!isJira && canPushJira` 可见；终态 DONE/CANCELLED 禁用，口径同推送按钮）。
  弹窗（Modal，2 字段符合布局约定）：实例下拉复用 push-targets 数据源（无启用实例时
  引导去平台集成配置）+ issueKey 输入（前端同正则即时校验）+ 显著警示
  「关联后需求转为 Jira 托管：标题/描述/类型/优先级等字段将以 Jira 为准并立即刷新，
  本地变为只读」。成功后刷新详情（source 翻 JIRA，入口自然消失、改由「Jira 操作」接管）。

### 删除清理与同步自愈（M2，REQ-27 修复单元）

- **FR-06 删除时清理**：监听 `RequirementDeletedEvent`，删除该需求（internalType=
  REQUIREMENT）的全部 `external_links` 行（`deleteByInternalTypeAndInternalId`）。
  事件同步发布于删除事务内，监听器复用调用方事务；整体 try-catch 只记 warn 不抛
  （清理失败不该让删除本身回滚）。
- **FR-07 同步自愈重导入**：`JiraSyncService.upsertIssue` 命中既有 link 时先探活
  （`requireById`）——NOT_FOUND（需求已删）→ 删残留 link、按无 link 路径重新导入为新需求，
  计 IMPORTED；其他异常保持原 SKIPPED 语义。覆盖 FR-06 上线前的存量脏数据。
  语义口径：**删除 ≠ 屏蔽**——要停同步某 issue，调整同步配置的附加 JQL 排除。

## 3. 否决方案

- **需求侧新增「关联状态/关联 key」字段**：否——复用既有 source/externalKey 与
  `external_links`，终态与推送/同步完全一致，详情页/同步/刷新全链路零适配。
- **新增 `link-jira` 权限点**：否——关联与推送同为「把 LOCAL 需求交给 Jira 托管」的
  写操作，风险同级，复用 `project.requirements.action.push-jira`。
- **「只登记 link、不转托管」的弱关联模式**：否——两套托管口径会让「哪些字段以谁为准」
  说不清；要弱关联用描述里贴 issue 链接即可，不进平台语义。
- **关联时保留本地标题/描述（以本地为准）**：否——issue 是用户在 Jira 侧维护的既有
  事实，本地多半只是占位；以 Jira 为准与同步导入的口径一致（弹窗显著警示 + 不可撤销）。

## 4. 架构与模块归属

全部落在既有模块，无新模块：

- `devmind-integration`：`JiraPushService.link`（关联主流程，与 push 共用 synchronized
  需求级并发边界 + JiraWriteGuard 实例级写锁）、`ProjectJiraIssueController` /link 端点、
  `RequirementExternalLinkCleanup`（FR-06 监听器）、`JiraSyncService.upsertIssue` 自愈分支。
- `devmind-project`：**零改动**——复用 `requireEntity/requireById/syncFromJira` 与
  删除事件 `RequirementDeletedEvent`。
- `devmind-auth`：`ApiPermissionCatalog` 加一条路径规则（挂既有 push-jira 权限点）。
- 前端 `features/requirements` 自包含：`api.ts` + `components/JiraLinkModal.tsx` +
  详情页按钮/弹窗挂载。

## 5. 数据模型

无新表、无新列。关联产物 = `external_links` 一行（与推送/同步同款）：

```
external_links: project_id, integration_id,
  internal_type='REQUIREMENT', internal_id=<需求id>,
  external_type='ISSUE', external_key=<PROJ-123>, external_url, status
```

外加需求行 `source=JIRA`、`external_key=<key>`（`syncFromJira` 一步写入）。
FR-06 新增派生删除 `deleteByInternalTypeAndInternalId`（删除事件清理用，无 schema 变更）。

## 6. API 概要

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/projects/{pid}/requirements/{rid}/jira/link` | 关联已有 Jira issue；409=需求已关联/同 key 已属其他需求，400=非 LOCAL/issueKey 非法/无可用凭据/issue 不可读（连接器统一包装 BAD_REQUEST 透出 Jira 原文，如 `HTTP 404 Issue does not exist`；仅回读体映射为空时 404） |

入参 `JiraLinkRequest{integrationId, issueKey}`；响应复用 `JiraPushResultView`
（externalKey/externalUrl/remoteStatus/issueType/syncCovered）。
弹窗数据源复用 `GET .../jira/push-targets`（实例下拉），无新读端点。

## 7. 安全

- 权限复用 `project.requirements.action.push-jira`（ApiPermissionCatalog 新增路径规则）。
- 写身份走 CAP-35 身份链（个人账号 → 机器人凭证 → 400 引导绑定），密钥不出 integration 模块。
- issueKey 服务端归一化 + 形态校验；回读用回读返回的权威 key 落库（不认用户输入原文）。
- 跨需求去重防「一个 issue 被多条需求认领」；残留 link 清理只删指向已删需求的行。

## 8. 里程碑

- **M1 手动关联**：FR-01~05（端点 + 弹窗入口）。（已交付）
- **M2 删除清理与同步自愈**：FR-06~07（REQ-27 修复单元）。（已交付）

## 9. 验收标准

- 单测（JiraPushServiceTest）：关联成功（link 落库 + source=JIRA + externalKey +
  托管字段套用）；已关联 409 报既有 key；非 LOCAL 400；非法 issueKey 400（含 null/空白/
  无数字段等形态）；回读为空 404 且不落本地状态；回读抛错原样透传；同 key 属其他
  存活需求 409；残留 link（指向已删需求）清理后关联成功。
- 单测（JiraSyncServiceTest / RequirementExternalLinkCleanupTest）：link 指向已删需求时
  同步重新导入为新需求（IMPORTED + 新 link）；探活非 NOT_FOUND 异常仍 SKIPPED 且 link 保留；
  删除事件清理该需求全部 ISSUE/MR link 且不影响其他需求/其他类型；清理失败不抛。
- `mvn -q -DskipTests compile` / `mvn -q test` 全绿；`cd frontend && npx tsc -b` 通过。
- E2E（mock Jira，tests/e2e 登记用例 `regression.jira-manual-link-sync-reimport`）：
  LOCAL 需求关联 PROJ-101 成功（详情 source=JIRA + externalKey + 标题被 Jira 值覆盖），
  重复关联 409、非法 key 400、不存在 key 400（透出 Jira 原文 HTTP 404）；同步导入 →
  删除需求 → 再同步重新导入为新需求（imported=1）。

## 10. 风险与开放问题

| # | 项 | 口径 |
|---|---|---|
| R1 | 关联立即以 Jira 值覆盖本地标题/描述/类型/优先级 | 设计语义（关联即托管）；弹窗显著警示 + 不可撤销，接受 |
| R2 | 删除 Jira 来源需求后下一轮同步会重新导入 | 删除 ≠ 屏蔽（FR-07）；要停同步须调整同步配置的附加 JQL 排除，已在 CAP-19 文档注明 |
| R3 | 关联后该 issue 不在任何启用同步配置的 JQL 内 | 与推送同口径：响应 syncCovered=false，详情页可「从 Jira 刷新」手动拉取 |
| R4 | 用户输错 key 关联到别的 issue | 前端即时校验 + 服务端回读校验存在性；关联前弹窗警示，关联后由用户在 Jira 侧发现——接受（与推送选错项目同级风险） |
| O1 | 批量关联（一次挂多条需求） | 暂不支持；手动关联是低频兜底通道，逐条操作够用 |
