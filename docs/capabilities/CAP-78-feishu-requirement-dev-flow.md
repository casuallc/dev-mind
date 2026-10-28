# CAP-78 飞书对话式需求开发全流程（AI 规划 · 会话生命周期 · 收口）

> 能力 ID：CAP-78 ｜ 分类：组装层 ｜ 状态：设计定稿 ｜ 日期：2026-10-20
>
> 决策记录：
> 1. **网页端已有的流程动作，飞书侧一律复用既有 Service，不新造语义**——
>    「开启 AI 规划」就是 `RequirementFlowService.startPlan`（CAP-52），收口就是
>    `SessionManagerService.finalizeRequirementWorkspace`（CAP-51）。本能力只做
>    「工具化 + 指令化 + 回推」，状态机、互斥、权限全部沿用服务端现有实现。
> 2. **对话式（copilot 工具）为主、斜杠指令为快速路径**——与 CAP-75/76 分层一致：
>    自然语言走 `PlatformTool`（写必确认），高频动作补 `/plan /finalize` 等指令。
> 3. **需求级收口定为 DANGEROUS**——它合并 feature 分支进基线并 push，改动共享远端历史，
>    等同生产变更，必须文本短码确认（卡片不给执行按钮），预览必须展示基线分支与脏工作区警告。

## 1. 目的

需求开发主流程（CAP-52 定稿）目前是「网页点按钮」：

```text
DRAFT ─开启AI规划→ ANALYZING ─规划会话产出(分析/方案/WI清单)─自动起开发会话→ IN_PROGRESS
      ─开发会话 DONE→ ACCEPTANCE ─人工验收→ DONE
```

每个环节都要开网页：需求详情页点「开启 AI 规划 / 重新开发」、WI 行内点「起会话」、
会话列表里 finish/resume、「收口合并到基线」。人不在电脑前（只有飞书）时整条链路停摆。

本能力把**全流程搬到飞书对话**，目标形态：

```text
用户：给 REQ-3 开启 AI 规划
机器人：待确认 [A7K2]：将在项目 X 对 REQ-3 起规划会话（分析+方案+WI 拆分）。回复「确认 A7K2」执行。
用户：确认 A7K2
机器人：已启动规划会话（sid xxx），完成后会自动登记产出并起开发会话，进展会推送到这里。
…（规划会话 DONE → 产出登记 → 开发会话自动启动，终态推送）…
用户：REQ-3 的会话跑到哪了
机器人：（list_sessions + get_session_events）开发会话 RUNNING，最近动作：……
用户：先把会话结束了 / 继续刚才的会话
机器人：（finish_session / resume_session，确认后执行）
用户：REQ-3 收口
机器人：待确认（DANGEROUS，文本短码）：将把 feature/req-3 合并进 master 并 push，工作区无未提交改动…
用户：确认 B2M9
机器人：收口完成，基线已推送。
```

### 现状缺口（本能力要补的部分）

| 流程动作 | 网页端点（既有） | 飞书现状 |
|---|---|---|
| 开启/重新 AI 规划 | `POST …/flow/plan` | ❌ 无 |
| 重新开发（需求级 dev 会话） | `POST …/flow/dev` | ⚠️ `/dev` 起的是普通会话，非 `[flow:dev]` |
| WI 起会话 | `POST …/work-items/{wid}/start-session` | ❌ 无 |
| 优雅结束会话 | `POST /sessions/{id}/finish` | ❌ 仅 `/stop`（kill） |
| 挂起/继续会话 | `POST /sessions/{id}/suspend|resume` | ❌ 无 |
| 查看会话状态 | `GET /sessions?requirementId=` / `/{id}/events` | ✅ 只读工具已有（list/get/events） |
| 需求级收口 | `POST …/requirements/{rid}/workspace/finalize` | ❌ 无 |
| 会话级收口 | `POST /sessions/{id}/finalize` | ❌ 无 |
| 流程事件回推 | CAP-06 通知（flow.plan.done 等 P1） | ⚠️ 依赖通知通道配置，需钉入口径 |

## 2. 功能需求

### 2.1 copilot 工具（主路径）

工具随能力模块交付（CAP-76 P10/§4.2）：flow 域工具归 `devmind-flow`，会话域工具归
`devmind-session`，零业务模块新依赖——两模块本就持有对应 Service。命名沿用
`<domain>_<action>` 约定；寻址复用 M6 口径（`REQ-<seq>`/`WI-<seq>`/内部 id/纯数字 seq，
`RequirementService.resolveRef` / `WorkItemService.resolveRef`）。

**FR-01 `start_requirement_plan`（WRITE，DEVELOPER）——开启 AI 规划**

- 参数：`projectId?`（缺省会话绑定项目）、`requirementId`（REQ 编号/id/seq）。
- 语义 = `RequirementFlowService.startPlan`：起一个直挂 requirementId 的 `[flow:plan]`
  会话（taskSpec 由 `FlowOutputContract.planSpec()` 生成，一次产出
  analysis.md/design.md/wi-plan.json 且不改代码）。
- 直接复用现有前置校验并映射错误码：终态需求 → `CONFLICT`；同需求有进行中会话
  （`requireNoActiveSession`）→ `CONFLICT` 并在文案里带进行中会话 id；
  无可用执行节点（路由链 409）→ `CONFLICT`。
- 已有规划产出时再调 = 重新规划（与网页「重新规划」同语义：整段重跑、产出落新版本、
  已有 WI 不删）。`preview()` 必须区分首规划/重新规划，重新规划预览注明
  「已有规划产出将整体重跑，已有工作单元保留」。
- 返回：会话 id、状态、平台深链（`/sessions?sid=`），并提示「规划完成后会自动登记
  分析/方案/WI 并起开发会话」。

**FR-02 `start_requirement_dev`（WRITE，DEVELOPER）——重新开发**

- 参数同 FR-01。语义 = `RequirementFlowService.startDev`：起 `[flow:dev]` 需求级
  开发会话（整份 TODO WI 清单一次做完，全部 TODO WI 置 IN_PROGRESS）。
- 需求无 WI → `CONFLICT` 并提示「先开启 AI 规划生成工作单元」；其余校验同 FR-01。

**FR-03 `start_work_item_session`（WRITE，DEVELOPER）——WI 起会话**

- 参数：`projectId?`、`workItemId`（WI 编号/id）。语义 =
  `RequirementFlowService.startWorkItemSession`（人工逃生通道）：WI.spec 作 taskSpec
  注入（缺 spec → `INVALID_ARGUMENTS`），TODO→IN_PROGRESS。

**FR-04 会话生命周期工具（WRITE，DEVELOPER）**

| 工具 | 参数 | 语义（既有 Service） |
|---|---|---|
| `finish_session` | `sessionId` | `SessionManagerService.finish`：关 stdin 优雅退出 → DONE/FAILED（区别 `/stop`/kill 的强杀） |
| `suspend_session` | `sessionId` | `suspend`：杀进程保留工作区，可 resume |
| `resume_session` | `sessionId` | `resume`：SUSPENDED/DONE/FAILED/TERMINATED → RUNNING（claude --resume 续历史）；收口后 resume 会把需求/会话 workspaceState 重新置 OPEN，预览需注明 |
| `stop_session` | 既有（kill → TERMINATED），不动 |

- 归属与状态机校验全部由 `SessionManagerService` 既有逻辑把关（非创建者非 admin
  resume/finalize 403 → `PERMISSION_DENIED`；会话仍 active 时收口 409 → `CONFLICT`），
  工具不重复实现，只做异常→`ToolResult` 映射。
- `sessionId` 参数同时接受会话内部 id 与「需求编号」——传 `REQ-<seq>` 时解析为该需求
  **最近一个活跃会话**（无活跃则最近一个非终态；皆无 → `NOT_FOUND`）。自然语言场景下
  用户记的是需求号不是 sid，这是对话式可用性的关键；解析结果必须写进预览
  （"目标会话 sid xxx（REQ-3 的开发会话，RUNNING）"），让用户确认前看到打到谁。

**FR-05 收口工具**

| 工具 | 风险 | 参数 | 语义 |
|---|---|---|---|
| `finalize_requirement_workspace` | **DANGEROUS** | `requirementId`、`discardChanges?`（默认 false） | `finalizeRequirementWorkspace`（CAP-51）：取该需求最近 workspace OPEN 会话，`feature/req-<id>` 合并进基线（merge --ff）+ push + **保留工作树**；需求与同需求全部 OPEN 会话行置 FINALIZED |
| `finalize_session_workspace` | WRITE | `sessionId`（接受 REQ 编号，同 FR-04 解析）、`discardChanges?`、`deleteRemoteBranch?`（默认 true） | `finalizeWorkspace`（CAP-42）：会话级收口，合并+push+删 worktree |

- DANGEROUS 理由：push 基线分支改动共享远端历史。按 CAP-76 FR-11：卡片不渲染确认
  按钮，必须文本短码确认；预览完整展示：需求编号与标题、基线分支、涉及仓库、
  `deleteRemoteBranch`、以及**脏工作区预警**——预览时尽力调工作区状态（会话级
  `workspace/status` 只读链）列出未提交文件数；查不到（节点离线/协议低）也要在预览
  注明「工作区状态未知，若存在未提交改动收口将失败并保留工作区」。
- `discardChanges=true` 必须在预览里红字单列「将丢弃 N 个未提交改动」，不得藏在参数里。
- 收口失败（未提交改动/合并冲突/节点离线）透传脱敏错误（Service 已处理：
  「收口失败（工作区已保留，可处理后重试）」），回执给出下一步建议
  （resume 会话提交后重试，或 discardChanges 重试）。

**FR-06 只读不新增**：`list_sessions`（按 requirementId 过滤已有）、`get_session`、
`get_session_events`、`get_requirement`（WI/设计摘要已有）已覆盖「查看会话状态」。
本期仅约定：模型回答「REQ-x 跑到哪了」类问题时先 `list_sessions(requirementId)` 再按需
`get_session_events(tail)`；工具描述里补这一句引导，不改实现。

**FR-07 写操作闸门全部沿用 CAP-76**：WRITE/DANGEROUS 走确认单（PENDING→确认执行），
个人绑定门禁（FR-19）、确认时重校验（FR-15，含对象反查 projectId + 状态复核）、
限流互斥不变。起会话=产生费用，与既有 `start_session` 同口径 WRITE。

### 2.2 斜杠指令快速路径（devmind-bot）

**FR-08 新增/扩展指令**（全部走敏感指令门禁，未 `/bindme` 拒绝）：

| 指令 | 行为 |
|---|---|
| `/plan <REQ编号>` | = FR-01。直接执行（与 `/dev` 既有口径一致：起会话类指令不二次确认）；**已有规划产出时**走 `PendingConfirmStore` 二次确认（"REQ-3 已有规划产出，重新规划将整体重跑（已有 WI 保留），回复「确认」继续"） |
| `/dev <WI编号>` | 扩展既有 `/dev`：参数匹配 `WI-<seq>` 形态时走 FR-03；`REQ-<seq>` 维持现状 |
| `/finish <sid\|REQ编号>` | = FR-04 finish；REQ 编号解析口径同 FR-04 |
| `/resume <sid\|REQ编号>` | = FR-04 resume |
| `/finalize <REQ编号> [discard]` | = FR-05 需求级收口；**始终二次确认**（PendingConfirmStore），确认文本含基线分支与 discard 警示；`discard` 关键字 = discardChanges=true |

- 回执统一带：动作结果 + 会话 id/需求编号 + 平台深链（`devmind.bot.platform-base-url`
  拼 `/sessions?sid=` / 需求详情），失败走既有 fail-visible 文案。
- 解析歧义一律拒绝并提示（如 `/finish` 传 REQ 编号但该需求有多个候选会话时取最近
  活跃且回执注明；无候选 → 报「没有可操作的会话」）。
- `/help` 文案同步更新。

**FR-09 指令与工具同源**：斜杠指令与 copilot 工具调的是**同一批 Service 方法**
（`RequirementFlowService` / `SessionManagerService`），差异只在入口与确认交互；
禁止 bot 模块为实现指令新造流程语义。

### 2.3 进度与事件回推

**FR-10 起会话即订阅推送**：FR-01/02/03 与 `/plan` 成功起会话后，按
`BotSessionWatchService` 既有机制登记该 chat 关注此会话（与 `/dev` `/session` 现状一致），
会话终态（DONE/FAILED）主动推送到原聊天，推送文案带需求编号与后续动作建议
（规划完成 → 「产出已登记，开发会话已自动启动」/ 部分产出 → 「缺 design.md，可
重新规划或到网页查看」）。

**FR-11 流程事件口径**：`flow.plan.done` / `flow.plan.partial` / `flow.dev.done`
（P1 通知，CAP-52 既有）经 CAP-06 通知中心的飞书通道可达；本能力不新增事件，
只在文档与回执文案里钉住这条链路（部署侧通道未配置时不受影响——会话终态推送
FR-10 不依赖通知中心）。

## 3. 非目标（本期不做）

- **验收翻转**：需求 ACCEPTANCE→DONE 走既有 `update_requirement_status` 工具
  （DANGEROUS，CAP-76 M2 已交付），本能力不重复。
- **构建/部署/发版**：CAP-76 v1 明确不交付部署/发版写工具，本能力维持该边界。
- **会话内对话**：`send_session_input`（DANGEROUS）已存在；「在飞书里实时看会话流」
  不做（用 `get_session_events` 轮询式查看 + 终态推送足够）。
- **前端新页面**：零前端改动（工具目录/审计页自动随注册表更新）。
- **多需求批量操作**：跨需求批量规划/收口不提供（CAP-76 FR-22 跨项目批量禁令同口径）。

## 4. 插件化接口

- 复用 `com.devmind.common.agent.tool.PlatformTool` SPI：devmind-flow 新增
  `FlowTools`（3 个工具）、devmind-session 新增 `SessionLifecycleTools`（5 个工具，
  `stop_session` 既有实现所在类内补充），均 `@Component` 注册进 `ToolRegistry`。
- 工具实现统一结构（CAP-76 §6.3）：参数校验 → 反查对象 projectId →
  `ToolAccessPolicy`（缺席=仅 RBAC）→ 合成 SecurityContext 调 Service → 映射 ToolResult。
- 契约快照 `docs/capabilities/tools/cap76-tools.yaml` 用 `tests/legacy/api/cap76_tools_contract.py`
  重新生成，8 个新 operation 在 diff 中可见。

## 5. 依赖关系

- **CAP-76**（工具 SPI/确认单/权限链/审计）：全部新工具走其既有机制，零 copilot 模块改动
  （工具随能力模块交付正是 CAP-76 设计点）。
- **CAP-75/77**（bot 底座/指令路由/PendingConfirmStore/敏感指令门禁/会话终态推送）：
  仅 devmind-bot 增改路由与帮助文案。
- **CAP-52**（startPlan/startDev/startWorkItemSession 与 `[flow:plan]`/`[flow:dev]` 契约）、
  **CAP-42/51**（finalizeWorkspace/finalizeRequirementWorkspace 与脏检查/保留工作树口径）、
  **CAP-34**（节点路由 409 语义）、**CAP-37**（产出回传——规划回执文案的依据）：
  只调用，不修改。
- 零新表、零新 REST 端点、零 runner 协议变更、零前端改动。

## 6. 验收标准

### 单元测试

1. 工具参数校验：REQ/WI 编号各形态解析、缺项目上下文 → `PROJECT_REQUIRED`、
   未知字段拒绝。
2. 错误映射：终态需求规划 → CONFLICT；有进行中会话 → CONFLICT 带 sid；
   无 WI 的 startDev → CONFLICT；缺 spec 的 WI 起会话 → INVALID_ARGUMENTS。
3. 风险闸门：8 个工具的风险级别/最低角色登记正确；`finalize_requirement_workspace`
   为 DANGEROUS，卡片路径确认被拒（CAP-76 FR-15 既有机制回归）。
4. 预览内容：重新规划注明「整体重跑」；discardChanges 红字单列；REQ 编号解析出的
   目标会话 sid 写进预览。
5. bot 指令：`/plan` 首规划直执行、再规划二次确认；`/finalize` 必二次确认且
   discard 进入确认文案；`/dev WI-2` 路由到 WI 起会话。

### E2E（`tests/legacy/api/cap78_feishu_flow_verify.py`，产物写 tmp/）

- 隔离 app（e2e profile）+ fake bot 注入消息 + fake 模型（工具调用）+ fake runner
  （executor=fake 或 mock WS 节点）。
- 场景：
  1. 对话式全链路：发「给 REQ-1 开启 AI 规划」→ 确认单 → 文本确认 → `[flow:plan]`
     会话拉起（断言 sessions 行 requirementId 正确、taskSpec 首行 `[flow:plan]`）；
  2. 规划会话 fake 完成并回传三份产出 → 断言分析/方案文档、WI 固化、开发会话自动启动
     （复用 CAP-52 链路，本能力只验证飞书入口不破坏它）；
  3. 「结束 REQ-1 的会话」→ finish → DONE；「继续」→ resume → RUNNING；
  4. 「收口 REQ-1」→ DANGEROUS 确认单无卡片按钮 → 文本短码确认 → 需求
     workspaceState=FINALIZED（fake runner ack 成功）；
  5. 斜杠：`/plan REQ-1`（有产出 → 裸「确认」二次确认后重跑）、`/finalize REQ-1`
     二次确认链；
  6. 门禁：未绑定身份写工具被拒；VIEWER 不可见写工具；他人不能确认。
- 跑完清理自起进程（见 tests/README.md）。

### 真机检查（飞书）

- [ ] 群聊自然语言完成「规划 → 状态查看 → 结束 → 继续 → 收口」全链路；
- [ ] DANGEROUS 收口卡无确认按钮、文本短码可执行；
- [ ] 会话终态主动推送到原群；
- [ ] 未绑定用户发起规划被拒并给出 `/bindme` 引导。

## 7. 里程碑

### M1 copilot 工具（对话式主路径）

- [ ] devmind-flow 3 工具（plan/dev/WI 起会话）+ devmind-session 5 工具
      （finish/suspend/resume/两个 finalize）
- [ ] REQ 编号→最近会话解析（FR-04）与预览文案
- [ ] 单测 + E2E 场景 1/2/3/4/6 + 契约快照更新

### M2 斜杠指令与回推

- [ ] `/plan` `/dev WI` `/finish` `/resume` `/finalize` + help 文案 + 二次确认
- [ ] 起会话回执带深链与 BotSessionWatch 登记核对（FR-10）
- [ ] E2E 场景 5

### M3 真机验收

- [ ] 飞书真机全链路走查（§6 真机检查四项）

## 8. 风险与开放问题

| # | 风险 | 处理 |
|---|---|---|
| R1 | 模型把「结束」歧义为 kill 或 finish | 工具描述写明 finish=优雅结束（首选）、stop=强杀；`/stop` 维持二次确认现状 |
| R2 | REQ 编号解析错会话（同需求多条会话） | 取最近活跃并在预览/回执点名 sid 与状态，用户确认前可见；解析不到就报错不猜 |
| R3 | 收口时工作区有未提交改动 | runner 侧既有脏检查兜底（失败保留工作区 + 脏文件清单）；预览尽力预警；回执给 resume/discard 两条出路 |
| R4 | 规划/dev 会话长时间运行，用户以为没响应 | 起会话回执即说明「完成会自动推送」；FR-10 终态推送；期间可用只读工具查进度 |
| R5 | resume 已收口需求会重开工作区（CAP-51 既有语义） | resume 预览注明「该需求已收口，继续会话将重新打开工作区」 |
| R6 | 通知通道未配置时流程事件不可达 | 终态推送（FR-10）不依赖通知中心；文档钉住差异，不静默 |

## 9. 被否方案

| 方案 | 否决原因 |
|---|---|
| 只做斜杠指令，不接 copilot 工具 | 违背「对话式执行」目标；指令要记语法，CAP-76 的工具确认链已是现成安全框架 |
| 在 devmind-copilot 里内置流程工具实现 | 违反 CAP-76「工具随能力模块交付、copilot 零业务依赖」；工具归 flow/session 模块 |
| bot 指令直接调 Controller HTTP | 绕权限合成与事务边界；进程内调 Service（与 CAP-75 既有指令一致） |
| 需求级收口定为 WRITE 卡片可确认 | push 基线等同生产变更，误点代价高；DANGEROUS + 文本短码与 CAP-76 FR-11 同口径 |
| 飞书内嵌实时会话流（WS 转发逐字输出） | 实现重、信息密度低；终态推送 + get_session_events 按需查看已覆盖「看状态」诉求 |
