# CAP-88 工作日志计划闭环（日/周计划项 + 执行关联 + 数据驱动复盘）

> 能力 ID：CAP-88 ｜ 分类：组装层 ｜ 状态：已实现（M1 计划项/关联/滚转 + 前端计划视图；
> M2 日报计划对照 + 周报数据驱动 {{lastWeekPlan}}/{{thisWeekPlan}}；M3 AI 建议计划：
> worklog-plan-suggest 场景 + suggest 链路（WipRequirementProvider SPI 探测降级）+
> 建议项 AI_SUGGEST 落库 + 前端「AI 建议」入口；FR-11 周期末提醒开放后置；
> E2E tests/cap88_e2e.py 26 项全绿）｜ 日期：2026-10-26
> **（2026-10-26 被 CAP-90 修订：交付当日实测反馈「日计划与条目双重记录、周计划与
> 周报下周计划隔离」——DAY scope 计划项整体退役并入条目（条目加 status，计划即
> 未完成条目），plan_items 收窄为 WEEK 唯一计划实体，周报「下周计划」改为计划项
> 单向投影；本节 FR-01 scope=DAY / FR-03 TODO→DOING 联动 / FR-05 日粒度 carry /
> FR-06 日计划视图口径作废，WEEK scope 与 M2/M3 主干沿用。）**
> （2026-10-26 修订：存储口径随 CAP-89 定稿**改为 DB 表**——CAP-74 的仓内文件
> 方案同日被推翻（远程推送判定无意义、git 链路整体退役），原计划文件契约作废；
> 功能闭环设计不变，仅载体从 `plans/*.md` 文件改为 `worklog_plan_items` 表 +
> `worklog_entries.plan_item_id` 列。）
> 决策记录：计划粒度定稿**日计划 + 周计划双层**（日计划管当天执行对照，周计划管
> 方向与周间滚转，两层经条目关联打通）；计划项为 DB 一等实体（随 CAP-89 存储
> 口径）；「AI 建议计划」纳入 M3，不阻塞 M1/M2 闭环先行。

## 1. 目的

工作日志现状是单向的「记录 → 回顾」链路：条目（git 导入/手动补录）记已发生的事，
日报/周报由 AI 对素材做总结。唯一的"计划"是周报里的 `next_plan_md` 自由文本——
不落结构、没有状态、不挂执行：

- 「上周计划跟进」（CAP-41 2026-10-23 增补）靠 LLM 解析上周文本逐条猜对应关系，
  既不可靠也无法在日常执行中对照；
- 计划没有生命周期：做完没做完、拖了几周，文本里都看不出来；
- 每天的执行（条目）与每周的方向（计划文本）之间没有连接点。

本能力把**计划升格为一等数据**（`worklog_plan_items` 表，计划项有状态、可关联、
可滚转），补齐闭环：

```
计划（worklog_plan_items：scope=DAY/WEEK，有状态）
   │ 执行时关联（worklog_entries.plan_item_id）
   ▼
条目（已发生事实）
   │ 生成时注入「计划 vs 实际」结构化对照
   ▼
日报：今日计划逐条有着落 + 计划外工作；周报：「上周计划跟进」由真实状态驱动
   │ 周期切换
   ▼
未完成项一键滚转次日/下周（CARRIED + carried_from_id 链，反复拖延可追溯）
```

## 2. 功能需求

### M1 计划项与执行关联

- **FR-01 计划项实体**：`worklog_plan_items` 表（见 §5）——user_id + scope
  （DAY/WEEK）+ plan_date（DAY=当天日期；WEEK=week_start 周一）+ title/content +
  plan_type（与条目同枚举 DEV/SUPPORT/MEETING/RESEARCH/OTHER）+ estimate_minutes
  （0.25h 步进口径）+ status + source + requirement_id/jira_issue_key 关联（沿用
  条目口径）+ carried_from_id + sort_order。按人隔离（仅能改自己的，他人 404，
  同条目口径）。
- **FR-02 计划 CRUD**：按 (scope, date/weekStart) 查列表；新增/编辑/删除/改状态
  常规 REST；工时内部分钟存储、API 暴露 hours（同条目）。
- **FR-03 状态机**：`TODO / DOING / DONE / DROPPED / CARRIED`。自动只到 DOING：
  条目关联计划项（FR-04）且该项为 TODO 时服务端置 DOING（关联只证明动过，不
  证明做完）；DONE/DROPPED 必须人工。状态历史 = updated_at + 演进预留（首版不
  自建审计表）。
- **FR-04 条目关联计划**：条目新增/编辑 API 增可选 `planId`（列
  `worklog_entries.plan_item_id`，随 CAP-89 FR-02 一并落；单关联——一项执行
  对应一个计划项）。git 扫描导入链路不动，导入后人工关联。删计划项时条目
  plan_item_id 置空（DB 世界直接清引用，无悬空 id 问题）。
- **FR-05 滚转**：打开某日/周计划视图时返回上一周期未完成项（TODO/DOING）提示；
  一键滚转 `POST /plans/carry`：目标周期插入各项副本（status=TODO、
  carried_from_id=原 id），原项置 CARRIED——单事务完成。`carried_from_id` 链使
  「一项拖了三周」可回溯；幂等（原项已 CARRIED 即跳过）。
- **FR-06 前端「计划」视图**：/worklog 页 Segmented 增「计划」（内层日/周切换 +
  日期/周导航，布局沿用内容区约定）：计划项列表（状态徽标 + 下拉改状态 + 预估
  工时 + 关联需求/Jira 展示）、新增/编辑抽屉、滚转提示条；条目编辑抽屉增「关联
  计划项」下拉（当日日计划 + 当周周计划，DOING/DONE 项也可选——补录忘挂的执行）；
  条目视图当日分组顶部显示计划完成度（如 3/5）。

### M2 数据驱动复盘

- **FR-07 日报生成注入计划对照**：`ReportService` 素材构建查当日计划项 + 条目
  plan_item_id 关联，prompt 增「今日计划对照」块（每项：标题/状态/关联条目
  摘要）；内置日报模板要求「今日重点」逐条呼应计划、无关联条目的工作归「其他
  进展/项目支持」。`{{entries}}`/`{{commits}}` 占位不变。
- **FR-08 周报「上周计划跟进」改数据驱动**：`{{lastWeekPlan}}` 内容由「上周周报
  next_plan_md 文本」升级为「上周计划项 + 真实状态 + 关联条目」结构化清单
  （占位符名不变，自定义模板兼容）；模板要求逐条标注「完成/部分完成/未完成」
  直接以 status 为准叙述，不再让模型从文本猜对应。上周无计划项时仍渲染「无
  上周计划：省略该块，不得编造」。本周计划项（若已排）一并注入，供「下周计划」
  节呼应——下周计划文本仍是汇报形态（自由文本），计划项才是结构载体，两者
  不互写（漂移风险见 §10）。
- **FR-09 存量兼容**：无计划项的历史日期/周，生成行为与现状完全一致（对照块
  省略）；`{{lastWeekPlan}}` 语义不变只升级内容来源。

### M3 AI 建议计划

- **FR-10 「建议下周计划」**：计划视图手动触发 → 走既有 worklog 会话生成链路
  （场景新增 `worklog-plan-suggest` 种子），素材 = 上周未完成/滚转项 + 在办需求
  （ObjectProvider 探测 CAP-13 SPI，缺席降级只用未完成项）+ 可选 hint；claude
  产 `.devmind/output/plan-suggest-yyyy-Www.md` → 服务端解析为建议项插
  `worklog_plan_items`，`source=AI_SUGGEST`、`status=TODO`。前端建议项带「AI」
  徽标，逐条编辑/删除即人工确认（同日报「自动草稿 + 人工确认」哲学，无独立
  接受态）。幂等：目标周已存在计划项时跳过生成；force 显式重生成只追加不覆盖
  已有项（已有项是人排过的事实）。
- **FR-11 周期末提醒（开放，可后置）**：周计划仍有未完成项时周五发站内提醒
  （CAP-06 既有广播制），引导滚转或标 DROPPED。

## 3. 否决方案

- **仓内计划文件（plans/*.md + marker 注释）**：初稿定稿、同日作废——该方案
  押注 CAP-74「仓 = 事实源」方向，CAP-89 判定远程无意义、git 链路整体退役后，
  文件载体只剩成本（解析器/无损往返机器），DB 表的状态/关联字段原生可查，
  计划项恰好是本能力最需要的结构化形态。
- **计划并入条目（PLANNED 标志位）**：否决。条目 = 已发生事实（日期 = 发生日），
  计划 = 面向未来且跨周期滚转，生命周期根本不同；混在一张表里会让「当日清单」
  语义分裂（今天记的事 vs 今天打算做的事），生成素材口径也要跟着分叉。
- **状态全自动（关联条目即 DONE）**：否决。关联只证明动过；自动只到 DOING，
  DONE 必须人工（M3 可让 AI 给建议值，但落库仍人工）。
- **周计划项取代周报 `next_plan_md` 文本**：否决。周报是给上级/团队看的汇报
  形态（叙述性文本），计划项是给自己跟踪的结构载体；强行合一要么汇报变干巴巴
  清单，要么跟踪失去结构。两者各司其职、不互写。

## 4. 架构与模块归属

- 后端：`devmind-worklog` 模块内——`PlanItemEntity/Repository/PlanService/
  PlanController`；ReportService 素材构建增计划对照注入。M3 新增 SPI
  `common.requirement.WipRequirementProvider`（实现方 devmind-project，在办需求素材）；
  无 runner/协议变更。
- 前端：`features/worklog` 增 `PlanView`（自包含）；`WorklogViewSwitch` 增
  「计划」页签；条目抽屉（EntryFormDrawer）增关联下拉。
- 依赖：**CAP-89（硬前置——条目表与 `plan_item_id` 列随其 FR-02 复活落地）**、
  CAP-41（生成链路/模板）、CAP-28（设置与调度骨架）；M3 另依赖 CAP-13（在办
  需求，ObjectProvider 探测缺席降级）、CAP-06（提醒，FR-11）。

## 5. 数据模型

```sql
worklog_plan_items(
  id PK,                      -- Long 自增（平台惯例；条目 String uuid 是 CAP-89 载明的有意例外，不扩散）
  user_id, scope,             -- DAY / WEEK
  plan_date,                  -- DAY=当天日期；WEEK=week_start（周一）
  title, content clob?,
  plan_type default 'DEV',    -- DEV/SUPPORT/MEETING/RESEARCH/OTHER
  estimate_minutes int?,      -- 分钟存储，API 暴露 hours（0.25h 步进）
  status default 'TODO',      -- TODO/DOING/DONE/DROPPED/CARRIED（@ColumnDefault 带引号）
  source default 'MANUAL',    -- MANUAL / AI_SUGGEST（M3）
  requirement_id?, jira_issue_key?,
  carried_from_id?,           -- 滚转来源项 id（自引用，拖延链可追溯）
  sort_order int, created_at, updated_at)

worklog_entries ── + plan_item_id?   -- 随 CAP-89 FR-02 一并落；删计划项时置空
```

## 6. API 概要

```
GET    /api/worklog/plans?scope=DAY|WEEK&date=|weekStart=   计划项列表（附上一周期未完成提示数据）
POST   /api/worklog/plans/items              新增 {scope, date|weekStart, title, content?, type, hours?, req?, jira?}
PUT    /api/worklog/plans/items/{id}         编辑/改状态
DELETE /api/worklog/plans/items/{id}         删除（关联条目 plan_item_id 置空）
POST   /api/worklog/plans/carry {scope, to, ids[]}          滚转到目标日期/周（幂等）
GET    /api/worklog/plans/linkable?date=                    条目抽屉可关联项（当日日计划+当周周计划）
POST   /api/worklog/plans/suggest {date, hint?, force?}       M3 AI 建议（异步受理，返回 ReportLaunch{sessionId, reused}；
                                                              date=目标周内任意日归一到周一；已有项非 force 幂等跳过，force 只追加不覆盖）

条目 POST/PUT 增可选 planId（落 plan_item_id + 触发 TODO→DOING 联动）
```

全部端点仅本人数据（owner 强制）；suggest 走既有 worklog 会话生成链路（异步、
亲和节点离线 409 口径同日报生成）。

## 7. 安全

- 个人域数据 owner 强制（CAP-62 口径）：计划项读写仅本人，越权 404（同条目）。
- 关联字段（requirement_id/jira_issue_key/plan_item_id/carried_from_id）服务端
  校验归属合法性：plan_item_id 必须是本人项，requirement 沿用条目的关联校验
  先例。
- AI_SUGGEST 项只是草稿行，不触发任何自动执行；FR-10 生成会话沿用 worklog
  场景只读权限口径。

## 8. 里程碑

- **M1 闭环骨架**：`worklog_plan_items` 表 + CRUD/状态/滚转 API + 条目关联
  （planId + TODO→DOING 联动）+ 前端「计划」视图与条目抽屉关联下拉。
  （依赖 CAP-89 M1 先落——条目表与 plan_item_id 列在其 FR-02。）
- **M2 复盘确定性**：日报计划对照注入 + 周报「上周计划跟进」改数据驱动 + 内置
  模板更新（存量自定义模板兼容）。
- **M3 智能建议**：`worklog-plan-suggest` 场景种子 + suggest 链路（CAP-13 探测
  降级）+ 前端建议项交互；（FR-11 周期末提醒视打扰度评估后定，本里程碑未实施）。

## 9. 验收标准

1. 计划项 CRUD：新增日/周计划项落库，编辑/删除/改状态生效；他人项 404；
2. 条目关联计划项：条目 plan_item_id 落库，关联项 TODO 自动变 DOING；删计划项
   后关联条目 plan_item_id 置空、正常渲染不报错；
3. 状态流转：DOING→DONE/DROPPED 人工改成功；TODO 不可被任何自动路径直接置
   DONE；
4. 滚转：昨日 2 项未完成 → 今日计划视图提示并可一键滚转，原项 CARRIED、新项
   带 carried_from_id 链；重复滚转幂等；跨周滚转同样成立；
5. 日报生成：prompt 素材含当日计划对照块，成稿「今日重点」呼应计划项、计划外
   工作归「其他进展/项目支持」；无计划项的历史日期生成行为与现状一致；
6. 周报生成：上周有计划项时「上周计划跟进」逐条对应且状态与库中一致（数据
   驱动，非模型猜）；上周无计划项时该块省略不编造；
7. M3：触发「建议下周计划」→ 建议项以 AI_SUGGEST 落库、前端带徽标，可逐条
   删改；目标周已有项时跳过生成；CAP-13 缺席时仅用未完成项素材正常生成；
8. 全量 `mvn test` 绿；E2E（tests/ 沉淀）覆盖 CRUD/关联联动/滚转幂等/生成
   对照注入。

## 10. 风险与开放问题

- **计划项与周报 `next_plan_md` 双写漂移**：口径已定（各写各的、不互写），但用户
  可能困惑「周报里写的下周计划怎么没进计划视图」。开放问题：周报生成后是否提供
  「把下周计划文本转为计划项」入口（解析自由文本不可靠，倾向 AI_SUGGEST 链路
  顺带覆盖该场景——下周计划以建议项先行、周报文本引用之）。M3 实施时再定。
- **滚转链噪音**：一项反复滚转会产生长 carried_from_id 链与多个 CARRIED 行。
  M1 先不做折叠，视实际使用加「链式合并展示」。
- **FR-11 提醒打扰度**：广播制通知下多人部署会互相看到提醒，评估后再定或等
  通知按人投递演进。
- **est 预估与实际工时对照**：条目有 minutes、计划有 estimate_minutes，M2 后可
  加「预估 vs 实际」偏差展示；首版只存不比。
- **状态历史**：首版只有 updated_at；出现「什么时候标 DONE 的」审计诉求再立
  演进（状态变更流水表），不阻塞。
