# CAP-19 Jira 任务/Bug 同步（拉取 → 需求 → 人工确认后自动开发）

> 能力 ID：CAP-19 ｜ 分类：底座 ｜ 状态：已实现 ｜ 日期：2026-09-01

## 1. 目的

把 Jira Server/DC 上的任务/Bug **单向拉取**进平台，落成研发主线（CAP-13）的
Requirement（DRAFT），经人工确认后走既有流程（分析 → 方案 → AI 拆分 → 派发 agent）
自动开发/修复。**只拉取不回写**：不在 Jira 侧产生任何写操作。

```
Jira Server/DC                    dev-mind
──────────────                   ─────────────────────────────────
Task / Bug / Story
      ↑  轮询（JQL + updated 水印增量，PAT Bearer 认证）
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

- **FR-01 Jira 连接器**：`JiraConnector`（`/rest/api/2`，`Authorization: Bearer <PAT>`）。
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
- **FR-04 增量水印**：JQL = `project = KEY AND (附加片段) AND updated >= "yyyy/MM/dd HH:mm"
  ORDER BY updated asc`；首轮无水印按 `created asc` 全量限页（每轮 ≤20 页 × 100 条）。
  水印 = 已处理的最大 issue updated，**整页落库后推进**并回拨 60s overlap
  （防时钟/事务边界漏单）；同步失败水印不动，重复拉取由 external_links 幂等兜住。
- **FR-05 issue → Requirement upsert**（单 issue 独立事务）：
  - 新 issue → `RequirementService.create()`（DRAFT；标题 `[KEY] summary` 截断 240；
    描述 = Jira description 原文 + 来源链接/类型/优先级/状态/报告人/标签尾注）
    + external_links 登记（external_url = `<base>/browse/<KEY>`，status = issue 状态）；
  - 已导入 → 仅当需求仍为 **DRAFT** 才刷新标题/描述；进入流程（ANALYZING 起）后
    不再覆盖（人工已接管），只刷链接状态。
- **FR-06 可观测**：每轮 `recordCall("jira_sync")` 落审计；有新增/刷新或失败时发领域事件
  `integration.jira.synced`（→ 通知中心"集成"事件，emit 去重窗口防轮询刷屏）；
  配置视图回显 lastSyncAt / lastWatermark / lastImported / lastUpdatedCount / lastError。
- **FR-07 前端闭环**：后台「平台集成」管理页（GitLab/JIRA 新建/编辑/启停/测试连接）；
  项目设置「Jira 同步」Tab（配置表单 + 开关 + 上次状态 + 立即同步）；
  需求列表 Jira 来源徽标（`GET /projects/{pid}/external-links?internalType=REQUIREMENT`
  批量反查，点击新窗跳 Jira issue 页）。

## 3. 关键设计

- **扩 SPI 而非新建接口**：`IntegrationConnector.searchIssues` 为 default 方法（默认抛
  不支持），`IntegrationService` 的 type 分发/创建白名单自动认可 JIRA 型——
  GitLabConnector 零改动。
- **新表而非复用 integration_bindings**：后者 `repo_id` 非空且 bind() 校验是 GitLab 在用的
  路径；Jira 同步配置与仓库正交，独立成表互不干扰。
- **更新边界 = DRAFT**：需求一旦被人工确认进入流程，Jira 侧的后续编辑不再回灌，
  避免覆盖人工/agent 已加工的内容；链接仍持续刷新状态供追溯。
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
| 测试连接 401/403 | PAT 失效或权限不足；Jira Server 用 Bearer PAT（非 Cloud 的 email+token） |
| 同步 0 条但 Jira 有 issue | 检查附加 JQL 是否过严；首轮按 created 全量限 20 页，超大项目多跑几轮 |
| 重复导入 | 不应发生：幂等键 = (integration_id, ISSUE, issue key)；查 external_links |
| 需求没被 Jira 更新刷新 | 预期行为：非 DRAFT 需求不覆盖；看链接 status 是否仍刷新 |
| 轮询没跑 | 配置 enabled=false / 间隔未到（看 lastSyncAt + pollIntervalSec）/ lastError |
| 时钟偏差漏单 | 水印已回拨 60s；仍可疑就把配置的 last_watermark 清空（改 Jira 项目 key 再改回）重全量 |
