# CAP-28 个人工作日志与工时管理（git log + 手动补录 → AI 日报/周报）

> 能力 ID：CAP-28 ｜ 分类：组装层 ｜ 状态：**已实现（MVP，FR-07 Jira 一键操作后置）** ｜ 日期：2026-09-05
> 注：仓库登记与扫描源已由 CAP-29 重构——`git_repositories` 归 devmind-project 全局管理（服务端克隆），本能力只保留订阅与扫描消费。

## 1. 目的

把"这周干了啥、下周要干啥、工时多少"从每周一的手工回忆负担，变成
**自动草稿 + 人工确认**：

- **工作条目**：每天多条（开发/支持/会议…），各记工时；git 提交可一键扫描导入，
  非开发类工作手动补录；
- **AI 日报**：每天定时（或手动）把当天 git log + 手动条目交给 headless claude
  一次性会话生成当日工作日志草稿，用户编辑确认；
- **AI 周报**：每周一自动生成上周总结 + 下周计划草稿，站内通知提醒；
- **Jira 智能操作**：条目可一键登记工时到 Jira / 未关联条目一键创建 Jira 任务。

个人级能力：每用户一份数据，互不干扰。

```
git_repositories（全局登记）── worklog_repo_subscriptions（用户勾选）
        │ GitLogScanner（author 过滤 = GitIdentityProvider 署名邮箱）
        ▼
worklog_entries（GIT/MANUAL 条目，含工时 minutes）
        │ ReportService ── OneShotAgentRunner（CAP-05 headless claude 裸会话）
        ▼
daily_reports ──► weekly_reports（上周总结 + 下周计划）
        │ DomainEventPublisher（worklog.daily.generated / worklog.weekly.generated）
        ▼
CAP-06 通知中心（站内提醒）
```

## 2. 功能需求

- **FR-01 全局代码仓库登记**：平台级 `git_repositories` 表（不挂项目，区别于
  CAP-02 的项目级多库模型）；登记写操作仅 ADMIN；登记时 `git rev-parse` 校验
  本地路径确为 git 仓库；字段含 local_path(unique)/remote_url/default_branch/status。
- **FR-02 用户参与勾选**：`worklog_repo_subscriptions`（user_id+repo_id 唯一），
  每用户勾选自己参与的仓库子集，git 扫描只覆盖勾选仓库。
- **FR-03 工作条目 CRUD**：`worklog_entries` = 日期 + 标题 + 类型
  （DEV/SUPPORT/MEETING/RESEARCH/OTHER）+ 工时 + 来源（GIT/MANUAL/AGENT）+
  可选关联（requirement_id / jira_issue_key）；按人隔离（仅能改自己的条目，
  他人条目 404）；工时内部以分钟存储，API 暴露 hours（0.25h 步进）。
- **FR-04 Git 提交扫描导入**：扫描本人勾选仓库指定日期的 log，
  author 过滤链 = `git remote get-url origin` 取 host → CAP-24
  `GitIdentityProvider.resolveAuthor(username, host)` → `git log --author=<email>`；
  解析为空则不过滤并 warn（预览人工勾选兜底）；
  `GET /git/preview` 预览不落库 → `POST /git/import` 导入为 GIT 条目，
  按 (user_id, repo_id, commit_sha) 幂等去重。
- **FR-05 AI 日报**：cron（默认每日 18:30）或手动触发
  `POST /api/worklog/daily/generate`；汇总当日 git 扫描 + 条目 → one-shot 会话
  生成 `daily_reports` DRAFT（unique(user_id, work_date)，已存在则跳过）→
  发 `worklog.daily.generated` 事件；用户可编辑正文并置 CONFIRMED。
- **FR-06 AI 周报**：cron（默认周一 09:00）或手动触发；输入 = 上周日报集合，
  生成 `weekly_reports`（summary_md + next_plan_md，unique(user_id, week_start)）；
  发 `worklog.weekly.generated` 事件 → 通知中心站内提醒。
- **FR-07 Jira 一键操作（MVP 后）**：条目按 jira_issue_key 直接 logWork
  （minutes→seconds）；未关联条目一键 createIssue 并回写 key。
  需 `IntegrationConnector` 新增 default `createIssue`。
- **FR-08 前端页面**：`features/worklog` 自包含——工作日志页
  （Card + Segmented[条目|日报|周报]）+ 代码仓库页（勾选 + ADMIN 管理）；
  裸路由 `/worklog`、`/worklog/repos`（不挂项目上下文）。

## 3. 关键设计

- **one-shot AI 走 SPI 解耦**：devmind-common 定义
  `com.devmind.common.agent.OneShotAgentRunner.run(prompt, timeoutSeconds)`，
  devmind-session 实现（无 projectId 裸会话 → create 后立即 finish 关 stdin →
  轮询至 DONE 取 `SessionEntity.summary`，超时 kill）；worklog 以
  `ObjectProvider` 探测注入，session 缺席时报告生成降级（手动条目仍可用）。
- **只读权限模式**：one-shot 总结是纯文本任务，专用配置
  `devmind.session.oneshot-permission-mode=plan`（不复用全局 acceptEdits），
  prompt 显式声明"不读写任何文件，只输出 Markdown 正文"。
- **调度照 CAP-19 模板**：独立 `WorklogSchedulingConfig`（@EnableScheduling 不侵
  启动类）+ cron 配置化 + 全局 AtomicBoolean 防重入 + 手动/定时共用核心方法；
  调度方法禁 @Transactional，逐用户逐报告 save 即时提交；
  调度线程无 SecurityContext，归属一律显式传 username。
- **分钟存储**：minutes int 列（0.25h=15），避免浮点；前端 InputNumber
  min 0.25 step 0.25（与 CAP-27 FR-04 口径一致）。
- **git 扫描编码安全**：命令显式 `-c i18n.logOutputEncoding=UTF-8
  --encoding=UTF-8`，进程 stdout 按 UTF-8 字节解码（照 WorktreeManager.run
  范式）；format 用 `%x1f` 字段分隔 + `%x1e` 记录分隔。
- **通知限制（MVP 接受）**：通知实体为广播制无接收人字段，多用户部署下
  日报/周报提醒全员可见（summary 带用户名区分）；按用户投递留作
  notification 模块后续演进。

## 4. 依赖关系

- 依赖：CAP-01（currentActor）、CAP-05（one-shot 会话执行）、
  CAP-06（事件转通知）、CAP-24（git 署名解析过滤 author）；
  FR-07 另依赖 CAP-19/27（Jira logWork 通道、createIssue 扩展点）。
- 被依赖：无。

## 5. 数据模型

```sql
git_repositories(            -- 全局登记，全员共享；写操作仅 ADMIN
  id PK, name, local_path unique, remote_url?, default_branch?,
  status default 'ACTIVE', created_by, created_at, updated_at)

worklog_repo_subscriptions(  -- 用户勾选
  id PK, user_id, repo_id FK, created_at, unique(user_id, repo_id))

worklog_entries(             -- 工作条目，每天多条
  id PK, user_id, work_date, title, content clob,
  entry_type default 'DEV',  -- DEV/SUPPORT/MEETING/RESEARCH/OTHER
  minutes int,               -- 内部分钟存储，API 暴露 hours
  source,                    -- GIT/MANUAL/AGENT
  repo_id?, commit_sha?,     -- GIT 来源去重键
  requirement_id?, jira_issue_key?, created_at, updated_at)

daily_reports(               -- unique(user_id, work_date)
  id PK, user_id, work_date, content_md clob,
  status default 'DRAFT',    -- DRAFT/CONFIRMED
  session_id?, created_at, updated_at)

weekly_reports(              -- unique(user_id, week_start)
  id PK, user_id, week_start, summary_md clob, next_plan_md clob,
  status default 'DRAFT', session_id?, created_at, updated_at)

worklog_user_settings(       -- 个人开关
  id PK, user_id unique, auto_daily bool, auto_weekly bool,
  daily_minutes_target int?, updated_at)
```

## 6. API 概要

```
# 全局仓库（写操作仅 ADMIN；列表/订阅全员）
GET    /api/worklog/repos                       列表（附本人 subscribed 标记）
POST   /api/worklog/repos                       登记（git rev-parse 校验路径）
PUT    /api/worklog/repos/{id}                  改名/分支/启停
DELETE /api/worklog/repos/{id}
PUT    /api/worklog/repos/{id}/subscription     {subscribed:true|false} 本人勾选

# 工作条目（仅本人）
GET    /api/worklog/entries?from=&to=
POST   /api/worklog/entries                     手动补录
PUT    /api/worklog/entries/{id}
DELETE /api/worklog/entries/{id}
GET    /api/worklog/git/preview?date=           扫描当日 commit（不落库）
POST   /api/worklog/git/import {date}           导入为 GIT 条目（幂等去重）

# 日报 / 周报（仅本人；generate 与定时调度共用核心，幂等跳过已存在）
GET    /api/worklog/daily?date=
POST   /api/worklog/daily/generate {date}
PUT    /api/worklog/daily/{id}                  {contentMd, status:CONFIRMED}
GET    /api/worklog/weekly?weekStart=
POST   /api/worklog/weekly/generate {weekStart}
PUT    /api/worklog/weekly/{id}                 {summaryMd, nextPlanMd, status}

# Jira 智能操作（FR-07，MVP 后）
POST   /api/worklog/entries/{id}/jira/log-work
POST   /api/worklog/entries/{id}/jira/create-issue

# 个人设置
GET/PUT /api/worklog/settings                   {autoDaily, autoWeekly, dailyMinutesTarget}

# 领域事件 → 通知中心
worklog.daily.generated / worklog.weekly.generated / worklog.jira.worklogged
```

配置（application.yml `devmind.worklog`）：`daily-enabled`/`daily-cron`
（默认 `0 30 18 * * *`）、`weekly-enabled`/`weekly-cron`
（默认 `0 0 9 * * MON`）、`git-scan-max-commits`（默认 200）、
`oneshot-timeout-seconds`（默认 300）。

## 7. 验收标准

1. ADMIN 登记本机 git 仓库后，普通用户可勾选订阅；
2. 勾选仓库当日有本人提交时，preview 列出且中文提交信息不乱码，
   import 后条目落库、重复导入不产生重复条目；
3. 手动补录条目可增删改，他人条目不可见不可改（404）；
4. 手动触发日报生成 → DRAFT 落库 + 通知中心出现提醒；当日重复触发不重复生成；
5. 手动触发周报生成 → summary + next_plan 草稿落库；
6. 会话模块缺席时，条目/仓库功能正常，报告生成给出明确降级错误。

## 8. MVP 范围（暂不做）

- **FR-07 Jira 一键操作**（createIssue/logWork 按 key 直挂）——依赖
  `IntegrationConnector` 新增写端点，单独批次落地；
- 通知按用户投递（现为广播制）；
- agent 会话自动转工时条目（source=AGENT 预留枚举，后续可接
  `session.completed` 事件）；
- 工时报表/统计图表。

## 9. 排错

| 现象 | 根因 | 处理 |
|------|------|------|
| generate 提交 200 但报告一直不出现 | 生成是异步的：查 `GET /api/sessions` 最近一条无项目会话的状态；FAILED 且看服务日志 `报告生成失败:` 前缀 | 按下方分项处理 |
| one-shot 会话秒 FAILED（createdAt==finishedAt） | fake 执行器脚本损坏（fake-agent.js 语法错误 node 秒退）或 claude 不在 PATH | fake 模式下 `node` 直接跑一遍 `devmind-session/src/main/resources/session/fake-agent.js` 验证；真实模式确认 claude CLI 可用 |
| 报「节点不在线」 | one-shot 会话曾随平台默认远程节点派发；已修为 `agentNodeId="local"` 保留值强制本机 | 升级到此修复后版本；远端节点场景不要用 one-shot |
| git 预览为空 | author 过滤不匹配：扫描按「我的 Git 凭证」（CAP-24）里该 host 的署名邮箱过滤 | 在 /me/git-credentials 配置与提交一致的署名；或仓库 remoteUrl 缺失导致无法推断 host（登记时补上） |
| git 提交中文主题乱码 | Windows git 默认按本地编码输出 log | 扫描已显式带 `-c i18n.logOutputEncoding=UTF-8 --encoding=UTF-8` 且按 UTF-8 字节解码；仍乱码检查仓库本身提交编码 |
| 已确认（CONFIRMED）报告 force 重生成不生效 | 设计如此：force 仅覆盖 DRAFT；异步任务内抛 409 记 warn 日志，报告保持 CONFIRMED | 先确认无误再定稿；确需重生成需先改回草稿（当前未开放，走库操作） |
| 并发触发报 409「已有报告生成任务在跑」 | AtomicBoolean 防重入，全局同一时刻只允许一个生成任务 | 稍后重试 |
| 日报/周报定时没跑 | 检查 `devmind.worklog.daily-enabled/weekly-enabled` 与 cron；调度覆盖范围 = 有仓库订阅的用户 ∪ 有设置行的用户，显式 autoDaily=false 排除 | 在「工作日志 → 设置」确认开关；完全无订阅无设置的用户不在覆盖范围内 |

## 10. 实现落点

- 后端：`devmind-worklog`（实体 6 表 / CodeRepoService / GitLogScanner / WorklogEntryService /
  ReportService / WorklogScheduler / 5 控制器）；SPI `OneShotAgentRunner`（devmind-common 定义，
  devmind-session `SessionOneShotRunner` 实现，ObjectProvider 探测缺席降级 400）；
  `CreateSessionRequest.agentNodeId` 保留值 `local`；notification 域标签 `worklog → 工时`。
- 前端：`features/worklog`（WorklogPage 三视图 + CodeReposPage + EntryFormDrawer /
  GitImportModal / ReportEditor），裸路由 `/worklog`、`/worklog/repos`（不进项目上下文），
  侧边栏「个人」组。
