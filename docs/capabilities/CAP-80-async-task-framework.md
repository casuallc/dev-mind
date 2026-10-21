# CAP-80 通用异步任务框架（Async Task Framework）

> 能力 ID：CAP-80 ｜ 分类：底座 ｜ 状态：设计定稿 ｜ 日期：2026-10-21
> 决策记录：**独立新模块 devmind-task**（积木式底座，依赖 devmind-execution 复用日志枢纽），
> 不并入 devmind-execution——CAP-12 是「步骤链执行原语」层，本能力是「任务登记/调度/重试」层，
> 职责正交；**不迁移四大执行器**（build/deploy/test/release 保留自有记录表与状态机，CAP-12
> 迁移约定不变），框架面向「无自有执行记录语义的通用异步/定时任务」；调度采用
> **主 tick + next_run_at 到期判定 + 乐观认领**（参照 CAP-28 WorklogScheduler 现有范例），
> 单实例部署前提下不引分布式调度框架；**认领即置 next_run_at=NULL**（评审定稿：
> 在跑任务不被 tick 重扫、规避时间戳精度等值比较、status 条件堵 PAUSED 穿透），
> 终态按形态重算回填；**dedup_key + ensureScheduled 幂等登记**（评审定稿：存量
> @Scheduled 迁移的启动期登记不产生重复任务）；**重启即收割在途 RUNNING**
> （不等 timeout×2，消除恢复盲窗）。

## 1. 目的

现状是「同步执行底座（CAP-12）+ 各模块私有虚拟线程池 + 自有记录表 + 自有僵尸收割
@Scheduled」的多份同构拷贝：build/deploy/test/release 四份、finetune/eval/repo-clone 等
若干份；全仓库 15+ 处散点 @Scheduled（清理/轮询/看门狗），无通用重试、无执行次数控制、
无统一任务视图、无统一调度入口。本能力抽出**统一异步任务层**：

- **一个入口**：业务方只实现 `AsyncTaskHandler`（按 task_type 注册为 Spring bean），
  提交（立即/延迟/定时）走 `AsyncTaskService` 统一 API，不再关心线程、调度与收割。
- **策略可配**：每个任务可设重试次数与退避策略、超时、执行次数上限、
  并发重叠策略（SKIP/QUEUE）、调度形态（立即/延迟/固定间隔/cron）。
- **过程可视**：每次执行（run）独立记录，实时日志走 CAP-12 日志枢纽 + WS，
  终态持久化；后台管理页统一查看任务与执行历史。
- **崩溃可恢复**：任务与调度状态全部落库，服务重启后到期任务自动续跑，
  僵尸 run 收割后按重试策略重排或判失败。

## 2. 功能需求

### M1 任务内核（表模型 + Handler SPI + 执行 + 重试 + 日志）

- **FR-01 Handler SPI**：`AsyncTaskHandler`（devmind-task api 包）：
  `String type()` + `void execute(String payload, TaskContext ctx)`；
  `TaskContext` 提供 `log(String)`（实时推 WS + 攒入 run 日志）、
  `runId/taskId/runSeq/attempt` 与进度回写。各业务模块以实现类 Spring bean 注册，
  框架按 type 分发；未知 type 提交即 400；启动期同 type 注册多个 bean 即
  fail-fast 报冲突。handler 契约：`execute` 实现须响应线程中断（或周期检查
  `ctx.isCancelled()`），长阻塞点不响应中断时该 run 只能等超时收割（FR-09）。
- **FR-02 任务与执行记录落库**：任务登记与调度配置一体落 `async_tasks`；
  每次执行（含重试的每次 attempt）落 `async_task_runs` 一行。payload 在创建时
  固化为 JSON 快照，后续业务配置变更不影响在途任务。列名避让 H2/PG 保留字，
  CLOB 列必带 `@JdbcTypeCode`（见 §5 数据模型）。
- **FR-03 执行内核**：框架自持虚拟线程池（`newVirtualThreadPerTaskExecutor`，
  @PreDestroy 关闭）；认领即更新 run=RUNNING + started_at，执行 handler，
  捕获全部异常转 FAILED（堆栈摘要入 error）；单 run 超时（timeout_ms，默认 30 分钟）
  中断并判 FAILED（可重试）。中断是**协作式**的：handler 不响应中断时线程继续
  存活，须靠 FR-09 收割兜底；为防收割器与迟到的 handler 双写同一 run，run 的
  一切终态流转（成功/失败/取消/收割）都带 `WHERE status IN ('QUEUED','RUNNING')`
  乐观条件，后到者丢弃。**触发/认领方法禁 @Transactional**，靠 save 自身事务
  即时提交（沿用执行器红线）。
- **FR-04 重试策略**：run 失败后 `attempt < retry_max_attempts` 时按退避策略计算
  下次执行时刻重排新 attempt 的 run：FIXED=固定 `retry_interval_ms`；
  EXPONENTIAL=间隔 ×2 递增、封顶 30 分钟。默认 `retry_max_attempts=1`
  （不重试，对齐现有执行器口径）。handler 抛 `NonRetryableTaskException` 直接终态
  不重试（对齐 CAP-55 retryable 分类口径：业务拒绝类不重试，连接/超时/5xx 类可重试）。
- **FR-05 详细执行日志**：复用 CAP-12 `ExecutionLogHub`/`ExecutionWsHandler`：
  topic = `task-run:{runId}`，WS 路径 `/ws/async-tasks/{runId}`；
  `TaskContext.log()` 实时广播 + StringBuilder 攒底，终态落 `async_task_runs.logs`；
  snapshot provider 从 run 表提供历史日志 + 状态，断线重连即回放。

### M2 调度与生命周期

- **FR-06 调度器主 tick**：`@Scheduled(fixedDelay)`（默认 5s，
  `devmind.task.tick-delay-ms`）扫描 `status=ACTIVE AND next_run_at<=now` 的任务，
  **乐观认领**：`UPDATE async_tasks SET next_run_at=NULL WHERE id=? AND
  status='ACTIVE' AND next_run_at IS NOT NULL`，0 行=已被认领或已被暂停，跳过。
  三个设计要点：① 认领即置 NULL——RUNNING 期间 next_run_at 恒空，tick 不会
  重复扫到在跑任务，终态时按 FR-07 重算回填（DONE/CANCELLED 保持 NULL）；
  ② WHERE 不依赖时间戳等值比较，规避 PG timestamp(micros) 与 Java nanos 的
  精度差导致的首轮 0 行；③ 带 status 条件，堵住扫描后、认领前被 PAUSED 的
  穿透窗口。@EnableScheduling 独立配置类自持，不侵启动类；单 tick 认领上限
  `devmind.task.tick-max-claim`（默认 50），防重启后积压任务单 tick 全部起跑。
- **FR-07 调度四形态**：
  - IMMEDIATE：创建即 next_run_at=now，跑一次后 status=DONE；
  - DELAY：指定 `run_at`（或 delayMs 换算），跑一次 DONE；
  - FIXED_DELAY：每次终态后 `next_run_at = finished + interval_ms`（fixed-delay
    语义：随执行耗时逐轮漂移，非固定速率，使用方勿按整点对齐心智排程）；
  - CRON：每次终态后按 `cron_expr` 算下次；表达式非法创建即 400。
  - 任何形态跑满 `max_runs`（null=不限）→ status=DONE；PAUSED 不调度，
    恢复时 next_run_at 已过期则立即补跑一次——跨多个漏跑轮次也只补一次
    （cron 不补历史漏跑轮次，interval 同理）。
- **FR-08 重叠策略**：tick 命中到期任务先查「未终态 run」（QUEUED/RUNNING）：
  存在则按 `overlap_policy`——SKIP=本轮作废，直接按 FR-07 规则把 next_run_at
  推进到下一轮（run_count 不增、不建 run）；QUEUE=什么都不动，next_run_at
  保持原到期时刻，上一 run 终态后下一 tick 自然补跑。不存在未终态 run 才走
  FR-06 认领建 run。
- **FR-09 僵尸收割与重启续跑**：内置 stale-reaper（`devmind.task.stale-reaper-*`，
  默认 60s）：RUNNING 超 `timeout_ms×2`（或 >2h 兜底）的 run 判 FAILED，按 FR-04
  重试策略重排。**启动时立即将全量 RUNNING run 判 FAILED 并按 FR-04 重排**——
  重启后所有 RUNNING 按定义皆僵尸（线程随 JVM 消亡），不等 timeout×2，
  消除最长 1h 的恢复盲窗；此后 ACTIVE 任务 next_run_at 到期即自然续跑。
- **FR-10 提交与控制 API（Java 侧）**：`AsyncTaskService`：
  `submit(type, payload, TaskOptions)`（IMMEDIATE/DELAY）、
  `schedule(type, payload, ScheduleSpec)`（FIXED_DELAY/CRON）、
  `ensureScheduled(dedupKey, type, payload, ScheduleSpec)`（按 dedup_key upsert：
  不存在则创建，已存在仅更新调度策略与 payload，不重置 run_count/status——
  供存量 @Scheduled 迁移在启动期幂等登记，重启不产生重复任务）、
  `pause/resume/cancel/triggerNow(taskId)`、`query`。
  `cancel` 级联：在途 run 协作式中断并判 CANCELLED（run CANCELLED 状态的
  唯一生产者）；`triggerNow` 仅 ACTIVE 任务可用。
  TaskOptions/ScheduleSpec 承载全部策略字段（重试/超时/次数/重叠），Builder 装配。

### M3 管理面（REST + 前端管理页）

- **FR-11 REST 端点**：任务列表（类型/状态/关键词过滤 + 分页）、任务详情、
  暂停/恢复/取消/立即触发、执行历史分页、run 详情含日志（详见 §6）。
  管理操作仅 ADMIN；列表/详情/run 详情 admin 全量、非 admin 仅见 actor=本人。
- **FR-12 前端管理页**：后台「系统」组新增「异步任务」页（`/admin/async-tasks`），
  Segmented 分「任务 / 执行记录」两视图：任务列表（类型/名称/调度摘要/状态/
  下次执行/已跑次数/操作：暂停·恢复·取消·立即触发）+ 执行记录列表（关联任务/
  状态/第几轮·第几次/耗时/错误），行点击开抽屉展示 run 详情与实时日志
  （订阅 `/ws/async-tasks/{runId}`）。布局遵循 docs/core/前端内容区布局约定.md，
  路由登记进布局巡检。

## 3. 否决方案

- **并入 devmind-execution**：CAP-12 是同步执行原语（步骤链 + 日志枢纽），本能力是
  任务登记/调度/重试层，并入会让底座背上调度职责；保持正交，task 依赖 execution。
- **迁移四大执行器进框架**：执行器的记录表承载业务语义（构建产物、部署计划、回滚链），
  统一 Job 表会逼出宽表+类型字段的过度设计（CAP-12 迁移约定已明确）；框架面向
  无自有执行记录语义的通用任务。
- **引 Quartz/XXL-Job 等外部调度框架**：单实例 + H2/PG 文件库场景下，主 tick +
  乐观认领 20 行即可覆盖，引入外部框架带回建表/集群/配置一整套复杂度。
- **@Scheduled 注解式注册任务**：cron 硬编码进注解即失去运行期可配性（暂停/改间隔/
  次数上限），与「策略可配」目标相悖；框架内统一表驱动。
- **一次性迁移存量 15+ 处 @Scheduled**：清理/轮询/看门狗各有既定口径（防重入锁、
  心跳超时等），批量迁移风险大于收益；后续按需逐个迁入（清理类最适合先行）。

## 4. 架构与模块归属

```
devmind-task（新模块，统一异步任务层）
  api：AsyncTaskHandler(type/execute) · TaskContext(log/runId/runSeq/attempt) ·
      NonRetryableTaskException · TaskOptions/ScheduleSpec（Builder）
  engine：AsyncTaskScheduler（主 tick + 乐观认领 + 僵尸收割）·
      AsyncTaskExecutor（虚拟线程池 + 超时中断）· TaskDispatcher（按 type 分发）
  service：AsyncTaskService（submit/schedule/pause/resume/cancel/triggerNow/query）
  model：AsyncTaskEntity / AsyncTaskRunEntity + Repository
  ws：/ws/async-tasks/{runId}（ExecutionWsHandler + run 表 snapshot provider）
  controller：/api/async-tasks/**（FR-11）
  依赖：devmind-common + devmind-execution（日志枢纽/WS Handler）；
      devmind-auth 角色判定走 SecurityContext 既有口径

业务模块（任务类型拥有方）
  实现 AsyncTaskHandler 注册 Spring bean + pom 依赖 devmind-task，
  提交走 AsyncTaskService —— 同「执行器依赖 execution」的既有依赖方向

devmind-app：装配依赖 +1；frontend/src/features/async-task/ 自包含 +
  App.tsx 注册路由 + 后台「系统」组菜单登记
```

任务执行语义归 handler 实现方，框架只管「何时跑、跑几次、败了怎么办、日志在哪」。

## 5. 数据模型

```
async_tasks(                        -- 任务登记 + 调度配置一体
  id varchar pk,                    -- 平台统一 id 生成口径
  task_type varchar(64),            -- handler type，分发键
  name varchar(200),
  payload clob,                     -- JSON 快照，LONGVARCHAR + length
  schedule_kind varchar(16),        -- IMMEDIATE / DELAY / FIXED_DELAY / CRON
  dedup_key varchar(128),           -- 幂等登记键（ensureScheduled upsert），可空
  run_at timestamp,                 -- DELAY 指定时刻（其余形态 null）
  interval_ms bigint,               -- FIXED_DELAY 间隔
  cron_expr varchar(128),           -- CRON 表达式
  max_runs int,                     -- 执行次数上限（null=不限）
  run_count int default 0,
  retry_max_attempts int default 1,
  retry_backoff varchar(16),        -- FIXED / EXPONENTIAL
  retry_interval_ms bigint,
  timeout_ms bigint,
  overlap_policy varchar(8),        -- SKIP / QUEUE
  next_run_at timestamp,            -- 认领即置 NULL（FR-06），终态重算回填
  status varchar(16),               -- ACTIVE / PAUSED / DONE / CANCELLED
  actor varchar(64),
  project_id varchar(64),           -- 可空，归属过滤用
  created_at / updated_at,
  unique(dedup_key),                -- 幂等登记防重
  index(status, next_run_at)        -- tick 扫描主路径
)

async_task_runs(                    -- 每次执行一行（含每次重试 attempt）
  id varchar pk,
  task_id varchar,                  -- 外键归属 async_tasks
  run_seq int,                      -- 第几轮执行（1 起，max_runs 口径）
  attempt int,                      -- 该轮第几次尝试（1 起）
  status varchar(16),               -- QUEUED / RUNNING / SUCCESS / FAILED / CANCELLED
  scheduled_at timestamp,
  started_at timestamp, finished_at timestamp, duration_ms bigint,
  error varchar(2000),              -- 失败摘要（堆栈首行 + 关键帧）
  logs clob,                        -- 完整执行日志，LONGVARCHAR + length
  index(task_id, run_seq, attempt)
)
```

- 列名避让 H2/PG 保留字（run_seq/attempt 不撞字；不用 version/commit 等）。
- CLOB 列（payload/logs）必带 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` +
  `@Column(length = 16_777_216)`，防 PG oid 与 MySQL tinytext（红线既有口径）。
- `int default 0` / `default 1` 用实体初始值 + getter 兜底，不用 @ColumnDefault
  （对齐 Boolean/数值列既有坑口径）；字符串默认值才用带引号 @ColumnDefault。
- ddl-auto=update 自动建表，无存量表变更、无迁移脚本。

## 6. API 概要

- `GET /api/async-tasks`：任务列表，参数 `type/status/keyword/page/size`；
  admin 全量、非 admin 强制 actor=本人过滤。
- `GET /api/async-tasks/{id}`：任务详情（含策略字段与 run_count）。
- `POST /api/async-tasks/{id}/pause|resume|cancel|trigger`：生命周期操作，仅 ADMIN；
  trigger 仅 ACTIVE 任务可用，PAUSED/DONE/CANCELLED 均 409（先恢复再触发）。
- `GET /api/async-tasks/{id}/runs`：执行历史分页（run_seq 倒序）。
- `GET /api/async-task-runs/{runId}`：run 详情（状态/attempt/耗时/error/logs）；
  非 admin 强制 join task.actor 校验属主（同任务详情口径，防猜 runId 越权看日志）。
- WS：`/ws/async-tasks/{runId}`——CAP-12 通用 Handler，snapshot 帧回放历史日志，
  终态补 done。
- 任务的**创建/改策略只走 Java API**（业务方代码提交），REST 不提供创建端点
  （任务类型是代码注册的，开放 REST 创建无意义且扩大攻击面）。

## 7. 安全

- 管理操作（pause/resume/cancel/trigger）仅 ADMIN；查询非 admin 强制本人 actor 过滤
  （对齐 CAP-62 owner 强制口径，不在前端藏按钮代替后端校验）。
- handler 执行在框架线程内合成 SecurityContext（actor=任务登记人），handler 内调
  业务 Service 的权限边界 = 登记人既有权限，不做第二套授权（对齐 CAP-75 FR-06 口径）。
- payload 落库即 JSON 快照，框架不解析不执行其中代码；handler 自行做参数校验。
- 重试耗尽（终态 FAILED 且 attempts 用尽）可选发 P1 通知（ObjectProvider 探测
  通知 SPI，未装配不阻塞）；不包含任何凭证类字段。

## 8. 里程碑

- **M1 任务内核**：FR-01~05（表模型 + Handler SPI + 虚拟线程执行 + 重试 + 日志）。
- **M2 调度与生命周期**：FR-06~10（主 tick 乐观认领 + 四形态调度 + 重叠策略 +
  僵尸收割/重启续跑 + Java 提交 API）。
- **M3 管理面**：FR-11~12（REST + 后台管理页 + WS 日志查看）。
- **展望**：存量散点 @Scheduled 逐个迁入（清理类先行）；重试耗尽接通知；
  多实例部署时的选主/分片；任务依赖编排（流程层域）。

## 9. 验收标准

- M1：注册 fake handler 提交 IMMEDIATE 任务——run 落库、日志 WS 实时可见、
  终态 logs 持久化、任务转 DONE；handler 抛普通异常 + retry_max_attempts=3/
  EXPONENTIAL 时产生 3 条 attempt run、间隔递增、终 FAILED，抛
  NonRetryableTaskException 只跑 1 次；提交未知 type 400；payload 创建时固化。
- M2：DELAY 到点才跑；FIXED_DELAY 按间隔连跑、max_runs=3 后 DONE；
  cron 非法 400；PAUSED 不跑、恢复跨多轮也只补跑一次；overlap=SKIP 时上一 run
  未终态跳过本轮且 run_count 不增、QUEUE 时上一 run 终态后下一 tick 补跑；
  认领后 next_run_at 置 NULL，在跑任务不被后续 tick 重复扫到；手造 RUNNING
  超时 run 被收割并按策略重排；**重启即收割在途 RUNNING 并按 FR-04 重排**，
  到期任务自动续跑；`ensureScheduled` 重启二次登记不产生新任务、仅更新策略；
  trigger 对 PAUSED 任务 409。
- M3：非 admin 查列表只见本人任务、查他人 run 详情 403、管理操作 403；
  admin 全量可操作；
  管理页两视图可查、抽屉日志实时滚动终态定格；路由登记进布局巡检；
  E2E（隔离 app + e2e profile）submit→WS 看日志→查 run 记录全链通过，
  跑完清理自起进程。
- 全量 `mvn -q test` 与 `npx tsc -b` 绿；单测覆盖重试退避计算、乐观认领并发、
  四形态 next_run_at 推进、权限过滤。

## 10. 风险与开放问题

| # | 项 | 口径 |
|---|---|---|
| R1 | 主 tick 5s 粒度，cron 最小生效粒度受限 | 平台任务均为分钟级以上周期；接受，秒级需求走 FIXED_DELAY 或后续降 tick |
| R2 | 虚拟线程池无界，并发任务暴涨拖垮 DB | M1 执行靠重叠策略 + 超时兜底 + 单 tick 认领上限（FR-06）防重启积压风暴；后续可加全局并发闸（信号量） |
| R3 | cron 解析库选型 | 复用 Spring `CronExpression`（spring-context 既有），不引新依赖 |
| R4 | 乐观认领在 H2/PG/MySQL 三库语义 | 标准 UPDATE WHERE 全库通用；E2E 覆盖 H2，存量 PG/MySQL 部署验证 |
| R5 | 存量 @Scheduled 长期并存两套调度视图 | 非目标已声明不迁移；管理页注明只覆盖框架任务 |
| O1 | 任务是否需要项目归属强隔离 | v1 project_id 仅过滤展示用；CAP-62 数据权限落地后再收紧 |
| O2 | 是否开放 REST 创建任务 | v1 否（§6）；如未来需要「页面手工建一次性任务」再评估 |
| O3 | 四大执行器是否最终迁入 | 留待执行器记录语义稳定后单独评估，不在本期 |
