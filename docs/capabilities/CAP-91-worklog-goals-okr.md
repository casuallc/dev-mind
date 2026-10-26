# CAP-91 工作目标与 OKR 对齐（年度目标 + 季度 OKR + 周计划对齐 + 季度复盘）

> 能力 ID：CAP-91 ｜ 分类：组装层 ｜ 状态：已实现（M1+M2+M3，2026-10-26） ｜ 日期：2026-10-26
> 决策记录：**年/季一张表**（`worklog_goals`：scope=YEAR/QUARTER × kind=OBJECTIVE/KEY_RESULT，
> 低频实体不拆表；季度 O 可选挂年度 O，KR 必挂同周期 O）；**计划对齐只加一层关联**
> （周计划项挂 `kr_id`，条目经 plan_item_id 间接对齐，不给条目再加列）；**季度复盘 =
> 数据驱动投影**（KR 达成度由关联周计划项真实状态 + DONE 条目工时聚合驱动，沿用 CAP-88
> 「上周计划跟进不靠 LLM 猜」的同一思路往上搬）；**KR 进度人工落库 + 派生建议值展示**
> （沿用「DONE 必须人工」哲学，派生值只展示不回写）；**KR 跨季滚转复用 carried_from_id 链**。

## 1. 目的

CAP-88/90 把计划闭环收敛为「日条目（计划态/事实）→ 周计划项 → 周报投影」三级漏斗，解决
了「计划 vs 执行」的日/周对照。但实际工作的目标管理是更长周期的两级：**年初定年度目标、
每季定 OKR**。现状下这两层完全游离在系统外，后果：

- **方向不可见**：排周计划时看不到当季 KR，「日常执行是否偏离季度方向」无从对照；
- **季末复盘靠回忆**：季度 OKR 达成度要在季末凭印象拼凑，与 CAP-88 之前的「上周计划跟进
  靠 LLM 猜文本」同构的不可靠；
- **漏斗断裂**：年度目标 → 季度 KR → 周计划 → 日执行之间没有结构连接点，计划闭环止步于周。

本能力把目标两级升格为一等数据并接入既有闭环：

```
年度目标（worklog_goals scope=YEAR）
  └─ 季度 OKR（scope=QUARTER：O + KR，KR 必挂同季 O）
        │ 周计划项挂 kr_id（对齐；条目经 plan_item_id 间接对齐）
        ▼
周计划项（CAP-88/90 已有）
  ▼
日条目（CAP-90 已有）
        │ 季末
        ▼
季度复盘报告：KR 达成度 = 关联周计划项真实状态 + DONE 条目工时聚合（数据驱动，不靠回忆）
```

## 2. 功能需求

### M1 目标实体与周计划对齐

- **FR-01 目标实体**：`worklog_goals` 表（见 §5）——user_id + scope（YEAR/QUARTER）+
  period（`2026` / `2026-Q1` 格式）+ kind（OBJECTIVE/KEY_RESULT）+ parent_id（KR 必填，
  挂同 scope+period 的 O；QUARTER O 可选挂同年 YEAR O；YEAR O 必空）+ title/content +
  weight（权重%，可空）+ progress（0-100 人工）+ status + carried_from_id + sort_order。
  按人隔离（仅能改自己的，他人 404，同条目/计划项口径）。
- **FR-02 目标 CRUD**：按 year 查整年树（`GET /goals/tree?year=`：年度 O 列表 + 四个季度
  各 O→KR 嵌套）；新增/编辑/删除/改状态/改进度常规 REST。校验：period 格式与 scope 匹配；
  parent 归属与层级规则（KR→同周期 O；QUARTER O→同年 YEAR O）；progress ∈ [0,100]。
  删除：O 有子项（KR 或季度 O）→ 400（先删/改挂子项）；删 KR → 引用它的周计划项 kr_id
  置空（同删计划项清条目引用的口径）。
- **FR-03 状态机**：`ACTIVE / DONE / DROPPED / CARRIED`。全部人工（目标是低频实体，
  不做自动流转）；CARRIED 仅滚转写入，人工不可设、已滚转项只读（同 CAP-88 计划项口径）。
- **FR-04 KR 跨季滚转**：`POST /goals/carry {toPeriod, ids[], krParentMap?}`——目标周期
  插入各项副本（status=ACTIVE、carried_from_id=原 id），原项置 CARRIED，单事务；幂等
  （原项非 ACTIVE 跳过）。KR 的新 parent 解析：`krParentMap[krId]` 显式指定 → 同批携带的
  父 O 副本 → 皆无 400（提示携带父 O 或显式指定目标 O）。
- **FR-05 周计划项挂 KR**：`worklog_plan_items` 增 `kr_id` 列（可空）；计划项新增/编辑
  API 增可选 `krId`（服务端校验必须是本人 KEY_RESULT，否则 400）；滚转复制 kr_id；
  计划项列表视图附 `krTitles` 映射（避免前端逐行解析）。**不做周期强校验**（周会跨季，
  如 2026-03-30 周跨 Q1/Q2，前端默认列所在季 KR 即可）。
- **FR-06 前端「目标」视图**：/worklog 页 Segmented 增「目标」——年份导航 + 年度目标区 +
  Q1~Q4 四个季度区（每区 O→KR 嵌套展示：O 行带状态/进度，KR 行带进度条、状态、
  **派生建议进度**徽标、关联计划项数）；目标编辑抽屉（kind 感知：选 KR 必选中父 O，
  选 QUARTER O 可选挂年度 O）；KR 行「滚转到下一季」入口。周计划抽屉（PlanItemFormDrawer）
  增「关联 KR」下拉（`GET /goals/linkable-krs?date=` 列该周所在季的 KR，带所属 O 标题）；
  周计划列表增 KR 列（krTitles 映射渲染 Tag）。
- **FR-07 派生进度（只展示不回写）**：tree 视图每个 KR 附派生数据——关联周计划项
  总数/完成数（DONE / 非 DROPPED·CARRIED）、关联 DONE 条目工时合计、建议进度
  （done/total×100，无关联项则 null 不展示）。progress 列仍人工维护（「自动草稿 +
  人工确认」哲学在目标域的同构：派生值是建议，落库是人工）。

### M2 季度复盘投影

- **FR-08 季度复盘报告**：`worklog_quarterly_reports` 表（user_id + quarter + content_md +
  session_id，(user_id, quarter) 唯一）。手动空白稿/编辑保存/查询端点同日报周报形态
  （按 period 寻址）。
- **FR-09 AI 生成季度复盘**：新场景 `worklog-quarterly-review`（种子照既有三场景范式，
  产物 `.devmind/output/quarterly-review-<period>.md`）。素材 = **OKR 对照块**（确定性
  渲染：当季 O→KR 树 + 每项状态/人工进度/派生进度 + 关联周计划项逐项真实状态与工时）+
  **计划外工作聚合**（季内 DONE 条目中未对齐任何 KR 的，按类型聚合条数与工时 + 代表性
  标题抽样）+ 可选 hint；prompt 固定结构（不走用户模板——季度复盘首版无模板定制诉求）。
  落库走 WorklogOutputMirror 消费会话回传（新文件契约 quarterly-review-<period>.md），
  防覆盖语义同日报（非 force 已有行跳过，force 显式覆盖）。
- **FR-10 「从数据重建」预览**：`GET /quarterly/review-preview?period=` 服务端把 OKR 对照块
  确定性渲染为 markdown（不经 LLM）返回；复盘编辑器旁按钮拉取填入（人工保存才落库，
  同 CAP-90 FR-10 哲学）。前端目标视图每个季度区头部有「季度复盘」入口（生成/查看/编辑）。

### M3 智能对齐

- **FR-11 suggest 注入当季 KR**：`worklog-plan-suggest` 素材增「当季 KR 清单」块
  （`- KR#<id> [O标题] KR标题（状态 · 人工进度）`）；输出契约扩一行可选标注
  `（KR#<id>）`；PlanSuggestParser 解析该标注（不合规静默丢弃），insertSuggestions
  校验 krId 属于本人当季 KR（不属于则置空不落，不炸整批）。场景骨架/prompt/解析器
  三方同步（存量库骨架不覆盖——KR 标注是可选项，旧骨架照常工作，见 §10 R3）。

## 3. 否决方案

- **OKR 用标签/自由文本挂在计划项上**：否决——重蹈 `next_plan_md` 覆辙（CAP-88 §1）：
  文本不可结构化，季末达成度又回到「LLM 猜/人工回忆」；目标两级恰好是本能力最需要的
  结构化形态（状态/进度/关联原生可查）。
- **条目直挂 kr_id**：否决——过度关联。条目经 `plan_item_id → 周计划项 → KR` 已可传递
  对齐；日粒度直挂会让「今日清单」录入负担回升（CAP-90 推翻双重记录的同一理由）。
- **KR 进度全自动（按关联计划项完成率回写）**：否决——关联只证明动过（CAP-88 §3 同构）；
  完成率不含「这项 KR 的达成质量」。派生值只展示作建议，progress 落库必须人工。
- **年度目标单独建表**：否决——年/季同为低频目标实体（一年录一次、一季录一次），
  字段完全同构，一张表 + scope 判别即可；季度 O 挂年度 O 用 parent_id 自引用表达。
- **与 CAP-13 需求主线合一**：否决——OKR 是个人域（按人隔离），需求是项目域；保持
  弱关联（计划项可同时挂 kr_id 与 requirement_id，各管各的）。
- **季度复盘走用户模板（{{placeholders}}）**：首版否决——日/周报模板化源于高频个性化
  诉求；季度复盘一年四次，固定 prompt 结构 + hint 足够，有定制诉求再立演进。

## 4. 架构与模块归属

- 后端：`devmind-worklog` 模块内——`WorklogGoalEntity/Repository/WorklogGoalService/
  WorklogGoalController`（目标 CRUD/树/滚转/linkable-krs）；`WorklogPlanService` 增
  kr_id 校验与复制；`WorklogQuarterlyReportEntity/Repository` + `ReportService` 增
  季度复盘生成/预览/CRUD + `WorklogOutputMirror` 增 quarterly-review 消费 +
  `WorklogAssetSeeder`/`WorklogScenarios` 增场景种子与常量 + `ReportPrompts` 增
  OKR 对照块/计划外聚合/季度复盘 prompt/KR 清单素材。无 runner/协议/SPI 变更。
- 前端：`features/worklog` 增 `GoalView`/`GoalFormDrawer`（自包含）；
  `WorklogViewSwitch` 增「目标」页签；`PlanItemFormDrawer` 增关联 KR 下拉；
  `PlanView` 增 KR 列；季度复盘编辑复用 `ReportEditor`。
- 依赖：CAP-88/90（周计划与复盘主干）、CAP-41（生成链路）、CAP-28（个人域口径）；
  无新依赖。

## 5. 数据模型

```sql
worklog_goals(
  id PK,                          -- Long 自增（平台惯例）
  user_id varchar(64) not null,
  scope varchar(8) not null,      -- YEAR / QUARTER
  period varchar(8) not null,     -- '2026' / '2026-Q1'（格式服务端校验）
  kind varchar(16) not null,      -- OBJECTIVE / KEY_RESULT
  parent_id bigint?,              -- KR→同周期 O（必填）；QUARTER O→同年 YEAR O（可选）；YEAR O 必空
  title varchar(256) not null,
  content clob?,
  weight int?,                    -- 权重%（可空）
  progress int not null default 0,  -- 人工进度 0-100
  status varchar(16) not null default 'ACTIVE',  -- ACTIVE/DONE/DROPPED/CARRIED（@ColumnDefault 带引号）
  carried_from_id bigint?,        -- 跨季滚转来源（自引用，拖延链可追溯）
  sort_order int not null default 0,
  created_at, updated_at)

worklog_plan_items ── + kr_id bigint?   -- 关联 KEY_RESULT；删 KR 时置空

worklog_quarterly_reports(
  id PK, user_id varchar(64) not null,
  quarter varchar(8) not null,    -- '2026-Q1'
  content_md clob, session_id varchar(64)?,
  created_at, updated_at,
  unique(user_id, quarter))
```

- 新表新列走 ddl-auto 自动演进；无存量手工动作。
- content 等 @Lob 必带 @JdbcTypeCode(LONGVARCHAR) + length（红线）。

## 6. API 概要

```
GET    /api/worklog/goals/tree?year=2026        整年目标树（年度 O + Q1~Q4 O→KR 嵌套，KR 附派生数据）
POST   /api/worklog/goals                       新增 {scope, period, kind, parentId?, title, content?, weight?}
PUT    /api/worklog/goals/{id}                  编辑/改状态/改进度 {..., status?, progress?}
DELETE /api/worklog/goals/{id}                  删除（O 有子项 400；删 KR 清计划项 kr_id）
POST   /api/worklog/goals/carry {toPeriod, ids[], krParentMap?}   KR/O 跨周期滚转（幂等）
GET    /api/worklog/goals/linkable-krs?date=    该日所在季的可关联 KR（计划项抽屉下拉）

计划项 POST/PUT 增可选 krId（KEY_RESULT 校验）；计划列表响应附 krTitles 映射

GET    /api/worklog/quarterly?period=2026-Q1          复盘查询（无则 200 null 同日报口径）
POST   /api/worklog/quarterly {period}                手动空白稿（幂等）
POST   /api/worklog/quarterly/generate {period, force?, hint?}   AI 生成（异步受理 ReportLaunch）
PUT    /api/worklog/quarterly/{period} {contentMd}    编辑保存
GET    /api/worklog/quarterly/review-preview?period=  OKR 对照确定性渲染 {markdown}（不经 LLM）
```

全部端点仅本人数据（owner 强制，越权 404，同条目/计划项口径）；generate 走既有 worklog
会话生成链路（异步、节点离线 409 口径同日报生成）。

## 7. 安全

- 个人域 owner 强制不变（目标/复盘与条目同口径）。
- 关联字段服务端校验归属：kr_id 必须是本人 KEY_RESULT；parent_id 必须是本人目标且满足
  层级规则；krParentMap 目标 O 必须是本人 toPeriod 的 OBJECTIVE。
- 复盘生成会话沿用 worklog 场景只读权限口径；review-preview 确定性渲染无 LLM、无注入面。
- 目标/复盘内容属绩效敏感信息，不进入任何跨用户聚合（无团队视图，本能力不做）。

## 8. 里程碑

- **M1 目标实体与对齐**：FR-01~FR-07（goals 表 + CRUD/树/滚转 + 计划项 kr_id + 前端
  「目标」视图与计划抽屉 KR 下拉 + 派生进度展示）。
- **M2 季度复盘投影**：FR-08~FR-10（quarterly_reports 表 + 生成场景与回传落库 +
  OKR 对照素材 + review-preview 重建按钮 + 前端复盘编辑入口）。
- **M3 智能对齐**：FR-11（suggest 注入当季 KR + 产物 KR 标注解析落库）。

## 9. 验收标准

1. 目标 CRUD：年 O/季 O/KR 新增落库，层级校验生效（KR 无父 400、父跨周期 400、YEAR O
   带父 400）；编辑/改进度/改状态生效；他人目标 404；删有子项的 O 400；删 KR 后关联
   计划项 kr_id 置空；
2. 滚转：KR 随父 O 同批滚转 → 副本挂副本；单独滚转 KR 无 krParentMap → 400；目标周期
   副本 status=ACTIVE 带 carried_from_id，原项 CARRIED；重复滚转幂等；
3. 计划对齐：计划项挂 krId 落库（非 KR/他人 KR 400）；滚转复制 kr_id；计划列表附
   krTitles；linkable-krs 只返回该日所在季 KR；
4. 派生进度：KR 关联 2 计划项 1 DONE → suggestedProgress=50、linkedHours 只计 DONE 条目；
   无关联项 suggestedProgress 为 null；progress 人工改动不被派生值覆盖；
5. 季度复盘：generate 起会话、回传 quarterly-review-<period>.md 落库；非 force 已有行
   reused 跳过；prompt 素材含 OKR 对照块与计划外聚合；review-preview 渲染与库中数据一致；
6. M3：suggest 素材含当季 KR 清单；产物带 `（KR#id）` 标注的建议项落库带 kr_id，非法
   id 置空不炸批；无 KR 时素材块降级提示、生成行为同现状；
7. 全量 `mvn test` 绿；E2E（tests/ 沉淀）覆盖目标 CRUD/层级校验/滚转/计划对齐/派生进度/
   复盘 CRUD 与预览/素材口径。

## 10. 风险与开放问题

| # | 事项 | 口径 |
|---|------|------|
| R1 | 目标录入是额外负担，低频实体可能沦为僵尸数据 | 缓解：CRUD 极简（年一次季一次）；价值锚在季末复盘不用回忆；suggest 注入让 KR 自然进入周计划视野 |
| R2 | 周跨季时 kr_id 无周期强校验，可能挂到「错季」KR | 接受：周天然跨季（2026-03-30 周跨 Q1/Q2），强校验误伤正常场景；复盘聚合按计划项日期归属季度 |
| R3 | suggest 场景骨架更新只对新库生效（种子不覆盖存量） | 接受：KR 标注是可选项，旧骨架产物照常解析（无标注=不挂 KR）；存量库要新契约可在场景管理页手改 |
| R4 | 共享库多实例部署下无迁移任务（本能力纯新增表/列） | 无风险：ddl-auto 幂等加表加列 |
| O1 | 年度复盘（聚合四季复盘 + 年度目标达成） | 开放：素材同构（年 O + 四季 O/KR + 全年工时聚合），视 M2 使用反馈再立演进 |
| O2 | O 的进度按 KR 加权自动汇总 | 开放：首版 O 进度纯人工；KR 权重已有 weight 列，有诉求再加派生汇总 |
| O3 | 目标域与 CAP-13 需求主线的双向可见（KR 下看需求进展） | 开放：保持弱关联现状；真出现「以 KR 视角看需求」诉求再评估 |
| O4 | 团队/上级视角的目标可见性 | 开放：本能力严格个人域；团队 OKR 管理是另一个能力（需组织模型），不在此立 |
