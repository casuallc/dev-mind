# CAP-88 工作日志计划闭环（日/周计划项 + 执行关联 + 数据驱动复盘）

> 能力 ID：CAP-88 ｜ 分类：组装层 ｜ 状态：设计定稿 ｜ 日期：2026-10-26
> 决策记录：计划粒度定稿**日计划 + 周计划双层**（日计划管当天执行对照，周计划管方向与
> 周间滚转，两层经条目关联打通）；计划项**存仓内文件不回建 DB 表**——CAP-74 已把
> 条目/报告事实源收敛为服务端 git 仓，计划沿用同一契约（marker 注释定界），平台外编辑、
> 写后自动推送、git 历史审计全部白嫖；「AI 建议计划」纳入 M3，不阻塞 M1/M2 闭环先行。

## 1. 目的

工作日志现状是单向的「记录 → 回顾」链路：条目（git 导入/手动补录）记已发生的事，
日报/周报由 AI 对素材做总结。唯一的"计划"是周报里的 `next_plan_md` 自由文本——
不落结构、没有状态、不挂执行：

- 「上周计划跟进」（CAP-41 2026-10-23 增补）靠 LLM 解析上周文本逐条猜对应关系，
  既不可靠也无法在日常执行中对照；
- 计划没有生命周期：做完没做完、拖了几周，文本里都看不出来；
- 每天的执行（条目）与每周的方向（计划文本）之间没有连接点。

本能力把**计划升格为一等数据**（仓内计划文件 + 结构化计划项），补齐闭环：

```
计划（plans/daily/yyyy-MM-dd.md、plans/weekly/yyyy-Www.md，计划项有状态）
   │ 执行时关联（条目 marker 增 plan=<id> 属性）
   ▼
条目（CAP-74 §10 既有契约，事实源在仓）
   │ 生成时注入「计划 vs 实际」结构化对照
   ▼
日报：今日计划逐条有着落 + 计划外工作；周报：「上周计划跟进」由真实状态驱动
   │ 周期切换
   ▼
未完成项一键滚转次日/下周（CARRIED + carried 链，反复拖延可追溯）
```

## 2. 功能需求

### M1 计划项与执行关联

- **FR-01 计划文件契约**：仓内 `<subdir>/plans/daily/yyyy-MM-dd.md`（日计划）与
  `<subdir>/plans/weekly/yyyy-Www.md`（周计划，周文件名口径同周报）。计划项格式
  沿用 CAP-74 §10 条目同款 marker 注释定界：
  `<!-- devmind:plan id=<32hex> type=DEV status=TODO est=120 source=MANUAL req=.. jira=.. carried=.. -->`
  + `## 标题` + 正文；preamble（首个注释前内容）与未知 `k=v` 属性逐字节/原序无损
  往返，平台外手改不丢。属性口径：`type` 与条目同枚举（DEV/SUPPORT/MEETING/
  RESEARCH/OTHER）；`est`=预估分钟（0.25h 步进口径）；`req`/`jira` 关联沿用条目
  口径；`carried`=滚转来源项 id。
- **FR-02 计划 CRUD**：按 (scope, date/weekStart) 读计划文件解析视图；新增/编辑/
  删除/改状态一律经 `WorklogRepoService` 单写者收口写文件 + commit
  （trailer `DevMind-Source: manual`）；**写后自动推送白嫖 CAP-74 FR-06**——仓写
  收口不变，绑定远端的仓计划变更随写即推，零新增同步链路。
- **FR-03 状态机**：`TODO / DOING / DONE / DROPPED / CARRIED`。状态变更 = 重写
  marker 属性 + commit。自动只到 DOING：条目关联计划项（FR-04）且该项为 TODO 时
  服务端置 DOING（关联只证明动过，不证明做完）；DONE/DROPPED 必须人工。状态
  历史走 git log，平台不自建。
- **FR-04 条目关联计划**：条目新增/编辑 API 增可选 `planId`；条目 marker 增
  `plan=<id>` 属性（单关联；一项执行对应一个计划项）。git 扫描导入链路不动，
  导入后人工关联。删计划项时关联条目保留 marker 原值（悬空 id 渲染为「已删计划」，
  不级联改条目文件）。
- **FR-05 滚转**：打开某日/周计划视图时检测上一周期未完成项（TODO/DOING），提示
  「上周期 N 项未完成」一键滚转：目标文件追加各项（新 id、`status=TODO`、
  `carried=<原id>`），原项状态改 `CARRIED`——同一仓一次 commit。`carried` 链使
  「一项拖了三周」可回溯；重复滚转幂等（原项已 CARRIED 即跳过）。
- **FR-06 前端「计划」视图**：/worklog 页 Segmented 增「计划」（内层日/周切换 +
  日期/周导航，布局沿用内容区约定）：计划项列表（状态徽标 + 下拉改状态 + 预估
  工时 + 关联需求/Jira 展示）、新增/编辑抽屉、滚转提示条；条目编辑抽屉增「关联
  计划项」下拉（当日日计划 + 当周周计划，DOING/DONE 项也可选——补录昨天忘挂的
  执行）；条目视图当日分组顶部显示计划完成度（如 3/5）。

### M2 数据驱动复盘

- **FR-07 日报生成注入计划对照**：`ReportService` 素材构建读当日计划文件 +
  条目 marker `plan` 关联，prompt 增「今日计划对照」块（每项：标题/状态/关联条目
  摘要）；内置日报模板要求「今日重点」逐条呼应计划、无关联条目的工作归「其他
  进展/项目支持」。`{{entries}}`/`{{commits}}` 占位不变。
- **FR-08 周报「上周计划跟进」改数据驱动**：`{{lastWeekPlan}}` 内容由「上周周报
  文本解析的下周计划」升级为「上周计划文件的计划项 + 真实状态 + 关联条目」结构
  化清单（占位符名不变，自定义模板兼容）；模板要求逐条标注「完成/部分完成/未完成」
  直接以 marker 状态为准叙述，不再让模型从文本猜对应。上周无计划文件时仍渲染
  「无上周计划：省略该块，不得编造」。本周计划项（若已排）一并注入，供「下周计划」
  节呼应——下周计划文本仍是汇报形态（自由文本），计划项才是结构载体，两者不互写。
- **FR-09 存量兼容**：无计划文件的历史日期/周，生成行为与现状完全一致（对照块
  省略）；`splitWeekly` 分节解析、`{{lastWeekPlan}}` 语义不变只升级内容来源。

### M3 AI 建议计划

- **FR-10 「建议下周计划」**：计划视图手动触发 → 走既有 worklog 会话生成链路
  （场景新增 `worklog-plan-suggest` 种子），素材 = 上周未完成/滚转项 + 本周在办
  需求（ObjectProvider 探测 CAP-13 SPI，缺席降级只用未完成项）+ 可选 hint；
  claude 产 `.devmind/output/plan-suggest-yyyy-Www.md` → 服务端解析为建议项写
  下周计划文件，`source=AI_SUGGEST`、`status=TODO`。前端建议项带「AI」徽标，
  逐条编辑/删除即人工确认（同日报「自动草稿 + 人工确认」哲学，无独立接受态）。
  幂等：目标周已存在计划项时跳过生成（force 显式重生成只追加不覆盖已有项——
  已有项是人排过的事实）。
- **FR-11 周期末提醒（开放，可后置）**：周计划仍有未完成项时周五发站内提醒
  （CAP-06 既有广播制），引导滚转或标 DROPPED。

## 3. 否决方案

- **回建 DB 表（worklog_plan_items）**：否决。CAP-74 刚把条目/报告从 DB 迁仓
  （「仓 = 事实源、禁回建 DB 镜像/落盘索引」），计划回建 DB 表是开倒车；计划项
  量级与条目相同（一人一年数百小数据），marker 解析器现成可抽共用；文件形态还
  直接继承平台外编辑、自动推送、git 历史审计三项红利。DB 唯一诱惑是状态字段的
  结构化查询，而该量级下「读文件 + 解析」毫无压力。
- **计划并入条目文件（PLANNED 标志位）**：否决。条目 = 已发生事实（日期 = 发生
  日），计划 = 面向未来且跨周期滚转，生命周期根本不同；混进 `entries/` 会让
  「当日清单」语义分裂（今天记的事 vs 今天打算做的事），生成素材口径也要跟着
  分叉。
- **状态全自动（关联条目即 DONE）**：否决。关联只证明动过；自动只到 DOING，
  DONE 必须人工（M3 可让 AI 给建议值，但落库仍人工）。
- **周计划项取代周报 `next_plan_md` 文本**：否决。周报是给上级/团队看的汇报
  形态（叙述性文本），计划项是给自己跟踪的结构载体；强行合一要么汇报变干巴巴
  清单，要么跟踪失去结构。两者各司其职、不互写（漂移风险见 §10）。

## 4. 架构与模块归属

- 后端：`devmind-worklog` 模块内——`PlanService`（CRUD/状态/滚转/关联联动）+
  marker 解析器从 CAP-74 §10 条目解析抽共用（条目/计划同构：注释定界 + 标题 +
  正文 + 无损往返）。写路径全走 `WorklogRepoService` 单写者收口，仓锁/commit/
  自动推送零新增。
- 前端：`features/worklog` 增 `PlanView`（自包含）；`WorklogViewSwitch` 增「计划」
  页签；条目抽屉（EntryFormDrawer）增关联下拉。
- 无新 SPI、无 runner/协议变更、无新表。M3 在办需求素材经 ObjectProvider 探测
  CAP-13 SPI（缺席降级，同 one-shot 探测先例）。
- 依赖：CAP-74（仓设施/单写者/自动推送/条目契约）、CAP-41（生成链路/模板）、
  CAP-28（设置与调度骨架）；M3 另依赖 CAP-13（在办需求）、CAP-06（提醒，FR-11）。

## 5. 数据模型

无新表、无存量表变更（ddl-auto 不动）。全部为仓内文件契约：

```
<subdir>/plans/daily/yyyy-MM-dd.md      -- 日计划（文件名编码日期，同 daily/ 口径）
<subdir>/plans/weekly/yyyy-Www.md       -- 周计划（周文件名口径同 weekly/）

文件内计划项：
<!-- devmind:plan id=<32hex> type=DEV status=TODO est=120 source=MANUAL
     req=<requirementId> jira=<key> carried=<来源项id> -->
## 计划项标题
正文（可选，目标/验收口径）

条目 marker 增可选属性：plan=<计划项id>（单关联，悬空 id 容忍）
```

`status` ∈ TODO/DOING/DONE/DROPPED/CARRIED；`source` ∈ MANUAL/AI_SUGGEST（M3）；
`est` 分钟 int（0.25h=15）；id 生成与条目同款 uuid 32hex。

## 6. API 概要

```
GET    /api/worklog/plans?scope=DAY|WEEK&date=|weekStart=   计划文件解析视图（含各项状态）
POST   /api/worklog/plans/items              新增 {scope, date|weekStart, title, content?, type, est?, req?, jira?}
PUT    /api/worklog/plans/items/{date}/{id}  编辑/改状态（date 定位文件免全目录扫，寻址同条目删除先例；
                                             周项用 weekStart 同位）
DELETE /api/worklog/plans/items/{date}/{id}
POST   /api/worklog/plans/carry {scope, to, ids[]}          滚转到目标日期/周（幂等）
GET    /api/worklog/plans/linkable?date=                    条目抽屉可关联项（当日日计划+当周周计划）
POST   /api/worklog/plans/suggest {weekStart, hint?}        M3 AI 建议（异步 202 + sessionId，幂等跳过）

条目 POST/PUT 增可选 planId（写 marker plan 属性 + 触发 TODO→DOING 联动）
```

写端点全部经仓锁单写者；commit message `worklog: plan daily 2026-10-26` /
`worklog: plan weekly 2026-W44` + `DevMind-Source: manual|ai` trailer。

## 7. 安全

- 个人域数据 owner 强制沿用 CAP-62 既有口径：计划读写只落操作人活动仓的本人
  subdir，越权访问 404（同条目/报告）。
- 计划文件路径由服务端按 (仓, 用户名, scope, date) 派生，客户端不可控路径；
  日期/周格式服务端强校验（防 `../` 注入，同条目删除寻址先例）。
- AI_SUGGEST 项只是文件里的草稿行，不触发任何自动执行；FR-10 生成会话沿用
  worklog 场景只读权限口径。

## 8. 里程碑

- **M1 闭环骨架**：计划文件契约 + marker 解析抽共用 + CRUD/状态/滚转 API + 条目
  关联（planId + TODO→DOING 联动）+ 前端「计划」视图与条目抽屉关联下拉。
- **M2 复盘确定性**：日报计划对照注入 + 周报「上周计划跟进」改数据驱动 + 内置
  模板更新（存量自定义模板兼容）。
- **M3 智能建议**：`worklog-plan-suggest` 场景种子 + suggest 链路（CAP-13 探测
  降级）+ 前端建议项交互；（FR-11 周期末提醒视打扰度评估后定）。

## 9. 验收标准

1. 新增日计划项后：仓内出现 `plans/daily/yyyy-MM-dd.md` 且 commit 带
   `DevMind-Source: manual`；绑定远端的仓无需手动推送即远端可见（白嫖 CAP-74
   FR-06）；平台外手改计划文件后平台读路径正常解析、未知属性无损往返；
2. 条目关联计划项：条目 marker 出现 `plan=<id>`，关联项 TODO 自动变 DOING；
   删计划项后关联条目正常渲染「已删计划」不报错；
3. 状态流转：DOING→DONE/DROPPED 人工改成功，git log 可见每次变更；TODO 不可
   被任何自动路径直接置 DONE；
4. 滚转：昨日 2 项未完成 → 今日计划视图提示并可一键滚转，原项 CARRIED、新项
   带 `carried` 链；重复滚转幂等；跨周滚转同样成立；
5. 日报生成：prompt 素材含当日计划对照块，成稿「今日重点」呼应计划项、计划外
   工作归「其他进展/项目支持」；无计划文件的历史日期生成行为与现状一致；
6. 周报生成：上周有计划文件时「上周计划跟进」逐条对应且状态与 marker 一致
   （数据驱动，非模型猜）；上周无计划文件时该块省略不编造；
7. M3：触发「建议下周计划」→ 建议项以 AI_SUGGEST 落下周计划文件、前端带徽标，
   可逐条删改；目标周已有项时跳过生成；CAP-13 缺席时仅用未完成项素材正常生成；
8. 越权：改他人计划项 404；路径注入（`date=../../x`）400。

## 10. 风险与开放问题

- **计划项与周报 `next_plan_md` 双写漂移**：口径已定（各写各的、不互写），但用户
  可能困惑「周报里写的下周计划怎么没进计划视图」。开放问题：周报生成后是否提供
  「把下周计划文本转为计划项」入口（解析自由文本不可靠，倾向 AI_SUGGEST 链路
  顺带覆盖该场景——下周计划以建议项先行、周报文本引用之）。M3 实施时再定。
- **团队仓下计划对他人可见**：仓共享语义下同仓成员可读彼此 subdir（同日报/周报），
  符合「工作日志可共享」定位，不做计划专属权限；有私密诉求用个人仓。
- **滚转链噪音**：一项反复滚转会产生长 `carried` 链与多个 CARRIED 行。M1 先不
  做折叠，视实际使用加「链式合并展示」。
- **FR-11 提醒打扰度**：广播制通知下多人部署会互相看到提醒，评估后再定或等
  通知按人投递演进。
- **est 预估与实际工时对照**：条目有 minutes、计划有 est，M2 后可加「预估 vs
  实际」偏差展示；首版只存不比。
