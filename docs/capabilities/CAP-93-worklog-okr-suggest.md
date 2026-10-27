# CAP-93 工作日志 OKR AI 生成（草稿确认落库）

> 能力 ID：CAP-93 ｜ 分类：组装层 ｜ 状态：实施中 ｜ 日期：2026-10-27
> 决策记录：**草稿确认落库**（AI 产物解析进 `worklog_okr_drafts` 草稿表，人工在抽屉里勾选/
> 编辑后「采纳」才批量建目标——OKR 是方向性数据，复用「建议计划」的直接落库会把模型幻觉
> 直接写进目标域，风险等级不同）；**周期可选**（YEAR 或任一 QUARTER，前端生成弹层默认
> 当前查看周期的下一周期，可改）；**复用会话生成链路**（新场景 `worklog-okr-suggest`，
> 与日报/周报/建议计划/季度复盘同构：服务端组 prompt 携带素材 → runner 会话执行 →
> `.devmind/output/okr-suggest-<period>.md` 回传 → `WorklogOutputMirror` 落草稿 +
> 站内通知）；**产物契约 = 两级列表行**（`- [O] 标题（权重 X%）` / 缩进 `- [KR] 标题`，
> 与 PlanSuggestParser 同族的行解析、不合规行静默丢弃）；**采纳复用既有校验链**（逐条走
> `WorklogGoalService.create`：KR 必挂同周期 O、季度 O 可选挂年度 O 等规则不变，不另开
> 写入旁路）。

## 1. 目的

CAP-91 把年度目标 + 季度 OKR 升格为一等数据并接入计划闭环，但目标本身仍要纯手工从零
填写：年初/季初定 OKR 时，「上一个周期实际做了什么、既有目标进展到哪」都已在系统里
（条目/周计划/周报/复盘/目标树），却要人去翻完再凭印象写——与 CAP-88 之前「周报靠回忆」
同构。本能力让 AI 基于这些真实素材生成下一周期 OKR 草稿，人工确认后落库，把「定目标」
从空白页写作变成审稿。

## 2. 功能需求

### M1 后端：生成链路 + 草稿 + 采纳

- **FR-01 生成触发**：`POST /api/worklog/goals/suggest {scope, period, force?, hint?}` →
  `ReportLaunch`。同步预检：目标周期已有目标且非 force → `reused=true` 不起会话（幂等，
  同 suggestPlan 口径）。素材组装（prompt 携带，模型不查库）：
  - QUARTER：所属年年度 O 树 + 目标季度的上一季度 OKR 进展（O/KR 状态与进度）+
    上一季度周报摘要（逐周 summary 截断）+ 上一季度条目按类型聚合；
  - YEAR：当年年度 O 树（含进展）+ 当年各季度 OKR 进展 + 当年各季度复盘摘要（有则）+
    全年条目按类型聚合。
- **FR-02 产物契约与解析**：场景产 `.devmind/output/okr-suggest-<period>.md`
  （period = `2026` 或 `2026-Q1`）。行契约：
  - O 行：`- [O] 标题（权重 40%）`（权重段可选，0~100）；
  - KR 行：O 行之后的缩进 `- [KR] 标题（权重 30%）`；
  - 容错（`OkrSuggestParser`，同 PlanSuggestParser 口径）：不合规行静默丢弃；括号段
    全/半角兼容；围栏代码块内的行不算；KR 无前置 O 丢弃；总量封顶 5 个 O、每 O 6 条 KR；
    标题截 256。
- **FR-03 草稿落库**：新表 `worklog_okr_drafts`（见 §5）。`WorklogOutputMirror` 消费
  `worklog-okr-suggest` 会话完成事件：解析产物 → upsert 草稿（user+period 唯一草稿行，
  重生成覆盖内容与解析结果、状态回 DRAFT）→ 发 `worklog.okr.suggested` 事件（站内通知
  沿用既有路由）。缺回传/解析为空 → 降级通知（同建议计划口径）。
- **FR-04 草稿查询/采纳/丢弃**：
  - `GET /api/worklog/goals/suggest/draft?period=` → 最新草稿（含解析树 + 产物原文 +
    sessionId 追溯），无草稿 exists=false（200 不报 404，同报告口径）；
  - `POST /api/worklog/goals/suggest/adopt {draftId, objectives:[{title, content?, weight?,
    parentYearGoalId?, krs:[{title, content?, weight?}]}]}` → 单事务两阶段（先 O 后 KR）
    逐条走 `WorklogGoalService.create` 校验链；任一条失败整体回滚 400；成功置草稿
    ADOPTED，返回创建数与目标视图；
  - `POST /api/worklog/goals/suggest/discard {draftId}` → 置 DISCARDED（采纳前可重来：
    force 重生成覆盖回 DRAFT）。
  - 归属：仅能操作本人草稿（他人 404）。

### M2 前端：生成入口 + 草稿确认抽屉

- **FR-05 生成入口**：GoalBoard 年度卡与季度卡 extra 各加「AI 生成」按钮 → 弹层选目标
  周期（Segmented YEAR/QUARTER + 周期选择，默认 = 当前查看周期的下一周期）+ 提示词
  （可空）→ POST suggest → 轮询草稿（同季度复盘抽屉的轮询模式，超时 5 分钟转通知中心）。
- **FR-06 草稿抽屉**：`OkrSuggestDrawer` 展示解析树为可编辑列表：每条勾选（默认全选）、
  标题/权重可改、行可删、季度 O 可选挂年度 O（下拉，候选 = 该年年度 O）；「采纳 N 项」
  提交 adopt（未勾 O 连带其 KR 不提交）→ 刷新目标树；「丢弃」调 discard。生成中/轮询
  超时/失败均 fail-visible。

## 3. 否决方案

- **AI 直接落库 goals**（同建议计划 `insertSuggestions`）：否决——OKR 是方向性低频数据，
  模型幻觉直接进目标域代价远高于一条周计划建议；草稿确认是 CAP-90 FR-10「从数据重建
  人工确认才落库」哲学的延续。
- **同步直调 LLM 不经会话**：否决——与日/周报生成链路不一致，丢掉 runner 执行环境、
  事件流可追溯与统一降级通知。
- **goals 表加 SUGGESTED 状态混存草稿**：否决——污染目标状态机（CAP-91 FR-03 全人工
  四态），且草稿需要编辑态与产物原文，生命周期不同。

## 4. 架构与模块归属

全部落在 `devmind-worklog`（同 CAP-88/91 生成链路）：

- 触发/素材：`ReportService.suggestOkr` + `ReportPrompts.okrSuggest`（素材组装复用
  tree/周报/条目聚合的既有查询）；
- 场景资产：`WorklogScenarios.OKR_SUGGEST` + `WorklogAssetSeeder.OKR_SUGGEST_SKELETON`
  （骨架注明产物行契约，与 OkrSuggestParser 同步）；
- 解析/落草稿：`OkrSuggestParser` + `WorklogOutputMirror.mirrorOkrSuggest` +
  `WorklogOkrDraftRepository`；
- 采纳：新 `OkrSuggestService`（草稿查询/采纳/丢弃），采纳写入复用 `WorklogGoalService`；
- 前端：`frontend/src/features/worklog`（GoalBoard 按钮 + OkrSuggestDrawer + api.ts）。

## 5. 数据模型

新表（ddl-auto 自动演进，无手工迁移）：

```sql
worklog_okr_drafts (
  id          BIGINT PK,
  user_id     VARCHAR(64)  NOT NULL,
  scope       VARCHAR(16)  NOT NULL,   -- YEAR | QUARTER
  period      VARCHAR(8)   NOT NULL,   -- '2026' | '2026-Q1'
  content_md  LONGTEXT,                -- 产物原文（追溯/排查）
  items_json  LONGTEXT,                -- 解析树 JSON：[{title,content,weight,krs:[...]}]
  status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',  -- DRAFT/ADOPTED/DISCARDED
  session_id  VARCHAR(64),             -- 生成会话追溯
  created_at  DATETIME, updated_at DATETIME,
  UNIQUE KEY (user_id, period)         -- 每人每周期一份草稿，force 重生成覆盖
)
```

复用表：`worklog_goals`（采纳写入）、`session_outputs`（产物回传）不变。

## 6. API 概要

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/worklog/goals/suggest` | 触发生成（幂等 reused，同 suggestPlan） |
| GET | `/api/worklog/goals/suggest/draft?period=` | 最新草稿（无则 exists=false） |
| POST | `/api/worklog/goals/suggest/adopt` | 采纳草稿批量建目标（事务） |
| POST | `/api/worklog/goals/suggest/discard` | 丢弃草稿 |

复用：`GET /api/worklog/goals/tree`（采纳后刷新）、会话链路既有端点。

## 7. 安全

全部端点仅本人数据（owner 强制，越权 404，同目标域口径）；生成走本人 WORKLOG 空间
会话，节点路由/离线 409 由会话层既有门控；素材仅含本人工作日志数据，不出个人域。

## 8. 里程碑

- **M1 后端**（FR-01~FR-04）：生成链路 + 解析 + 草稿 + 采纳/丢弃 + 单测。
- **M2 前端**（FR-05/FR-06）：生成入口 + 草稿确认抽屉 + E2E。

## 9. 验收标准

- M1：`OkrSuggestParserTest`（行契约/容错/封顶/围栏）、`OkrSuggestServiceTest`
  （采纳两阶段/事务回滚/归属 404/幂等覆盖）、`WorklogOutputMirror` 草稿落库单测；
  `mvn -q test` 全绿。
- M2：`tests/cap93_okr_suggest_e2e.py`（隔离 app + fake executor：触发 suggest → 产物
  回传 → 草稿出现 → 采纳后 goals 树含新目标；discard 路径）通过；`npx tsc -b` 通过。

## 10. 风险与开放问题

| 编号 | 描述 | 口径 |
|------|------|------|
| R1 | 素材窗口取「上一周期」对年初生成（无上年数据）退化为少量素材 | 接受：素材块为空时渲染「无数据」提示，模型按 hint 生成（同建议计划降级口径） |
| R2 | 采纳编辑后树校验失败（如 KR 父 O 未勾选） | 缓解：前端联动（未勾 O 连带 KR 不提交）+ 后端整体回滚报错 |
| O1 | 年度 O 的 KR（YEAR scope 也允许 KR 挂年度 O）生成支持 | 本期支持：YEAR 草稿同样 O+KR 两级 |
| O2 | CAP-76 工具侧补 suggest/adopt 工具 | 后置：先有 UI 闭环，工具化需求明确后补 |
