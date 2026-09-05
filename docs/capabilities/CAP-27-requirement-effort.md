# CAP-27 需求工时（AI 实际耗时汇总 + Jira 工时字段同步/回写）

> 能力 ID：CAP-27 ｜ 分类：组装层 ｜ 状态：已实现 ｜ 日期：2026-09-05

## 1. 目的

让需求的"花了多少功夫"自动可见、可对账：

- **AI 实际耗时**：需求下所有 agent 会话的时长自动汇总，无需人工填报；
- **Jira 工时字段**：time tracking（originalEstimate / timeSpent）随同步落到需求上展示；
- **工时回写**：平台侧一键把工时（默认 = AI 实际耗时）登记进 Jira worklog，
  让 Jira 的已用工时与平台实际执行对齐。

```
sessions（requirementId, createdAt, finishedAt, status）
      │  SessionAgentTimeLookup（project 端口 RequirementAgentTimeLookup 的 session 实现）
      ▼
RequirementView.agentSeconds ──────┐
Jira timeoriginalestimate/timespent│  需求列表「AI 耗时」「Jira 工时」列
      │  随 JiraSyncService 落托管列 │  需求详情属性卡「AI 执行耗时/预估/已用」
      ▼                            │
requirements.estimated_seconds / spent_seconds
      ▲
      └── 「Jira 操作 → 登记工时」POST /issue/{key}/worklog（复用 CAP-19 FR-08 回写通道）
```

## 2. 功能需求

- **FR-01 AI 实际耗时汇总**：project 模块定义端口
  `RequirementAgentTimeLookup.secondsFor(Collection<requirementId>)`，
  session 模块实现 `SessionAgentTimeLookup`（会话表按 requirementId 批量查，
  逐条：终态按 finishedAt-createdAt、活跃（RUNNING/WAITING_*）算到当前时刻、
  挂起/异常无 finishedAt 不计；按需求求和，零/负值丢弃）。
  `RequirementView` 增 `agentSeconds`（秒，无会话 → null），list/get 批量带出
  （与 refsFor 同模式，ObjectProvider 探测注入，无 session 模块时降级为 null）。
- **FR-02 Jira 工时字段同步**：`JiraSyncService.ISSUE_FIELDS` 增
  `timeoriginalestimate,timespent`（Jira 返回秒数，可空）；`JiraIssue`/
  `JiraManagedFields`/`RequirementEntity` 增 `estimatedSeconds`/`spentSeconds`
  （`estimated_seconds`/`spent_seconds` 列，Long 可空无需默认值）；
  属托管字段——同步无条件刷新，本地表单不可编辑，本地 create/update 通道不写。
- **FR-03 工时回写（worklog）**：SPI `IntegrationConnector` 增 default
  `logWork(cfg, token, issueKey, seconds, comment)`（默认抛不支持，GitLab 零改动）；
  `JiraConnector` 实现 `POST /rest/api/2/issue/{key}/worklog`
  （body `{"timeSpentSeconds": s, "comment": c?}`）。
  端点 `POST /api/projects/{pid}/requirements/{rid}/jira/worklog`；
  `JiraIssueActionService.logWork` 复用 FR-08 的 resolve 链（Jira 来源/链接/集成启用）
  与 refreshAfterTransit 单条刷新（timeSpent 随即回落托管列）；
  秒数校验 1..360000；`recordCall("jira_worklog")` + 审计 + 领域事件
  `integration.jira.worklogged`（→ 通知中心）。
- **FR-04 前端展示与登记**：
  - `shared/utils/format.ts` 增 `fmtDuration(sec)`：null/≤0 → '-'；<1h → 'Xm'；
    整点 → 'Xh'；否则 'XhYm'。
  - 需求列表：全来源「AI 耗时」列；Jira 视图加「Jira 工时」列（已用 / 预估）。
  - 需求详情属性卡：「AI 执行耗时」（全来源）+ Jira 来源托管行「预估工时」「已用工时」。
  - 「Jira 操作」下拉菜单底部加「登记工时」（与转换项分隔线隔开）：弹窗
    InputNumber 小时（min 0.25，step 0.25，**默认值 = agentSeconds 按 0.25h 取整**）
    + 备注 Input → 提交后刷新；无可用转换（如 issue 已 Done）时主按钮改为登记工时。

## 3. 关键设计

- **跨模块取数走端口而非依赖**：project 不依赖 session，工时汇总由
  `RequirementAgentTimeLookup` 端口解耦（同 RequirementExternalRefLookup 手法），
  session 缺席时视图字段为 null，前端 fmtDuration 显示 '-'。
- **口径：秒存储、活跃算到当前**：一律以秒存储/传输，格式化只在前端；
  活跃会话（isActive）按 now-createdAt 动态计，详情/列表每次加载即最新，
  无需定时任务固化。
- **托管边界不变**：estimated/spent 与标题/优先级同级——同步/回写后刷新，
  本地只读；本地 status/ownerId/docId 依旧绝不动（见 CAP-19 关键设计）。
- **回写复用 FR-08 通道**：worklog 与 transitions 同属"人工触发的 Jira 写路径"，
  共用 resolve 链/刷新/审计/事件手法；写端点白名单因此扩为 transitions + worklog 两个。

## 4. 依赖关系

- 依赖：CAP-05（会话表与状态机）、CAP-13（Requirement 落点）、
  CAP-19（Jira 同步链路与 FR-08 回写通道）。
- 被依赖：无。

## 5. 排错

| 现象 | 排查 |
|---|---|
| AI 耗时显示 '-' | 需求下无会话 / 会话均无时长（挂起且无 finishedAt 不计）；活跃会话按当前时刻动态算，刷新页面即更新 |
| 预估/已用工时 '-' | 非 Jira 来源无此字段；Jira 侧未设 time tracking；未同步过（手动触发一次同步） |
| 登记工时 400 | 秒数超出 1..360000；或 Jira 时间跟踪未启用/无 worklog 权限，errorMessages 原样弹出 |
| 登记后已用工时没变 | 登记后刷新失败不回滚（远端已生效）：看日志 warn，下轮同步补齐 |
| 「登记工时」入口不出现 | 同 CAP-19 FR-08：非 Jira 来源/未关联/集成禁用/拉取失败静默降级（查集成调用审计 jira_worklog） |
