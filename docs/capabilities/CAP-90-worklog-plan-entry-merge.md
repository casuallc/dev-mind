# CAP-90 工作日志计划-条目合并与周报投影（日计划并入条目，周计划↔周报单向打通）

> 能力 ID：CAP-90 ｜ 分类：组装层 ｜ 状态：定稿未实施 ｜ 日期：2026-10-26
> 决策记录：**日计划并入条目**（推翻 CAP-88 §3「计划并入条目」否决——CAP-88 交付
> 当日实测反馈：同一件事要录两遍（计划一条 + 条目一条 + 手动关联），双重记录代价
> 大于「条目=纯事实」的语义纯度收益；语义分裂担忧用 `status=DONE 即事实」单一判别
> 化解）；**周计划保留为唯一计划实体**（周粒度无单一发生日，条目表装不下，且它是
> 周报「上周计划跟进」的结构载体）；**周报「下周计划」= 下周计划项的单向叙述投影**
> （计划项是唯一事实源，文本人工可润色不回写；推翻 CAP-88 FR-08「两者不互写」口径，
> §10 自述的「周报写的下周计划怎么没进计划视图」困惑由此消除）。本能力修订 CAP-88：
> 其 DAY scope 相关口径（FR-01 scope=DAY、FR-03 TODO→DOING 联动、FR-05 日粒度
> plans/carry、FR-06 日计划视图）被本能力取代，WEEK scope 与 M2/M3 主干沿用。

## 1. 目的

CAP-88 把计划升格为一等数据（`worklog_plan_items`，日/周双层），闭环骨架成立，但
交付当天实际使用暴露两个设计与交互问题：

1. **日计划与条目双重记录**：「今天要修 X bug」在日计划录一条；做完在条目再记一条、
   还要在抽屉里手动挂 planId 关联。同一工作单元录两遍，日计划层沦为誊抄——用户
   直觉是「计划就是还没做的条目，完成了就标记完成」。
2. **周计划与周报「下周计划」隔离**：周计划项（结构载体）与周报 `next_plan_md`
   （自由文本）各写各的、互不流动（CAP-88 FR-08 口径），用户在周报里写的计划进不了
   计划视图，计划视图排的项也不体现在周报里，CAP-88 §10 已自列为开放问题。

本能力把计划闭环收敛为更顺的形态：

```
条目（status：TODO/DOING = 计划，DONE = 事实；当日清单 = 计划+事实一处管）
   │ plan_item_id 关联当周周计划项（日执行对齐周方向）
   ▼
周计划项（唯一计划实体：方向 + 周间滚转 + 上周计划跟进数据源）
   │ 单向投影（生成时渲染 / 编辑器「从计划项重建」）
   ▼
周报 next_plan_md（汇报形态文本，人工润色不回写）
```

## 2. 功能需求

### M1 日计划并入条目

- **FR-01 条目加状态列**：`worklog_entries` 增 `status`（TODO/DOING/DONE/DROPPED/
  CARRIED，`@ColumnDefault("'DONE'")`，存量事实条目天然回填 DONE）、
  `estimate_minutes`（可空，计划态预估，0.25h 步进口径同条目 minutes）、
  `carried_from_id`（varchar(32) 可空，自引用条目 uuid，滚转链）。
- **FR-02 状态语义**：git 导入 / AGENT 来源恒 DONE（事实）；手动新建默认 DONE
  （补录已发生的），显式传 `status=TODO` 即计划。TODO↔DOING 人工互转，DONE/DROPPED
  人工；CARRIED 仅滚转写入。原「条目关联计划项 → TODO 自动 DOING」联动随 DAY
  计划项退役（计划即条目，无关联可挂）；条目 `plan_item_id` 保留，只指向 WEEK 项。
  EntryRequest 新增字段用 `Boolean`/包装类型（Jackson 3 红线）。
- **FR-03 「完成」一步到位**：条目视图当日列表中 TODO/DOING 行显示「完成」按钮 →
  弹工时确认（预填 estimate_minutes 换算值，可改）→ 一次 PUT 置 DONE + minutes。
  不再需要「先建计划、再录条目、再挂关联」三步。
- **FR-04 素材与统计口径**：日报/周报素材、工时统计一律只取 `status=DONE` 条目
  （TODO/DOING/DROPPED/CARRIED 不进工时与正文素材）。日报「今日计划对照」块数据源
  从 DAY 计划项换成**当日未完成条目 + 当周周计划项**（对照语义不变）；周报
  `{{lastWeekPlan}}` 数据源不变（WEEK 计划项）。
- **FR-05 日粒度滚转**：`POST /api/worklog/entries/carry {to, ids[]}`——目标日插入
  各条目副本（新 uuid、status=TODO、estimate 沿用、carried_from_id=原 uuid），
  原行置 CARRIED，单事务；幂等（原行已 CARRIED 跳过）。条目视图当日列表返回
  上一日未完成项提示（沿用 plans 列表的 carryover 形态）。周粒度滚转
  （plans/carry scope=WEEK）不动。
- **FR-06 计划项表收窄为 WEEK**：plans CRUD 校验 `scope≠WEEK → 400`；
  `GET /plans?scope=DAY` 与 `POST /plans/carry scope=DAY` 同步退役 400；
  `linkable?date=` 只返回当周 WEEK 项。**存量 DAY 项迁移**（ApplicationRunner
  幂等任务，照 CAP-89 FR-03 范式）：DAY 项映射为条目（新 uuid；TODO/DOING→同状态
  条目 minutes=0、estimate 沿用；DONE→DONE 条目 minutes=estimate 兜底 0；
  DROPPED/CARRIED→同状态条目），迁移内两趟回填 carried_from_id（plan id→新 uuid
  映射）保滚转链；条目 plan_item_id 指向 DAY 项的一律置空（关联语义已被
  「计划即条目」吸收）；迁移完成删除全部 DAY 行。幂等键：无 DAY 行即跳过。
- **FR-07 前端「条目」视图即今日清单**：当日列表加状态列（TODO/DOING 徽标 +
  「完成」快捷钮；CARRIED/DROPPED 淡化）；条目抽屉加「状态」选择（计划/事实）
  与「预估工时」（仅计划态显示）；当日完成度徽标口径不变（数据源换 entries）。
  「计划」视图移除日/周内层切换，只留周计划（AI 建议、滚转、关联展示不动）；
  条目抽屉「关联计划项」下拉只列当周周计划项——日执行直接对齐周方向。
- **FR-08 AI 建议链路不变**：suggest 本就只产 WEEK 项，无改动。

### M2 周报「下周计划」单向投影

- **FR-09 生成素材注入下周计划项**：周报素材构建增查下周（weekStart+7d）WEEK
  计划项，新占位 `{{nextWeekPlan}}`（有计划项=结构化清单；无=占位文案「无下周
  计划项：按本周总结合理推断」维持现状行为）。内置周报模板「下周计划」节要求
  优先逐条叙述该清单；自定义模板不含新占位即不生效（兼容口径同 CAP-88 FR-09）。
- **FR-10 「从计划项重建」按钮**：`GET /api/worklog/weekly/next-plan-preview?weekStart=`
  服务端按固定格式把下周计划项确定性渲染为 markdown（不经 LLM）返回
  `{markdown, itemCount}`；周报编辑器「下周计划」字段旁按钮拉取预览，确认后填入
  编辑框（人工保存才落库，沿用「自动草稿 + 人工确认」哲学）。周计划视图在该周
  已被（上周）周报引用时显示「已写入周报」徽标（前端派生，无新字段）。

## 3. 否决方案

- **计划/事实继续分表（CAP-88 现状）**：交付当日实测推翻——日粒度上计划与条目是
  同一工作单元的两个时态，分表迫使双重记录 + 手动关联，交互代价大于语义纯度收益。
- **周计划项也并入条目**：否决。条目日期=发生日，周粒度无单一日期可落；且周计划
  是周报「上周计划跟进」与「下周计划」投影的结构载体，独立表原生可查。
- **周报文本解析回计划项（双向同步）**：维持 CAP-88 §10 否决——自由文本解析不可靠，
  漂移风险大；缺口由 FR-09/FR-10 单向投影 + CAP-88 M3 AI 建议链路覆盖。
- **「完成」自动判定（关联 git commit 即 DONE）**：维持 CAP-88 否决——DONE 必须人工，
  自动只证明动过。

## 4. 架构与模块归属

- 后端：`devmind-worklog`——`WorklogEntryEntity/EntryRequest/EntryView` 加列加字段、
  `WorklogEntryService`（状态校验 + carry）/ `WorklogEntryController`（carry 端点）、
  `WorklogPlanService/Controller`（scope 收窄 400 校验）、`DayPlanToEntryMigration`
  （ApplicationRunner 幂等）、`ReportService/ReportPrompts/WorklogTemplates`
  （对照块数据源切换 + `{{nextWeekPlan}}`）、`WorklogReportController`
  （next-plan-preview 端点）。无 runner/协议/SPI 变更。
- 前端：`features/worklog`——`WorklogPage` 条目视图（今日清单 + 完成交互 + 完成度
  数据源）、`EntryFormDrawer`（状态/预估工时）、`PlanView`（只留周计划）、
  `ReportEditor`/周报视图（重建按钮）、`types.ts/api.ts`。
- 依赖：CAP-89（条目表）、CAP-88（周计划与复盘主干）；无新依赖。

## 5. 数据模型

```sql
worklog_entries ── + status varchar(16) not null default 'DONE'  -- TODO/DOING/DONE/DROPPED/CARRIED
                   + estimate_minutes int?                       -- 计划态预估（分钟存储，API 暴露 hours）
                   + carried_from_id varchar(32)?                -- 自引用条目 uuid，滚转链

worklog_plan_items ── scope 收窄 WEEK（DAY 存量迁移为条目后删除行；表结构不动）
```

- 新列走 ddl-auto 自动加；`status` 带默认值，新增列时 H2/MySQL/PG 均回填存量行
  （上线后抽查一次）；`@ColumnDefault("'DONE'")` 字符串默认必带引号（红线）。
- 存量手工动作：无（DDL 自动 + DAY 项迁移走应用内幂等任务）。

## 6. API 概要

```
POST   /api/worklog/entries                  增可选 status（默认 DONE）/estimateHours
PUT    /api/worklog/entries/{id}             同上 + 改状态（CARRIED 人工不可设 400）
POST   /api/worklog/entries/carry {to, ids[]}   日粒度滚转（幂等；原行置 CARRIED）
GET    /api/worklog/entries?from=&to=        响应 EntryView 增 status/estimateHours/carriedFromId

GET    /api/worklog/plans?scope=DAY          → 400（退役）
POST   /api/worklog/plans/items              scope≠WEEK → 400
POST   /api/worklog/plans/carry scope=DAY    → 400（用 entries/carry）
GET    /api/worklog/plans/linkable?date=     只返回当周 WEEK 项

GET    /api/worklog/weekly/next-plan-preview?weekStart=   → {markdown, itemCount}（确定性渲染，非 LLM）

周报模板新占位 {{nextWeekPlan}}（下周计划项清单，无则推断提示）
```

全部端点仅本人数据（owner 强制，口径不变）。

## 7. 安全

- 个人域 owner 强制不变（计划态条目与事实条目同口径，越权 404）。
- next-plan-preview 只读本人下周计划项，确定性渲染无 LLM 调用、无注入面。
- 状态校验服务端兜底：CARRIED 人工不可设、git/AGENT 来源不可转非 DONE。

## 8. 里程碑

- **M1 日计划并入条目**：FR-01~FR-07（条目加列 + 状态语义 + 完成交互 + 素材口径 +
  日滚转 + plan 收窄 WEEK + DAY 存量迁移 + 前端今日清单与周计划视图收敛）。
- **M2 周报投影**：FR-09~FR-10（生成素材注入 + 重建按钮 + 引用徽标）。

## 9. 验收标准

1. 手动新建条目默认 DONE；传 TODO 落计划态；TODO/DOING 行「完成」一次置 DONE+工时；
   CARRIED 人工设 400；git 导入条目恒 DONE；
2. 日粒度滚转：昨日 2 项未完成 → 今日提示并可一键滚转，原行 CARRIED、副本带
   carried_from_id；重复滚转幂等；
3. 存量 DAY 计划项迁移为对应状态条目、滚转链 uuid 回填正确、条目 plan_item_id 指向
   DAY 项的置空；二次启动迁移跳过；
4. plans DAY scope 全部端点 400；linkable 只返回 WEEK 项；
5. 日报素材只含 DONE 条目，「今日计划对照」含当日未完成条目 + 当周周计划项；
6. 周报生成：下周已排计划项时「下周计划」逐条呼应清单；无计划项时维持推断行为；
   next-plan-preview 渲染与下周计划项一致，重建按钮填入后人工保存落库；
7. 全量 `mvn test` 绿；E2E tests/cap90_e2e.py 覆盖状态流转/完成交互/日滚转幂等/
   DAY 迁移/素材口径/投影渲染。

## 10. 风险与开放问题

| # | 事项 | 口径 |
|---|------|------|
| R1 | 条目表混入计划态，「事实」语义纯度弱化 | 接受：`status=DONE 即事实`单一判别，素材/工时一律 DONE-only |
| R2 | CARRIED 行在条目列表产生噪音（同 CAP-88 滚转链问题） | 缓解：前端默认淡化/折叠；视使用加链式合并 |
| R3 | DAY 迁移在共享库多实例部署下并发执行 | 缓解：幂等（无 DAY 行即跳过）+ 单事务逐项迁移，重复执行无害 |
| O1 | 预估 vs 实际工时偏差展示（estimate_minutes 与 minutes 已同表） | 开放：M2 后视使用加，首版只存不比 |
| O2 | 周报文本人工润色后与计划项漂移 | 接受：投影快照性质，「重建」按钮可随时再同步 |
| O3 | 状态历史审计（什么时候标 DONE 的） | 开放：沿用 CAP-88 口径（首版只有 updated_at），有诉求再立演进 |
