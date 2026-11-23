# CAP-114 会话工作区定时清理

> 能力 ID：CAP-114 ｜ 分类：底座 ｜ 状态：已实现 ｜ 日期：2026-11-23
> 决策记录：清理入口复用既有 `workspace_release` 链路（runner 丢弃式删除 + 服务端落
> RELEASED），不新造协议帧；策略落库单行配置（`workspace_cleanup_config`），
> 调度用「小时 tick + 配置比对」而非动态 cron（@Scheduled 表达式不吃 DB 值）；
> 定时任务无登录态，释放走去掉 actor 校验的内部入口（占用/状态防护保留）。

## 1. 目的

会话工作区（runner 上的 worktree）目前只有三条清理路径，都有缺口：

1. **手动收口/清理**（CAP-42/51）：依赖人点，忘了点就一直占盘；
2. **runner 侧 GC**（CAP-34 FR-05）：只兜旧布局 `sessions/<sid>` 残留与需求工作树
   超龄（30 天），CAP-42 每用户固定工作区明确不参与 GC，且参数写死在各节点
   `agent.properties` 文件里，平台无统一入口、运维要逐节点改文件；
3. **runner GC 删目录后服务端无感知**：DB 里 `workspace_state` 仍是 OPEN，
   工作区管理 tab 显示失真。

用户反馈：会话中的工作区应能自动/定时清理，清理策略要能在「设置」里配置。
本能力在**服务端**加一条平台级定时清理链路：按策略筛选「终态且超龄」的会话工作区，
走既有释放链路回收，策略在「设置」页可视化配置，清理结果站内通知。

## 2. 功能需求

### 清理策略配置与定时执行（M1）

- **FR-01 策略配置端点**：新增 `GET /api/sessions/workspace-cleanup/config` 与
  `PUT /api/sessions/workspace-cleanup/config`，读写平台级单行配置
  `WorkspaceCleanupConfigView{enabled, retentionDays, runHour, scopeRequirement,
  scopeSession, lastRunAt, lastReport}`。字段约束：retentionDays ∈ [1, 365]（默认 30），
  runHour ∈ [0, 23]（默认 3），scope 两个至少选一个。首次 GET 无配置行时返回默认值
  （不落库，PUT 才建行）。
- **FR-02 定时清理任务**：`WorkspaceCleanupTask` 每小时 tick（`fixedDelay=1h`，
  首跑延迟 5min——避开启动期 runner 未重连的窗口），tick 内读配置：
  `enabled=true` 且当前小时 == runHour 且 `lastRunAt` 不在今天 → 执行一轮清理并更新
  `lastRunAt/lastReport`。调度配置（cron/开关）不放 yml——策略即 DB 配置，改即生效，
  不需要重启。
- **FR-03 清理候选筛选**：`workspace_state=OPEN` 且会话状态为终态（非
  RUNNING/WAITING_INPUT/WAITING_AUTH/SUSPENDED）且 `agent_node_id` 非空
  （历史本机会话无节点可清理）且 `updated_at < now - retentionDays`；
  `scopeRequirement=false` 时排除 `req-` 键会话，`scopeSession=false` 时排除
  非 `req-` 键会话（sid- 键与 CAP-42 旧布局 work@owner）。进行中会话天然被
  状态条件排除（需求级共享工作树另有进行中占用预检兜底）。
- **FR-04 清理动作复用释放链路**：逐候选调用内部释放入口（自
  `SessionManagerService.releaseWorkspace` 抽出的去 actor 校验版本，占用/状态/协议
  门控全保留）：下发 `workspace_release` 帧（丢弃语义，不动远端分支）→ runner 删
  worktree + 本地分支 → 成功落 RELEASED（需求级连带同需求会话行 + 需求行回「未开启」）。
  单个失败（节点离线/目录占用/协议过低）只记原因进报告，**不阻断本轮其余候选**，
  下轮重试。
- **FR-05 结果通知**：每轮结束只要「清理数 > 0 或失败数 > 0」就发布站内通知
  `WORKSPACE_CLEANUP_DONE`：清理 N 个、跳过 M 个、失败 K 个（失败附逐条原因）；
  0 命中轮只落报告不发通知（每天常态，不打扰管理员）。无论是否通知，报告
  （trigger/finishedAt/cleaned/skippedActive/failed/details，明细超 100 条截断）
  一律落库供设置页查看。复用 `notificationPublisher`（同 CAP-51 释放失败告警路径）。
- **FR-06 设置页「工作区清理」tab**：`/settings` 新增第四个视图「工作区清理」
  （`workspace-cleanup`）。表单 = 启用开关 + 保留天数（InputNumber）+ 执行时间
  （Select 0~23 点）+ 清理范围（Checkbox：需求级工作区 / 会话级工作区）；
  下方展示上次执行时间与结果报告（pre 文本）。Card extra = 刷新 + 保存（权限门控）
  + 「立即执行一次」（管理员手动触发一轮，不等定时点；异步执行，前端轮询刷新报告）。
  布局遵循前端内容区布局约定（Card+Segmented，extra 按钮）。
- **FR-07 立即执行端点**：`POST /api/sessions/workspace-cleanup/run` 手动触发一轮
  （无视 enabled/runHour/lastRunAt，仍按 retentionDays/scope 筛选），异步执行立即
  返回，结果经 lastReport 与通知透出。
- **FR-08 权限登记**：MenuCatalog 新增 `tab("top.settings", "workspace-cleanup",
  "工作区清理", 50)` 与 `action("top.settings", "manage-workspace-cleanup",
  "管理工作区清理策略", 50, true, seedFrom("admin.notifications"))`——tab 与 action
  的默认授权都 seed 自 admin 区节点（平台级策略，普通用户默认不可见不可改）；
  ApiPermissionCatalog 登记 PUT config 与 POST run 两条写规则。
- **FR-09 超龄判定口径**：用会话行 `updated_at`（会话最后活动时间）而非目录 mtime——
  服务端看不见 runner 磁盘；与 runner 侧 GC 的 mtime 口径独立并存（runner GC 兜
  孤儿目录，本能力管「服务端在册」工作区）。

## 3. 否决方案

- **把策略下发给 runner 调它自己的 GC 参数**：要改协议（新下行帧）+ runner 升级门控
  + 各节点漂移对账，成本高；且 runner GC 是「磁盘视角」兜底，与本能力「服务端在册
  视角」职责不同，两者并存不冲突。
- **动态 cron（SchedulingConfigurer 按 DB 值注册）**：Spring 调度注册动态化复杂且
  排查不直观；「小时 tick + 配置比对」语义等价、实现三十行、天然支持改配置即生效。
- **清理前 N 天先发预警通知再删**：多一个状态机（已预警/待删），M1 不需要——
  候选本身就是「终态 + 超龄」，误删代价是未收口改动丢失，故保留天数默认 30 天
  且**只清终态会话**，进行中工作区永不入选；有预警诉求再演进 M2。
- **清理 FINALIZED 工作区**：FINALIZED 意味着收口已删目录（CAP-42），无盘可清，
  不在候选内。

## 4. 架构与模块归属

```
devmind-session（工作区生命周期归属模块，无新模块）
  model/WorkspaceCleanupConfigEntity（新）—— 单行配置表（id 固定 1）
  repo/WorkspaceCleanupConfigRepository（新）
  service/WorkspaceCleanupService（新）—— 配置读写 + 候选筛选 + 逐候选释放 + 报告落库/通知
  service/WorkspaceCleanupTask（新）—— @Scheduled 小时 tick
  service/SessionManagerService —— releaseWorkspace 抽内部入口（去 actor 校验，其余保留）
  controller/SessionController —— GET/PUT config + POST run 三个端点
  dto/WorkspaceCleanupConfigView（新）

devmind-auth
  menu/MenuCatalog —— tab + action 登记（seed 自 admin.notifications）
  menu/ApiPermissionCatalog —— PUT/POST 写规则

frontend features/auth（设置页既有归属）
  components/SettingsViewSwitch.tsx —— tab 登记（key/label/权限节点）
  pages/SettingsPage.tsx —— 挂面板 + extra 按钮
  features/sessions/components/WorkspaceCleanupPanel.tsx（新）—— 表单 + 上次报告
  features/sessions/api.ts / types.ts —— config 读写 + run
```

## 5. 数据模型

`workspace_cleanup_config`（单行，id 固定 1，`ddl-auto=update` 自动演进）：

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 固定 1 |
| enabled | BOOLEAN 默认 false | 总开关（默认关，管理员显式开启） |
| retention_days | INT 默认 30 | 保留天数（终态会话工作区超龄即清） |
| run_hour | INT 默认 3 | 每日执行时间（小时，0~23） |
| scope_requirement | BOOLEAN 默认 true | 是否清需求级工作区（req- 键） |
| scope_session | BOOLEAN 默认 true | 是否清会话级工作区（sid- 键与旧布局） |
| last_run_at | TIMESTAMP 可空 | 上次执行时间（防同日重跑 + 页面展示） |
| last_report | LONGVARCHAR 可空 | 上次执行报告（JSON 文本，页面展示） |
| updated_at / updated_by | TIMESTAMP / VARCHAR | 配置最后修改 |

## 6. API 概要

- `GET /api/sessions/workspace-cleanup/config` → `WorkspaceCleanupConfigView`（全认证可读）
- `PUT /api/sessions/workspace-cleanup/config` ← `WorkspaceCleanupConfigView`（写操作
  走 `top.settings.action.manage-workspace-cleanup`）
- `POST /api/sessions/workspace-cleanup/run` → 立即触发一轮（同写权限），异步执行，
  返回 202 语义 `{started: true}`

## 7. 安全

- 配置读写端点沿用 SecurityConfig 既有口径（GET 全认证、PUT/POST 走权限目录写规则）；
  tab/action 默认授权 seed 自 admin 区，普通用户默认不可见该 tab。
- 清理是**丢弃语义**（不合并不 push），防线 = 候选只选终态会话 + 超龄门槛 +
  需求级占用预检；进行中会话（含 SUSPENDED）永不入选。
- 释放帧走既有 `workspace_release` 协议（v7/v10 门控保留），无新协议面。
- 定时任务无登录态：内部释放入口跳过 actor 校验，但**不跳过**会话状态/占用/协议检查。

## 8. 里程碑

- **M1 定时清理 + 设置页**（已交付）：FR-01~09。

## 9. 验收标准

- `mvn -q -pl devmind-session -am test` 通过（WorkspaceCleanupService 测试：候选筛选
  口径——终态/超龄/节点/scope 排除；逐个失败不阻断；配置默认值与校验；
  tick 判重——同日不重跑、disabled 不跑、hour 不匹配不跑）。
- `npx tsc -b` 通过。
- 起服务后：设置页出现「工作区清理」tab（管理员可见），保存策略 → DB 落行；
  「立即执行一次」→ 超龄终态会话工作区被释放（workspace_state=RELEASED，
  runner 侧目录删除），收到站内通知，tab 展示报告；普通用户默认看不到该 tab。

## 10. 风险与开放问题

| # | 项 | 口径 |
|---|---|---|
| R1 | 终态判定用会话 status，runner 崩溃但 hello 对账未判死的会话可能残留 RUNNING 永不入选 | 接受（宁可不清不可错清；对账修复后自然入选） |
| R2 | 服务端 RELEASED 与 runner 侧 GC 删目录的状态不同步是既有问题（GC 删了 OPEN 仍在） | 本能力收窄了该窗口（超龄前服务端主动清），彻底对账属 runner GC 改造，另立能力 |
| O1 | 清理前预警（提前 N 天通知工作区 owner） | 展望，M2 |
| O2 | 按项目/节点差异化策略 | 展望，先平台级单行 |
