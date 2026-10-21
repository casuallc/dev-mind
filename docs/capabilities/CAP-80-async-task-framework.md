# CAP-80 通用异步任务框架（Async Task Framework）

> 能力 ID：CAP-80 ｜ 分类：底座 ｜ 状态：设计定稿 ｜ 日期：2026-10-21
> 背景：现状是「同步执行底座（CAP-12）+ 各模块私有虚拟线程池 + 自有记录表 +
> 自有僵尸收割 @Scheduled」的多份同构拷贝：build/deploy/test/release 四份、
> finetune/eval/repo-clone 等若干份；全仓库 15+ 处散点 @Scheduled，无通用重试、
> 无执行次数控制、无统一任务视图。本能力抽出**统一异步任务层**（模块
> `devmind-task`）：任务登记/调度/重试/日志/次数控制一体，后续所有异步与定时
> 任务一律丢进框架，不再新起私有线程池 + 私有 @Scheduled。

## 1. 目的

- **一个入口**：业务方只实现 `AsyncTaskHandler`（按 task_type 注册），提交
  （立即/延迟/定时）走 `AsyncTaskService` 统一 API，不再关心线程、调度与收割。
- **策略可配**：每个任务可设重试次数与退避策略、超时、执行次数上限、
  并发重叠策略（SKIP/QUEUE）、定时规则（cron/固定间隔/指定时刻）。
- **过程可视**：每次执行（run）独立记录，实时日志走 CAP-12 日志枢纽 +
  WS，终态持久化；后台管理页统一查看任务与执行历史。
- **崩溃可恢复**：任务与调度状态全部落库，服务重启后到期任务自动续跑，
  僵尸 run 收割后按重试策略重排或判失败。

## 2. 功能需求

### M1 任务内核（表模型 + 执行 + 重试）

- **FR-01 任务表 `async_tasks`**：任务登记与调度配置一体。
  `id / task_type / name / payload（JSON 快照，LONGVARCHAR）/
  schedule_kind（IMMEDIATE|DELAY|FIXED_INTERVAL|CRON）/ run_at（DELAY 指定时刻）/
  interval_ms / cron_expr / max_runs（执行次数上限，null=不限）/ run_count /
  retry_max_attempts / retry_backoff（FIXED|EXPONENTIAL）/ retry_interval_ms /
  timeout_ms / overlap_policy（SKIP|QUEUE）/ next_run_at / status
  （ACTIVE|PAUSED|DONE|CANCELLED）/ actor / project_id（可空，归属过滤用）/
  created_at / updated_at`。
  列名避让 H2/PG 保留字（不用 version 等）；CLOB 必带 `@JdbcTypeCode`。
- **FR-02 执行记录表 `async_task_runs`**：每次执行一行（含重试的每次 attempt）。
  `id / task_id / run_seq（第几轮执行）/ attempt（该轮第几次尝试，1 起）/
  status（QUEUED|RUNNING|SUCCESS|FAILED|CANCELLED）/ scheduled_at / started_at /
  finished_at / duration_ms / error / logs（LONGVARCHAR）`。
- **FR-03 Handler SPI**：`AsyncTaskHandler`（devmind-task api 包）：
  `String type()` + `void execute(String payload, TaskContext ctx)`；
  `TaskContext` 提供 `log(String)`（实时推 WS + 攒入 run 日志）、
  `runId/taskId/runSeq/attempt`、进度回写。各业务模块以实现类 Spring bean
  注册，框架按 type 分发；未知 type 的任务提交即 400。
- **FR-04 执行内核**：框架自持虚拟线程池（`newVirtualThreadPerTaskExecutor`，
  @PreDestroy 关闭）；认领即更新 run=RUNNING + started_at，执行 handler，
  捕获全部异常转 FAILED（含堆栈摘要入 error）；单 run 超时（timeout_ms，
  默认 30 分钟）中断并判 FAILED（可重试）。**触发/认领方法禁 @Transactional**，
  靠 save 自身事务即时提交（沿用执行器红线）。
- **FR-05 重试策略**：run 失败后 `attempt < retry_max_attempts` 时按
  `retry_backoff` 计算下次执行时刻（FIXED=固定 `retry_interval_ms`；
  EXPONENTIAL=间隔 ×2 递增、封顶 30 分钟）重排新 attempt 的 run；
  默认 `retry_max_attempts=1`（不重试，对齐现有执行器口径）。
  handler 抛 `NonRetryableTaskException` 直接终态不重试（对齐 CAP-55
  retryable 分类口径：4xx 类业务拒绝不重试，连接/超时/5xx 类可重试）。
- **FR-06 详细执行日志**：复用 CAP-12 `ExecutionLogHub`/`ExecutionWsHandler`：
  topic = `task-run:{runId}`，WS 路径 `/ws/async-tasks/{runId}`；
  `TaskContext.log()` 实时广播 + StringBuilder 攒底，终态落 `async_task_runs.logs`；
  snapshot provider 从 run 表提供历史日志 + 状态，断线重连即回放。

### M2 调度与生命周期

- **FR-07 调度器**：主 tick（`@Scheduled(fixedDelay)`，默认 5s，配置
  `devmind.task.tick-delay-ms`）扫描 `status=ACTIVE AND next_run_at<=now`
  的任务，**乐观认领**（`UPDATE ... SET next_run_at=... WHERE id=? AND
  next_run_at=?`，0 行=已被认领，跳过）防 tick 与手动触发并发重复；
  同任务上一 run 未终态时按 `overlap_policy`：SKIP=本轮跳过顺延，
  QUEUE=保持 next_run_at 到期即补跑。模式参照 CAP-28 WorklogScheduler
  「主 tick + 到期判定 + 防重入」。
- **FR-08 调度语义**：
  - IMMEDIATE：创建即 next_run_at=now，跑一次后 status=DONE（max_runs=1）；
  - DELAY：指定 `run_at`（或 delayMs 换算），跑一次 DONE；
  - FIXED_INTERVAL：每次终态后 `next_run_at = finished + interval_ms`；
  - CRON：每次终态后按 cron 算下次；`cron_expr` 非法创建即 400。
  - 任何形态跑满 `max_runs` → status=DONE；PAUSED 不调度、恢复时
    next_run_at 已过期则立即补跑一次（cron 不补历史漏跑轮次）。
- **FR-09 僵尸收割**：内置 stale-reaper（同 tick 内或独立 delay，配置
  `devmind.task.stale-reaper-*`）：RUNNING 超 `timeout_ms×2`（或 >2h 兜底）
  的 run 判 FAILED，按 FR-05 重试策略重排；服务重启后 ACTIVE 任务
  next_run_at 到期即自然续跑，无需特殊恢复流程。
- **FR-10 提交 API**：`AsyncTaskService`：
  `submit(type, payload, TaskOptions)`（IMMEDIATE/DELAY）、
  `schedule(type, payload, ScheduleSpec)`（FIXED_INTERVAL/CRON）、
  `pause/resume/cancel/triggerNow(taskId)`、`query`。
  TaskOptions/ScheduleSpec 承载 FR-01 全部策略字段，业务方 Builder 装配。

### M3 管理面（REST + 前端）

- **FR-11 REST 端点**：`/api/async-tasks`（列表：类型/状态/关键词过滤 +
  分页）、`/api/async-tasks/{id}`（详情）、`/{id}/pause|resume|cancel|trigger`
  （POST）、`/api/async-tasks/{id}/runs`（执行历史分页）、
  `/api/async-task-runs/{runId}`（run 详情含日志）。
  管理操作仅 ADMIN；列表/详情 admin 全量、非 admin 仅见 actor=本人。
- **FR-12 前端管理页**：后台「系统」组新增「异步任务」页
  （`/admin/async-tasks`），Segmented 分「任务 / 执行记录」两视图：
  任务列表（类型/名称/调度摘要/状态/下次执行/已跑次数/操作：暂停·恢复·
  取消·立即触发）+ 执行记录列表（关联任务/状态/第几轮·第几次/耗时/错误），
  行点击开抽屉展示 run 详情与实时日志（订阅 `/ws/async-tasks/{runId}`）。
  布局遵循 docs/core/前端内容区布局约定.md。
- **FR-13 配置项**：`devmind.task.tick-delay-ms`（默认 5000）、
  `devmind.task.default-timeout-ms`（默认 1800000）、
  `devmind.task.default-retry-interval-ms`（默认 60000）、
  `devmind.task.stale-reaper-delay-ms`（默认 60000）；
  @EnableScheduling 独立配置类自持，不侵启动类。

## 3. 非目标（本期不做）

- **不迁移四大执行器**：build/deploy/test/release 保留自有记录表与状态机
  （CAP-12 迁移约定不变）；框架面向「无自有执行记录语义的通用异步/定时
  任务」，执行器是否迁入由后续能力评估。
- **不动存量 @Scheduled**：15+ 处散点定时（清理/轮询/看门狗）维持原样，
  后续按需逐个迁入框架（清理类最适合先行）。
- **不做分布式调度**：单实例部署前提下乐观认领足够；多实例选主/分片
  留待后续。
- **不做任务编排**：任务间依赖/DAG 属流程层（CAP-15/17 域），框架只保证
  单任务可靠执行。

## 4. 验收

### M1
- 注册一个 fake handler 提交 IMMEDIATE 任务：run 落库、日志实时 WS 可见、
  终态 logs 持久化、任务转 DONE。
- handler 抛普通异常 + retry_max_attempts=3/EXPONENTIAL：产生 3 条 attempt
  run，间隔递增，最终 FAILED；抛 NonRetryableTaskException 只跑 1 次。
- 提交未知 type → 400；payload 快照在创建时固化，后续改业务配置不影响
  在途任务。

### M2
- DELAY 任务到点才跑；FIXED_INTERVAL 按间隔连跑，max_runs=3 后 DONE；
  CRON 非法表达式 400；PAUSED 期间不跑，恢复后立即补跑一次。
- 框架内手造 RUNNING 超时 run，收割后按重试策略重排；服务重启后到期
  任务自动续跑。
- overlap=SKIP 时上一 run 未终态则跳过本轮且 run_count 不增。

### M3
- 非 admin 调 `/api/async-tasks` 只见本人任务、管理操作 403；admin 全量。
- 管理页两视图可查，抽屉内日志实时滚动、终态定格；路由登记进布局巡检。
- E2E：起隔离 app（e2e profile），submit→WS 看日志→查 run 记录全链验证，
  跑完清理自起进程。

## 5. 依赖与影响

- 依赖：CAP-12（ExecutionLogHub/ExecutionWsHandler 复用）、CAP-01（RBAC：
  admin 判定）、CAP-06（可选：重试耗尽 P1 通知，ObjectProvider 探测注入，
  未装配不阻塞）。
- 新增模块 `devmind-task`（api/service/engine/ws/controller），`devmind-app`
  装配依赖；前端 `src/features/async-task/` 自包含 + App.tsx 注册路由 +
  后台菜单登记。
- 表结构：新增 `async_tasks` / `async_task_runs` 两表（ddl-auto 自动建）；
  CLOB 列必带 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` + length 防 tinytext；
  无存量表变更、无迁移脚本。
- 提交纪律：① 模块骨架 + 表模型 + 内核（M1）；② 调度器与生命周期（M2）；
  ③ REST + 前端管理页（M3）——分次提交，每次过 `mvn -q test` /
  `npx tsc -b`。
