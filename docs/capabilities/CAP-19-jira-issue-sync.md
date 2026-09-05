# CAP-19 Jira 任务/Bug 同步（拉取 → 需求 → 人工确认后自动开发）

> 能力 ID：CAP-19 ｜ 分类：底座 ｜ 状态：已实现 ｜ 日期：2026-09-01

## 1. 目的

把 Jira Server/DC 上的任务/Bug **单向拉取**进平台，落成研发主线（CAP-13）的
Requirement（DRAFT），经人工确认后走既有流程（分析 → 方案 → AI 拆分 → 派发 agent）
自动开发/修复。同步本身**只拉取不回写**；FR-08 起支持**人工触发的平台侧状态回写**
（工作流转换），这是唯一的 Jira 写路径。

```
Jira Server/DC                    dev-mind
──────────────                   ─────────────────────────────────
Task / Bug / Story
      ↑  轮询（JQL = project + 附加片段，PAT Bearer 或 Basic 认证）
      │  GET /rest/api/2/search
JiraSyncService ──→ Requirement（DRAFT，标题 [PROJ-123] summary）
                  └─→ external_links（REQUIREMENT ↔ ISSUE）幂等登记
                         ↓ 人工确认（UI FlowActions）
                    分析 → 方案 → 拆分 → WorkItemOrchestrator 派发 agent
```

与 CAP-18 的关系：共用 `integrations` 表（TYPE_JIRA）、PAT 加密落库（IntegrationCipher）、
`IntegrationConnector` SPI（新增 `searchIssues` default 方法）、`external_links`
（新增内部类型 REQUIREMENT，外部类型 ISSUE 为 CAP-18 预留）与 `integration_calls` 审计。

## 2. 功能需求

- **FR-01 Jira 连接器**：`JiraConnector`（`/rest/api/2`）。认证按集成 `auth_type`：
  PAT（Jira 8.14+，`Authorization: Bearer <PAT>`）/ BASIC（8.13 及更早，
  `Basic base64(username:password)`，密文格式 `"username\npassword"`）。
  testConnection = `/myself` + `/serverInfo`；listProjects = `/project`（绑定辅助）；
  searchIssues = `/search`（JQL + startAt/maxResults 分页 + fields 白名单）。
  只读连接器：git 动词抛"不支持"，不向 Jira 发任何写请求。
- **FR-02 同步配置 CRUD**：`jira_sync_configs` 表 = integration(JIRA) + 内部项目 +
  Jira 项目 key + 附加 JQL 过滤片段 + 启用开关 + 轮询间隔（默认 300s，最小 60s）；
  同项目对同一 Jira 实例仅一条配置（唯一约束 + 409）。
  端点：`/api/projects/{pid}/jira-sync`（GET/POST/PUT/DELETE）。
- **FR-03 轮询同步**：全仓库首个 `@Scheduled`（tick 60s 可配
  `devmind.integration.jira.tick-ms`），`@EnableScheduling` 独立配置类不侵启动类；
  AtomicBoolean 全局防重入；按配置 `lastSyncAt + pollIntervalSec` 到期筛选（天然错峰）。
  手动触发 `POST /{configId}/run` 与轮询共用核心。
- **FR-04 同步范围 = 创建时所给条件**：JQL 只含 `project = KEY AND (附加片段)
  ORDER BY created asc`，不加任何其他过滤规则（无时间窗口、无增量水印）；
  每轮全量拉取匹配集，≤20 页 × 100 条防爆量；重复拉取由 external_links 幂等兜住。
- **FR-04a JQL 实时预览**：`POST /api/projects/{pid}/jira-sync/preview`
  （integrationId + jiraProjectKey + jql）按同一套拼装试算，返回命中总数 +
  前 8 条样例；创建/编辑表单内防抖实时展示，保存前即可确认过滤效果。
- **FR-05 issue → Requirement upsert**（单 issue 独立事务）：
  - 新 issue → `RequirementService.createFromJira()`（DRAFT，source=JIRA；
    标题/描述为 Jira 原文无前缀无尾注，元信息全部落列；
    **需求 createdAt/updatedAt 取 issue 自身的 created/updated**）
    + external_links 登记（external_url = `<base>/browse/<KEY>`，status = issue 状态）；
  - 已导入 → 托管字段（标题/描述/类型/优先级/经办人/报告人/标签/修复版本/截止日期）
    无条件刷新（本地只读无冲突），updatedAt 同步为 issue updated；
    本地字段 status/ownerId/docId 绝不动。
- **FR-06 可观测**：每轮 `recordCall("jira_sync")` 落审计；有新增/刷新或失败时发领域事件
  `integration.jira.synced`（→ 通知中心"集成"事件，emit 去重窗口防轮询刷屏）；
  配置视图回显 lastSyncAt / lastImported / lastUpdatedCount / lastError。
- **FR-07 前端闭环**：后台「平台集成」管理页（GitLab/JIRA 新建/编辑/启停/测试连接）；
  项目设置「Jira 同步」Tab（配置表单 + 开关 + 上次状态 + 立即同步）；
  需求列表 Jira 来源徽标（`GET /projects/{pid}/external-links?internalType=REQUIREMENT`
  批量反查，点击新窗跳 Jira issue 页）。
- **FR-08 平台侧状态回写（工作流转换）**：需求详情页「Jira 操作」下拉（仅 JIRA 来源渲染）
  动态列出关联 issue 当前可用转换（`GET /issue/{key}/transitions`，名称/目标状态随实例
  工作流，不硬编码）；确认后 `POST /api/projects/{pid}/requirements/{rid}/jira/transitions`
  执行（`transitionId` 安全闸：必须在当前可用列表内）。写操作仅限 transitions 端点，
  SPI 以 default 方法扩展（`listTransitions`/`transitionIssue`，git 平台默认抛不支持）。
  成功后按 key 单条刷新：link.status + 托管字段（本地 status/ownerId/docId 绝不动）；
  刷新失败不回滚转换（下轮同步补齐）。每次执行 `recordCall("jira_transition")` + 审计 +
  领域事件 `integration.jira.transitioned`（→ 通知中心）。

## 3. 关键设计

- **扩 SPI 而非新建接口**：`IntegrationConnector.searchIssues` 为 default 方法（默认抛
  不支持），`IntegrationService` 的 type 分发/创建白名单自动认可 JIRA 型——
  GitLabConnector 零改动。
- **新表而非复用 integration_bindings**：后者 `repo_id` 非空且 bind() 校验是 GitLab 在用的
  路径；Jira 同步配置与仓库正交，独立成表互不干扰。
- **更新边界 = 托管字段**：Jira 托管字段本地只读、同步无条件刷新（含需求时间取 issue
  自身 created/updated，列表排序与 Jira 一致）；status/ownerId/docId 为本地字段，
  同步绝不动，人工接管状态机。FR-08 回写同样守此边界：平台执行 Jira 转换只改远端 +
  刷新托管字段/remoteStatus，**不回写本地需求 status**（两边状态机独立，本地由
  FlowActions 人工流转）。
- **全量重拉而非增量水印**（2026-09 口径调整）：过滤条件就是配置里看得见的
  project + 附加 JQL，行为可预期、可用预览试算；幂等由 external_links 兜住，
  单轮限页防爆量。
- **自动开发触发 = 人工确认**（本期口径）：导入的 DRAFT 需求与手工创建的需求在流程上
  完全同构，FlowActions 链路零改动。后续可加"满足条件（如 label=auto-fix 的 Bug）
  自动进分析"的策略开关。

## 4. 依赖关系

- 依赖：CAP-18（Integration/凭据/SPI/审计/External Link）、CAP-13（Requirement 落点）、
  CAP-06（通知中心，经领域事件间接触达，无模块依赖边）。
- 被依赖：无（流程层对导入来源无感知）。

## 5. 排错

| 现象 | 排查 |
|---|---|
| 测试连接 401/403 | 凭据失效或权限不足。Jira Server 8.14+ 用 Bearer PAT；**8.13 及更早无 PAT**，认证方式选「用户名 + 密码」（Basic Auth，均非 Cloud 的 email+token） |
| 同步 0 条但 Jira 有 issue | 检查附加 JQL 是否过严（表单内预览可实时试算命中数）；单轮限 20 页，量大时多跑几轮 |
| 重复导入 | 不应发生：幂等键 = (integration_id, ISSUE, issue key)；查 external_links |
| 需求没被 Jira 更新刷新 | 看配置 lastError 与链接 status；托管字段每轮无条件刷新 |
| 轮询没跑 | 配置 enabled=false / 间隔未到（看 lastSyncAt + pollIntervalSec）/ lastError |
| 详情页没有「Jira 操作」按钮 | 非 Jira 来源 / 未关联 issue（查 external_links）/ 集成禁用 / 无可用转换或权限不足（静默降级，查集成调用审计 jira_transition） |
| 执行转换报 400/403 | Jira 工作流 validator/条件拦截（如必填字段未填、无权转换），errorMessages 原样弹出；可用转换已随状态变化时刷新页面重试 |
| 转换成功但状态没变 | 刷新失败不回滚（转换已生效）：看日志 warn，下轮同步补齐 remoteStatus |
