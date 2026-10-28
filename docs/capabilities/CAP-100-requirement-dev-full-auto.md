# CAP-100 需求开发全自动链（规划→开发→WI 自动完成）

> 能力 ID：CAP-100 ｜ 分类：流程层 ｜ 状态：已实现（2026-10-28，87d9dde2/51ceb4ca/91f68544）
> 演进 CAP-52 FR-06（开发会话成功后 WI 不自动 DONE）与 CAP-17（自动构建幂等粒度 WI 级→需求级）。
>
> 决策记录：
> - 2026-10-28 定稿：规划链路方案登记即 CONFIRMED（不再是人工门禁，恢复 WI→designId 追溯边）；
>   开发会话回填 WI.sessionId（WI 行可见归属会话）；「开发会话成功自动完成 WI」做项目级开关
>   （默认关），开启后自动构建幂等粒度提升为需求级（一个需求一次自动构建，不再逐 WI 掀起 N 次）。

## 1. 目的

CAP-52 把「需求 → 代码」压成一次点击（规划会话 → 自动起开发会话），但收尾留了三处断点，
全自动链走不通：

| 断点 | 现状 | 后果 |
|------|------|------|
| 方案确认 | 规划产出 Design 恒为 DRAFT，无人确认；`applySplitPlan` 只认 CONFIRMED 方案挂 designId | WI→方案追溯边恒断（designId=null）；「确认」按钮成纯摆设——开发都跑完了再确认只是补账 |
| 会话归属 | `[flow:dev]` 只挂 requirementId，WI 行「会话」列按 workItemId 匹配查不到 | 哪个会话做的这批 WI 无从直接跳转 |
| WI 完成 | 开发会话成功后 WI 停 IN_PROGRESS，等人点「全部完成」 | 全自动项目仍要人工收尾一次 |

本能力把三步接通：**一次点击 → 规划（方案即采纳）→ 开发 → WI 自动完成 →（按项目开关）需求级一次构建**。
人工闸门保留在需求级（ACCEPTANCE → 人工验收 → DONE）与收口（合并基线人工），WI 级不再设卡。

## 2. 功能需求

### FR-01 规划产出方案登记即 CONFIRMED

- `handlePlanOutput` 登记方案文档后**立即置 CONFIRMED**（规划链路产出的方案语义 = 已采纳，
  开发会话即以它为依据执行；确认失败仅记日志不阻塞后续固化）。
- 时序保证：方案确认先于 `applySplitPlan`，使其既有的「CONFIRMED 方案挂 designId」逻辑
  自动恢复 WI→方案追溯边（无需改 `applySplitPlan`）。
- 存量老路径不动：DESIGN 型 WI 会话（`handleDesignOutput`）登记的 Design 仍为 DRAFT
  （CAP-52 §7 存量路径保留口径）；人工新建/手工推送（CAP-39）的方案状态流转不变。

### FR-02 工作单元回填归属会话

- `work_items` 加 `session_id` 列（可空，`ddl-auto=update` 自动演进）。
- 回填点：
  - `launchDev`（`[flow:dev]` 需求级开发会话）：会话创建成功后，本次置 IN_PROGRESS 的全部
    WI 回填 sessionId；「重新开发」覆盖为最新会话。
  - `startWorkItemSession`（WI 行内起会话）：回填该 WI。
- `WorkItemView` 加 `sessionId` 字段。
- 前端 WI 表「会话」列：优先按 `sessionId` 反查 overview.sessions，未命中回落既有
  workItemId 匹配（兼容存量数据）。

### FR-03 项目级开关：开发会话成功自动完成 WI

- `projects` 加 `auto_done_wi_on_dev`（Boolean 可空，**不加 @ColumnDefault**——MySQL bit
  事故先例；存量行 null 由 getter 兜底 false = 默认关）。读写走 `ProjectRequest`/`ProjectView`
  （创建缺省 false；更新不传 = 保持不变），与 CAP-72 两开关同模式。
- 开关开时，`handleDevOutput`（`[flow:dev]` 会话成功）在现有逻辑后追加：把该需求
  **IN_PROGRESS** 的 WI 逐条置 DONE（走 `updateStatus`：rollup、事件、CAP-17 触发语义不变）。
  TODO/BLOCKED 不动（开发会话未覆盖的 WI 不替人宣布完成）；需求已 DONE/CANCELLED 不动。
- `flow.dev.done` 通知文案补充「N 个工作单元已自动完成」；开关关时文案与行为完全不变。
- 项目表单（ProjectFormDrawer）加开关「开发会话成功后自动完成工作单元」，缺省关。

### FR-04 自动构建幂等粒度提升为需求级

- 背景：FR-03 批量 DONE 逐条发 `workitem.status.changed`，per-WI 幂等下会掀起 N 次构建。
  而 CAP-52 模型下一个需求 = 一条分支 = 一次开发，构建走主库 HEAD（CAP-17 分支假设），
  逐 WI 构建的区分度已不存在。
- 规则：`tryTriggerBuild` 幂等检查从「该 WI 已有构建记录」提升为「**该 WI 所属需求**已有
  构建记录（任意状态）→ 跳过」；`retryPendingBuilds` 补扫候选同口径排除。
- 影响面（写进升级公告）：仅影响开了 `auto_build_on_wi_done` 的项目（默认关）；同需求第二个
  WI DONE 不再自动构建，需要时人工触发构建（构建单人工入口不变）。自动部署 TEST/通知载体
  仍以触发构建的代表 WI 为准，不动。

## 3. 否决方案

- **批量 DONE 走静默通道（不发逐 WI 事件）+ 显式一次构建**：要补扫不重复触发就得在数据上区分
  「被批量覆盖的 WI」与「待重试的 WI」，要么加标记列要么改补扫口径，复杂度高于直接提升幂等粒度；
  且静默通道绕开 rollup/事件会让审计链断一截。否决，选 FR-04 的需求级幂等。
- **WI 级保留人工确认、只做 ①②**：WI 粒度已被 CAP-52 压到 1~3 条且验收闸门在需求级，
  WI 级人工确认只剩一次批量点击的价值；但全自动有实时风险，故做成项目级开关（FR-03）而非恒开。
- **新增 ADOPTED 状态区分「AI 采纳」与「人工确认」**：CONFIRMED 现状无任何门禁消费方，
  加一个无人判读的状态是纯仪式；如未来需要区分来源，Design.createdBy/产物 producer 已可溯。

## 4. 架构与模块归属

| 改动 | 模块 |
|------|------|
| FR-01 方案即确认、FR-02 launchDev 回填、FR-03 批量 DONE | devmind-flow（RequirementFlowService） |
| FR-02 session_id 列 + bindSession + WorkItemView；FR-03 开关列与 DTO | devmind-project |
| FR-02 startWorkItemSession 回填 | devmind-flow（RequirementFlowService） |
| FR-04 需求级幂等 | devmind-flow（PipelineOrchestrator/PipelineEligibility） |
| 前端：项目表单开关、WI 会话列 | frontend features/projects、features/requirements |

## 5. 数据模型

- `work_items.session_id` varchar(32) 可空——归属会话（最新一次），仅展示/跳转用途，不做门禁。
- `projects.auto_done_wi_on_dev` bit/boolean 可空——null=false（默认关），无 @ColumnDefault。
- 存量库随 `ddl-auto=update` 自动加列，无需手工 ALTER（两列均可空无默认）。

## 6. API 概要

- `ProjectRequest`/`ProjectView` 加 `autoDoneWiOnDev`（Boolean，更新不传 = 保持）。
- `WorkItemView` 加 `sessionId`。
- 无新端点。

## 7. 安全

- 全自动链的爆炸半径由两个项目级开关叠加控制：`auto_done_wi_on_dev`（本能力）+
  `auto_build_on_wi_done`（CAP-72），两者都开才会走到「AI 写完零人工构建」；默认全关。
- CAP-17 红线不松动：生产部署与发版永远人工；需求级验收（ACCEPTANCE→DONE）与收口合并仍人工。

## 8. 里程碑

- M1 后端：FR-01~FR-04 + 单测（flow/project）。
- M2 前端：项目表单开关 + WI 会话列；`npx tsc -b`。

## 9. 验收标准

1. 开启 AI 规划跑完：方案文档状态 CONFIRMED；固化的 WI 挂上 designId（追溯边恢复）。
2. `[flow:dev]` 启动后：本次置 IN_PROGRESS 的 WI 的 sessionId = 开发会话 id；WI 行内起会话同理；
   「重新开发」后 sessionId 覆盖为最新会话。
3. 开关关（默认）：开发会话成功后 WI 保持 IN_PROGRESS，行为与现状零差异。
4. 开关开：开发会话成功 → IN_PROGRESS 的 WI 全部 DONE（TODO/BLOCKED 不动）→ 需求 rollup
   ACCEPTANCE → `flow.dev.done` 通知带自动完成条数。
5. FR-04：同需求两个代码类 WI 同时 DONE（批量）→ 只产生一条构建记录；同需求已有构建记录后
   再 DONE 一个 WI → 不自动构建（人工触发不受影响）。
6. 更新项目不传 `autoDoneWiOnDev` = 保持原值。
7. `mvn -q test` 全绿、`npx tsc -b` 通过。

## 10. 风险与开放问题

| # | 风险/问题 | 应对 |
|---|-----------|------|
| R1 | 开发会话「成功」只代表进程正常退出，不代表 WI 真做完（漏做某项也 SUCCESS） | 只翻 IN_PROGRESS 且要求会话成功；需求级人工验收与收口仍是最终闸门；偏差靠验收发现后「重新开发」 |
| R2 | FR-04 需求级幂等改变手工逐 WI 项目的自动构建节奏 | 只影响显式开了 autoBuildOnWiDone 的项目；升级公告说明；人工触发兜底 |
| R3 | 批量 DONE 与 CAP-17 通知（pipeline.completed 按 WI 发）条数 | 部署完成通知仍按代表 WI 发一条，不放大 |
