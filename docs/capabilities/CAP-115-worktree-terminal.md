# CAP-115 Worktree 终端（基于 bash 直接操作 git worktree 的轻量改动入口）

> 能力 ID：CAP-115 ｜ 分类：组装层 ｜ 状态：**已完成（M1/M2/M3 全部落地并 E2E 通过）** ｜ 日期：2026-11-23
> 决策记录：终端工作区登记**不依赖 coding 会话**——新建 `terminal_workspaces` 表作为
> worktree 的独立登记载体（对齐 session_repos 的 repos_json 快照语义）；runner 侧目录
> 物化复用 `RunnerWorkspace.ensureUserWorktree` 幂等语义（目录存在且分支匹配即复用），
> 新增 `terminal_ws_prepare` 帧（协议 v26）只做「物化 + 绑定登记」；命令执行**复用既有
> terminal exec 链路**（协议 v16/17 的 PersistentShell），sessionId 用 `term-<id>` 伪 id；
> 收口抽取 `WorkspaceFinalizeCore` 内核供会话/需求/终端三入口共用；runner 侧
> `RunnerWorkspace.finalize/release` 的布局判定由 `specs.size()>1` 改为按 worktree/缓存
> 实物证据逐库识别（终端工作区单库也走 prepareMulti 多库布局，size 判据必误判，
> M3 E2E 暴露后修复）；写锁、危险命令二次确认（428 + 短码）、心跳/挂起
> 语义均在服务端落地，runner 白名单强制校验红线不变。

## 1. 目的

目前针对项目下代码的开发只能通过创建 coding 会话（AI agent）完成。实际工作中存在
大量「一句话级」的小改动（改个配置、加一行日志、修个拼写、调个文案），拉起一个完整
AI 会话成本高、链路长、还要等 agent 跑完，反而不如人直接手改一个文件来得快。

本能力提供一个「终端式」入口：用户可以直接进入某个 git worktree（或新建一个独立
worktree），用 bash 对代码进行操作，改完后仍复用现有的收口/合并流程，保证基线安全
与可追溯。

## 2. 功能需求

### M1 终端工作区登记与管理

- **FR-01 实体 terminal_workspaces**：终端工作区登记表。字段：id varchar(36) PK、
  project_id、requirement_id（可空）、work_item_id（可空）、workspace_key
  （新建=`term-<8位小写短id>`；接管已有 worktree 时=被接管的 key 如 `req-<rid>`）、
  branch（新建=`terminal/<同短id>`；接管时=被接管分支）、owner、agent_node_id、
  base_branch、repos_json（创建时从 project_repos 拷快照的 JSON 数组，字段对齐
  SessionRepoEntity：remoteUrl/baseBranch/branch/localPath）、state
  （OPEN/FINALIZED/RELEASED，默认 OPEN）、lock_session_id、lock_owner、lock_at、
  finalize_detail、finalized_at、last_active_at、created_by、created_at。
- **FR-02 实体 terminal_sessions**：终端会话表。字段：id、workspace_id（FK）、
  user_id、state（RUNNING/SUSPENDED/CLOSED，默认 RUNNING）、started_at、
  last_heartbeat_at、ended_at。
- **FR-03 命名扩展**：devmind-project `WorktreeManager` 新增
  `workspaceKeyForTerminal()` → `term-<短id>`、`branchForTerminal()` →
  `terminal/<短id>`；devmind-common `RunnerWorkspace` 分支前缀校验（原强制
  `feature/` 前缀）放行 `terminal/` 前缀（白名单并列，不做任意放行）。
- **FR-04 协议 v26 + terminal_ws_prepare 帧**：devmind-common `AgentProtocol.CURRENT`
  25→26，新增帧常量 `terminal_ws_prepare`（及 ack）；`AgentNodeConnector` SPI 加
  `terminalWorkspacePrepare`；devmind-agent `AgentConnectionRegistry` 实现：协议版本
  门控（不足 v26 → 409）、waiter 等 ack（参照 finalizeWorkspace）、每次下发记
  `agent_audit_logs` 审计（新事件 `WS_TERMINAL_PREPARE`）。
- **FR-05 runner 侧 prepare 处理**：收到 terminal_ws_prepare(projectId, owner,
  workspaceKey, branch, repoSpecs) 后走 `RunnerWorkspace.ensureUserWorktree` 幂等物化
  （目录存在且分支匹配即复用接管，分支不匹配报占用）；目录绑定登记到
  `RunnerSessionRegistry`（key=`term-<id>` 伪 sessionId），且绑定**落盘**到 runner
  工作区元数据文件（解决 recentDirs 纯内存、runner 重启后目录失忆）；ack 带回实际
  路径与分支。
- **FR-06 创建/接管 API**：`TerminalWorkspaceService` + `TerminalWorkspaceController`：
  - `GET /api/projects/{pid}/terminal-workspaces`：列表（key/branch/所属需求/状态/
    锁占用者/last_active_at/created_by）。
  - `POST /api/projects/{pid}/terminal-workspaces`：新建 {requirementId?, workItemId?,
    baseBranch?}。预检：节点路由沿用会话规则（显式 agentNodeId→项目默认→平台默认
    →皆无 409，禁回落本机）；owner 校验复用 requireWorkspaceOwner（正则
    `[a-zA-Z0-9._-]+` + 保留名 main/sessions/builds/_chat/work/worktrees）；节点在线
    与协议 v26 门控；下发 terminal_ws_prepare（基线 ff 与目录冲突由 runner
    ensureUserWorktree 兜底，失败原样透传 409）；成功落库 state=OPEN。
  - `POST /api/projects/{pid}/terminal-workspaces/attach` {workspaceKey}：接管已存在
    且未收口的 worktree。预检：key 形如 `req-<rid>` 时复用
    `SessionManagerService.precheckRequirementOccupancy`（同需求有 active/SUSPENDED
    coding 会话→409）；terminal_workspaces 同 key 已有 OPEN 行→409；然后
    prepare + 落库。
- **FR-07 写锁**：`POST /api/terminal-workspaces/{id}/lock`（抢锁：已被持有→409 返回
  lock_owner；持锁更新 lock_session_id/lock_owner/lock_at）与
  `POST /api/terminal-workspaces/{id}/unlock`。心跳续期在 M2。
- **FR-08 反向占用预检**：`SessionManagerService.create` 目标需求/工作区若被
  terminal_workspaces 中活跃锁持有→409 并提示占用者。
- **FR-09 权限登记**：devmind-auth `ApiPermissionCatalog` 注册
  `project.terminal.action.manage`（列表/新建/接管/锁）与
  `project.terminal.action.finalize`（端点 M3 落地，本单元先注册码）；跨项目不可见
  沿用 CAP-62 数据权限过滤。

### M2 bash 终端通道与前端入口

- **FR-10 TerminalSessionService**：open（建 terminal_sessions 行 RUNNING + 抢锁 +
  下发 terminal_ws_prepare 保证 runner 目录绑定存在）、exec/cancel/complete 透传
  （REST → AgentNodeConnector.terminalExec/terminalComplete/terminalCancel → WS 帧 →
  runner TerminalHandler/PersistentShell：长驻 Git Bash、env/cwd 保持、Ctrl+C 整树杀、
  超时 60s、128KB 截尾）。sessionId 一律传 `term-<id>` 伪 id。REST：
  `POST /api/terminal-workspaces/{id}/terminal/open|exec|cancel|complete|heartbeat|close`。
  heartbeat（前端 30s 一次）续 terminal_sessions.last_heartbeat_at 与锁 lock_at；
  close 置 CLOSED 放锁；90s 无心跳或节点掉线→会话 SUSPENDED、放锁，
  terminal_workspaces 行与工作区目录保留（重开 open 即恢复）。
- **FR-11 写 Profile**：runner 侧 TerminalHandler 支持按会话携带 profile。终端工作区用
  write profile：默认放行 git:*（含 add/commit/checkout，push 只允许非 force）、
  重定向 > >>、cp/mv/mkdir/touch/tee/sed -i、常见构建与包管理命令。节点 ceiling 新增
  agent.properties 配置 `terminalWriteAllowlist`（CSV 前缀，缺省=write 默认集），
  write profile 不得超出 ceiling；runner 白名单强制校验红线不变（刻意不放服务端）。
- **FR-12 危险命令二次确认**：服务端新增 `DangerousCommandGuard`，模式表至少含
  rm -rf、git push --force / push -f、git reset --hard、git clean -fd、mkfs、fork 炸弹。
  exec 命中→HTTP 428 + {code:"NEED_CONFIRM", command}；前端确认后带 confirmed=true
  重发→放行并记 agent_audit_logs 确认审计（命令全文 + 确认人）。runner 侧另做不可
  绕过硬禁：rm -rf /、rm -rf ~、写平台物化路径（info/exclude 等，对齐 CAP-42 FR-10）。
- **FR-13 文件最小读写**：`GET /api/terminal-workspaces/{id}/files/tree` 与
  `GET .../files/file?path=` 复用 CAP-54 workspace_query 只读帧；`PUT .../files/file`
  （body {path, contentBase64, expectedSha?}）走新 WS 帧 `workspace_file_write`
  （协议 v26 内追加常量，不重复递增 CURRENT），runner 侧用 resolveConfined 同款
  startsWith+toRealPath 双重限定，越界拒绝，乐观锁 expectedSha 不匹配→409。
- **FR-14 命令历史**：`GET /api/terminal-workspaces/{id}/history` 查 agent_audit_logs
  （审计 detail 带 workspaceKey 维度），返回命令/执行人/时间/exitCode/耗时倒序分页。
- **FR-15 前端**：新 feature 目录 `src/features/terminal-workspace/` 自包含：
  - 抽取共享终端组件：TerminalTab.tsx 渲染内核（ansi.ts、字号调节、输入/补全）抽为
    `src/shared/terminal/TerminalView`，API base 参数化；SessionsBoard/ChatsBoard/
    ChatPanel 三处改为复用。
  - WorktreeListPage：聚合 terminal-workspaces 与现有会话型 workspaces 列表
    （key/分支/所属需求/状态/锁占用者/最近活跃，时间一律 fmtTime）；行操作：进入
    终端、新建工作区（可关联需求/工作项，可空）、接管已有 worktree；未提交改动数
    用现有 workspace_status 查询按需拉取。
  - TerminalWorkspacePage：左文件树+查看+编辑保存（简单文本编辑，不引 Monaco），
    右 TerminalView；危险命令 428 → Modal 展示命令全文→确认重发；open 被占用
    409 → 明示占用者。
  - 需求详情页加「工作区」卡片：聚合会话工作区与终端工作区状态，提供进入终端/
    新建终端工作区入口。
  - App.tsx 注册路由；布局遵循前端内容区布局约定。
- **FR-16 权限**：终端与文件端点挂 `project.terminal.action.manage`。

### M3 收口对齐与治理

- **FR-17 收口内核抽取**：doFinalizeWorkspace 中「下发 workspace_finalize 帧并处理
  结果」的内核抽为 `WorkspaceFinalizeCore`：入参 (nodeId, projectId, owner,
  workspaceKey, repoSpecs, operatorGitAuthor, discardChanges, deleteRemoteBranch)，
  内部走 AgentConnectionRegistry.finalizeWorkspace；原会话级
  `POST /api/sessions/{id}/finalize` 与需求级
  `POST /api/projects/{pid}/requirements/{rid}/workspace/finalize` 改调内核
  （行为不变，有回归测试）；runner 侧 RunnerWorkspace.finalize 零改动。
- **FR-18 终端工作区收口**：`POST /api/terminal-workspaces/{id}/finalize`
  {confirmCode, discardChanges, deleteRemoteBranch?}：本人或 admin；state=OPEN（否则
  409）；锁被他人活跃持有→409；confirmCode 必须等于 workspaceKey（文本短码确认，
  服务端硬校验——收口会 push 共享基线，属高危操作）。repoSpecs 取 repos_json 快照
  （不回查 project_repos 现值）；操作者署名复用 resolveSessionActor/CAP-24 GitAuthor
  解析链。脏且 !discardChanges→409 列前 3 个脏文件、工作区保留；discardChanges=true
  →reset --hard + clean -fd。成功：state=FINALIZED、finalize_detail（merge manifest
  JSON）与 finalized_at 落库、记 agent_audit_logs `WS_FINALIZE`（detail 带
  workspaceKey）；失败→409「收口失败（工作区已保留，可重试）」。
  `GET /api/terminal-workspaces/{id}/finalize-detail` 返回收口记录。
- **FR-19 释放**：`DELETE /api/terminal-workspaces/{id}`：复用 workspace_release 帧
  （runner 丢弃式删除，报告脏文件数与本地独有提交数）→ state=RELEASED；有未提交
  改动默认拒绝并提示，显式 discardChanges=true 才执行。
- **FR-20 定期提醒**：`TerminalWorkspaceReminderJob`（小时 tick，参照 CAP-114 配置
  模式）：扫描 state=OPEN 且 last_active_at 超阈值（默认 3 天，配置项）的终端工作区
  →向 created_by 发站内通知（新事件类型 TERMINAL_WORKSPACE_IDLE，文案含 key/未活跃
  天数/收口或释放入口链接）；只提醒不自动释放。
- **FR-21 CAP-114 清理纳入**：WorkspaceCleanupService 的 key 分类扩展识别 `term-`
  前缀，终端工作区归独立 scope，超龄释放走 DELETE 链路，清理结果进 last_report 与
  WORKSPACE_CLEANUP_DONE 通知。
- **FR-22 前端收口操作**：worktree 列表页与需求详情页工作区卡片补「收口/释放/查看
  收口记录」：收口 Modal 要求输入 workspaceKey 短码 + discardChanges 勾选 + 明示
  「将 ff-merge 并 push 到共享基线 <baseBranch>」；FinalizeDetailModal 复用现有组件。
  按钮权限码 `project.terminal.action.finalize`。
- **FR-23 审计串联**：以 workspaceKey 为线索串联 绑定(terminal_workspaces)→会话
  (terminal_sessions)→命令(agent_audit_logs)→收口(finalize_detail)，finalize-detail
  接口聚合返回关键节点。

## 3. 否决方案

- **复用 sessions 表加类型字段**：coding 会话状态机（RUNNING/WAITING_INPUT/…）与
  终端工作区状态机（OPEN/FINALIZED/RELEASED）语义不同，混表会让会话查询/清理/
  收口全部分支化；独立表 + 复用 runner 物化语义成本最低。
- **服务端做命令白名单**：runner 侧已有强制白名单（FR-11 红线），服务端再叠一套会
  双写漂移；服务端只做「危险命令二次确认」这一层人机交互闸门，强制校验留在 runner。
- **轻量入口降低收口确认等级**：否决——收口 push 共享基线是高危操作，文本短码
  （输入 workspaceKey）确认不降级。
- **新建独立协议通道（终端专用 WS）**：terminal exec 帧族（v16/17）已覆盖
  exec/cancel/complete，新造通道是重复建设；只需伪 sessionId 让 runner 认得绑定。

## 4. 架构与模块归属

```
devmind-common
  AgentProtocol —— CURRENT 25→26；terminal_ws_prepare/ack、workspace_file_write 常量
  AgentNodeConnector SPI —— +terminalWorkspacePrepare、（M2）workspaceFileWrite
  RunnerWorkspace —— 分支前缀白名单放行 terminal/

devmind-project
  WorktreeManager —— +workspaceKeyForTerminal()/branchForTerminal()

devmind-agent
  AgentConnectionRegistry —— terminalWorkspacePrepare（v26 门控 + waiter + 审计）

devmind-agent-runner
  terminal_ws_prepare 处理器 —— ensureUserWorktree 幂等物化 + RunnerSessionRegistry
    绑定（落盘元数据）；（M2）workspace_file_write 处理器、TerminalHandler write profile、
    危险命令硬禁

devmind-session
  model/TerminalWorkspaceEntity、TerminalSessionEntity（+Repository）
  service/TerminalWorkspaceService（M1 创建/接管/锁；M3 finalize/release）
  service/TerminalSessionService（M2 open/exec/cancel/complete/heartbeat/close）
  service/DangerousCommandGuard（M2）
  service/WorkspaceFinalizeCore（M3，自 SessionManagerService 抽取）
  service/TerminalWorkspaceReminderJob（M3）
  service/SessionManagerService —— create 加反向占用预检；finalize 改调内核
  controller/TerminalWorkspaceController

devmind-auth
  ApiPermissionCatalog —— project.terminal.action.manage / .finalize

frontend
  src/shared/terminal/TerminalView（自 TerminalTab 抽取，三处旧入口复用）
  src/features/terminal-workspace/（WorktreeListPage、TerminalWorkspacePage、api/types）
  features/requirements/pages/RequirementDetailPage.tsx —— 「工作区」卡片
  App.tsx —— 路由注册
```

## 5. 数据模型

`terminal_workspaces`（`ddl-auto=update` 自动演进）：

| 列 | 类型 | 说明 |
|---|---|---|
| id | varchar(36) PK | |
| project_id | varchar | 归属项目 |
| requirement_id / work_item_id | varchar 可空 | 关联需求/工作项 |
| workspace_key | varchar | term-<短id>；接管时=被接管 key（如 req-<rid>） |
| branch | varchar | terminal/<短id>；接管时=被接管分支 |
| owner | varchar | worktree 属主（节点上目录隔离维度） |
| agent_node_id | varchar | 物化所在节点 |
| base_branch | varchar | 基线分支 |
| repos_json | LONGVARCHAR | 创建时 project_repos 快照（字段对齐 SessionRepoEntity） |
| state | varchar 默认 'OPEN' | OPEN / FINALIZED / RELEASED |
| lock_session_id / lock_owner / lock_at | varchar / varchar / timestamp 可空 | 写锁 |
| finalize_detail | LONGVARCHAR 可空 | 收口 merge manifest JSON |
| finalized_at | timestamp 可空 | |
| last_active_at | timestamp | 最近活跃（提醒/清理口径） |
| created_by / created_at | varchar / timestamp | |

`terminal_sessions`：

| 列 | 类型 | 说明 |
|---|---|---|
| id | varchar(36) PK | |
| workspace_id | varchar | FK → terminal_workspaces |
| user_id | varchar | |
| state | varchar 默认 'RUNNING' | RUNNING / SUSPENDED / CLOSED |
| started_at / last_heartbeat_at / ended_at | timestamp | |

红线遵守：@ColumnDefault 字符串默认值带引号；@Lob 带
@JdbcTypeCode(SqlTypes.LONGVARCHAR) + @Column(length=16_777_216)；列名避开 H2
保留字；请求 DTO 布尔字段用 Boolean 包装；时间序列化走全局 JacksonConfig。

## 6. API 概要

M1：
- `GET /api/projects/{pid}/terminal-workspaces`（列表）
- `POST /api/projects/{pid}/terminal-workspaces`（新建 {requirementId?, workItemId?, baseBranch?}）
- `POST /api/projects/{pid}/terminal-workspaces/attach`（接管 {workspaceKey}）
- `POST /api/terminal-workspaces/{id}/lock` / `POST /api/terminal-workspaces/{id}/unlock`

M2：
- `POST /api/terminal-workspaces/{id}/terminal/open|exec|cancel|complete|heartbeat|close`
- `GET /api/terminal-workspaces/{id}/files/tree`、`GET .../files/file?path=`、
  `PUT .../files/file` {path, contentBase64, expectedSha?}
- `GET /api/terminal-workspaces/{id}/history`

M3：
- `POST /api/terminal-workspaces/{id}/finalize` {confirmCode, discardChanges, deleteRemoteBranch?}
- `GET /api/terminal-workspaces/{id}/finalize-detail`
- `DELETE /api/terminal-workspaces/{id}` {?discardChanges=true}

WS 帧（协议 v26）：`terminal_ws_prepare` + ack、`workspace_file_write` + ack。

## 7. 安全

- 权限：列表/新建/接管/锁/终端/文件端点挂 `project.terminal.action.manage`；收口/释放
  挂 `project.terminal.action.finalize`；跨项目不可见沿用 CAP-62 数据权限过滤。
- 并发：同一 worktree 同时只允许一个终端会话持有写锁；被占用 409 返回占用者。
  反向占用预检：持锁工作区的需求上创建 coding 会话→409。
- 命令安全：runner 白名单强制校验（FR-11）+ 服务端危险命令二次确认（FR-12，428 +
  confirmed 重发 + 审计）+ runner 硬禁（rm -rf /、rm -rf ~、平台物化路径）。
- 文件写：startsWith + toRealPath 双重限定，越界拒绝；乐观锁 expectedSha。
- 收口高危：confirmCode 服务端硬校验 = workspaceKey，前端不可绕过。
- 审计：prepare/命令/确认/收口/释放均落 agent_audit_logs，detail 带 workspaceKey 维度。

## 8. 里程碑

- **M1 终端工作区登记与管理**：FR-01~09（实体/状态机/写锁/创建/接管 API +
  terminal_ws_prepare 帧 + 权限登记）。
- **M2 bash 终端通道与前端入口**：FR-10~16（命令透传、写 Profile、危险命令二次
  确认、文件最小读写、命令历史、worktree 列表页与终端面板）。
- **M3 收口对齐与治理**：FR-17~23（finalize 内核抽取复用、文本短码确认、释放链路、
  定期提醒与清理纳入、审计串联）。

## 9. 验收标准

- M1：`mvn -q test` 全绿（实体 schema 测试参照 LobColumnTypeTest 模式；锁抢占/释放、
  双向占用预检 JUnit）。端到端（真实 git 临时仓）：新建 terminal 工作区→runner 磁盘
  出现 worktrees/term-<id>/ 且分支 terminal/<id>→列表可见→重复 attach 同 key 409→
  加锁后他人加锁 409 且返回占用者→unlock 后可锁→持锁时对同需求创建 coding 会话
  409。
- M2：`mvn -q test` 全绿（DangerousCommandGuard 模式匹配、心跳续锁/90s 超时
  SUSPENDED、文件写越界拒绝与乐观锁 409 JUnit）；`npx tsc -b` 通过。端到端：新建→
  打开终端→echo 重定向写文件、git add/commit 成功（写 Profile 生效）→文件树可见
  改动、编辑保存成功→git push --force 被 428 拦截、确认后放行且审计可查→history
  返回完整命令流水→kill runner 后会话 SUSPENDED、重开 open 恢复且 cwd 正确→另一
  会话 open 同工作区 409 显示占用者→旧三入口终端 Tab 回归正常。
- M3：`mvn -q test` 全绿（WorkspaceFinalizeCore 两入口等价性参数化测试、短码校验、
  脏工作区 409/丢弃重试、释放守卫、ReminderJob 扫描与通知断言）；`npx tsc -b` 通过。
  端到端：commit 后错误短码收口被拒→正确短码收口成功（基线 ff 推进且 push、
  finalize_detail 有合并清单、状态 FINALIZED）→二次收口 409；脏工作区收口 409 列
  脏文件→discardChanges 重试成功；DELETE 释放带脏被拒→discard 后成功、目录删除、
  状态 RELEASED；会话级/需求级旧收口入口回归通过。

## 10. 风险与开放问题

| # | 项 | 口径 |
|---|---|---|
| R1 | runner 重启后 term- 绑定失忆 | M1 起绑定落盘 runner 工作区元数据文件（同时修复既有 recentDirs 纯内存问题） |
| R2 | 终端会话 90s 无心跳挂起但 shell 仍在 runner 上 | 接受：工作区保留是特性（可恢复）；runner 侧 GC 口径另议 |
| R3 | 写 Profile 放权 git commit 等命令被滥用 | 节点 ceiling（terminalWriteAllowlist）封顶 + 全量命令审计；危险命令另过 428 闸门 |
| R4 | 接管 req- 键 worktree 与进行中 coding 会话并发 | precheckRequirementOccupancy 复用 + 反向占用预检双保险 |
| O1 | 未提交改动数在列表页展示 | M2 用 workspace_status 按需拉取 |
| O2 | 终端工作区超龄自动释放 | M3 只提醒不自动释放；CAP-114 清理纳入后由策略驱动 |
| O3 | 多用户同时查看同一终端（只读围观） | 展望，写锁只挡写 |
