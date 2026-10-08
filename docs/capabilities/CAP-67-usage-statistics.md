# CAP-67 用量统计（会话/问答 token 与成本聚合分析）

> 能力 ID：CAP-67 ｜ 分类：组装层 ｜ 状态：需求定稿（2026-10-08），待实施。

## 1. 目的

会话（CAP-05）与通用问答（CAP-30）的**单条用量账本早已落地**：claude result 帧经
`TurnUsage` 结构化后由 `onTurnResult` 纯 SQL 原子累加进 `sessions` / `chat_sessions`
的六列（`cost_usd`、`input/output/cache_read/cache_creation_tokens`、`turn_count`），
前端在会话列表「用量」列与问答列表副标题逐行展示。但**没有任何聚合层**——

- 想回答「这个需求烧了多少钱/多少 token」只能逐行心算；
- 想回答「平台这个月总共消耗多少、谁在消耗、哪个模型在消耗」无入口；
- 时间趋势（每日消耗曲线）完全缺失。

本能力补齐聚合统计：**总体大盘 + 多维分组（需求/项目/模型/用户）+ 每日趋势 + Top 明细**，
一个 `/usage` 页面承载，供个人复盘与平台成本分析。

**定位**：只读聚合能力——零新表、零写路径、零事件，不改被统计模块的任何代码；
统计口径直接落在现有累计列上（与 CAP-16 指挥中心「只读聚合」同型）。

### 非目标（v1 不做）

- 逐回合用量账本表（`usage_records`：每回合一行带时间戳）——当前累计列无逐回合时间戳，
  时段归属只能按会话**创建时间**近似（见「已知限制」）；如需精确逐日/逐回合再立 CAP 演进；
- 成本预算/配额/告警（超阈值通知、限额熔断）；
- 用量数据导出（CSV/Excel）；
- 按 runner 节点维度统计（`agent_node_id` 在会话上有，但节点是执行位置而非消费主体，
  分析价值低，后续需要再加一个 dim 即可）。

## 2. 功能需求

### FR-01 总体汇总（summary）

- 给定时段（`from`/`to` 可空 = 全部）与用户过滤，返回两源合并的总计：
  总成本、输入/输出/缓存读/缓存写 tokens、回合数、会话数、问答数；
- 前端以 `Statistic` 卡片行展示，时段快捷档：今日 / 近 7 天 / 近 30 天 / 全部。

### FR-02 多维分组（breakdown）

- `dim=requirement|project|model|user` 四种分组，每行返回：
  分组 key + 展示名、会话数、问答数、回合数、四类 token、成本；
- 需求维度：按 `sessions.requirement_id` 分组，标签补需求标题（project 模块批量查）；
  问答无需求归属，全部并入「未归属（问答）」桶；`requirement_id IS NULL` 的会话
  （自由会话/WORKLOG）并入「未归属（会话）」桶；
- 项目维度：同上按 `project_id` 分组，问答并入「未归属（问答）」桶；
- 模型维度：按 `model` 分组（null/空 → 「默认」），会话与问答都参与；
- 用户维度：按 `created_by` 分组，**仅 ADMIN 可用**（非 admin 请求此 dim 报 403）；
- 需求/项目行支持点击跳转对应详情页。

### FR-03 每日趋势（daily）

- `days` 参数（默认 30，上限 90）：返回每日 `{date, cost, tokens, turns}` 序列，
  会话与问答合并；
- 实现口径：查时段内轻量投影行（id/createdAt/六列），Java 侧按 `createdAt` 日桶聚合
  （避开 JPQL 日期截断在 H2/PG/MySQL 的方言差异）；
- 前端纯 div 柱状图（零新依赖），可切换成本/tokens 两种数值。

### FR-04 用量 Top 明细（top）

- 按成本降序取会话+问答混合 Top N（默认 20，上限 100）：
  来源（会话/问答）、标题（taskSpec/问答标题截断）、归属需求、模型、创建人、
  六列用量、创建时间；点击跳会话工作台/问答页。

### FR-05 数据可见范围（owner 强制）

- 口径照搬 CAP-62 个人数据 owner 强制与 `ChatManagerService.isAdmin()` 判定姿势：
  - 非 ADMIN：所有端点忽略 `userId` 入参，强制 `createdBy = 当前登录人`；
  - ADMIN：`userId` 缺省 = 平台全部，传值 = 指定用户；
- 统计不暴露他人数据的任何痕迹（分组行、Top 明细同口径过滤）。

## 3. 依赖关系

- CAP-01（认证/角色判定：IdentityService + UserEntity.ROLE_ADMIN）；
- CAP-05 / CAP-30（被统计的用量累计列；**只读**，不改其代码）；
- CAP-02 / CAP-13（需求标题、项目名标签补全，只读批量查）；
- 跨模块直接用实体有先例（devmind-session → devmind-project）。

## 4. 数据模型

**零新表**。全部统计来自 `sessions` / `chat_sessions` 现有六列累计列的 JPQL 聚合
（`ddl-auto` 无变更）。

### 已知限制（时段归属近似）

用量按会话/问答的**创建时间**归属时段：跨天长会话的全部消耗计入创建日，
时段过滤等价于「该时段创建的会话的累计用量」。逐回合精确归属需 `usage_records`
账本表（非目标，另立 CAP 演进；届时 summary 需 union 存量累计行与新账本行）。

## 5. API 概要

新模块 `devmind-usage`，`UsageController` `@RequestMapping("/api/usage")`：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/summary?from&to&userId` | FR-01 两源合并总计（Instant ISO 入参，可空） |
| GET | `/breakdown?dim&from&to&userId` | FR-02 分组行列表（dim ∈ requirement/project/model/user） |
| GET | `/daily?days&userId` | FR-03 每日序列（days 默认 30 上限 90） |
| GET | `/top?limit&from&to&userId` | FR-04 成本降序混合明细（limit 默认 20 上限 100） |

实现要点：

- 聚合查询走 usage 模块内新建的 Spring Data repo 接口（跨模块实体允许，
  `sum + coalesce` 防空，`group by` 投影）；每日趋势 Java 分桶；
- 标签补全：requirementId→标题（RequirementRepository 批量）、projectId→项目名、
  createdBy→用户显示名（UserRepository），均内存 join，不 N+1。

## 6. 前端

`frontend/src/features/usage/`（api/types/UsagePage 自包含），一级导航加「用量」
（个人级页面，无项目页签条）。布局遵循 `docs/core/前端内容区布局约定.md`
（多区块页 `pageRootScrollStyle`，三 Card）：

1. **总体卡**：title + 时段 `Segmented`，extra 刷新 +（admin）用户 `Select`；`Statistic` 行；
2. **每日趋势卡**：成本/tokens 切换，纯 div 柱状图；
3. **维度卡**：title 内 `Segmented`（按需求/按项目/按模型/Top，admin 多「按用户」）+ `FitTable`。

复用 `shared/utils/format.ts` 的 `fmtTokens`/`fmtCost`/`usageText`/`fmtTime`；禁新增图表依赖。

## 7. 验收标准

1. fake runner 造 1 会话（挂需求）+1 问答并完成入账后：
   - `/usage/summary` 总计 = 两源常量精确相加（cost/token 逐项）；
   - `/usage/breakdown?dim=requirement` 命中该需求行且数值等于 fake 常量；
   - `/usage/daily` 当日桶包含两源回合；`/usage/top` 列出两条且来源标记正确；
2. 非 admin 用户调任意端点只见自己的数据（含传 `userId=他人` 被忽略）；
   非 admin 请求 `dim=user` 报 403；
3. `mvn -q test` 全绿（含新增 `UsageStatsServiceTest`）；`npx tsc -b` 通过；
   `node tests/e2e-layout-pages.mjs` 覆盖 /usage 无溢出。

## 8. MVP 范围

上述 FR-01~05 全部（本能力体量即 MVP）。非目标项（账本表/预算/导出/节点维度）一律后置。
