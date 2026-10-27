# CAP-80 通用任务框架（任务管理 / Async Task Framework）

> 能力 ID：CAP-80 ｜ 分类：底座 ｜ 状态：设计定稿 ｜ 日期：2026-10-21（M4 增补同日定稿）
> 决策记录：**独立新模块 devmind-task**（积木式底座，依赖 devmind-execution 复用日志枢纽），
> 不并入 devmind-execution——CAP-12 是「步骤链执行原语」层，本能力是「任务登记/调度/重试」层，
> 职责正交；**不迁移四大执行器**（build/deploy/test/release 保留自有记录表与状态机，CAP-12
> 迁移约定不变），框架面向「无自有执行记录语义的通用异步/定时任务」；调度采用
> **主 tick + next_run_at 到期判定 + 乐观认领**（参照 CAP-28 WorklogScheduler 现有范例），
> 单实例部署前提下不引分布式调度框架；**认领即置 next_run_at=NULL**（评审定稿：
> 在跑任务不被 tick 重扫、规避时间戳精度等值比较、status 条件堵 PAUSED 穿透），
> 终态按形态重算回填；**dedup_key + ensureScheduled 幂等登记**（评审定稿：存量
> @Scheduled 迁移的启动期登记不产生重复任务）；**重启即收割在途 RUNNING**
> （不等 timeout×2，消除恢复盲窗）。**M4 增补（任务管理化）**：管理面正名「任务管理」，
> **开放 REST 创建/编辑**（O2 关闭落地，ADMIN-only）；**runner 执行位置**——
> `async_tasks.agent_node_id` + handler 元数据声明执行位置（LOCAL/RUNNER），
> RUNNER 类经 `AgentNodeRouter` 路由链（显式→项目默认→平台默认→409，无本机回落）；
> **builtin.shell 复用 CAP-36 `exec` 帧族**（评审定稿：协议 v3 起全量存量 runner 已支持，
> 零协议改动零 runner 发版；不另起 task_exec 帧族），runner 侧 execAllowlist 防线不变。

> 后续消费方（2026-10-27，定稿未实施）：[CAP-96](CAP-96-assistant-task-runtime.md) 和 [CAP-98](CAP-98-standing-missions-upstream-watch.md) 复用本框架的主 tick/内部 handler 调度，助手目标、授权、作业等待与长期委托由各自业务表管理，不由 async run 状态代替。保留本 CAP 的 ADMIN 人工创建接口；普通用户经受限助手业务 API 提交白名单内部 handler，身份必须显式传播。后台 run 重试不得直接重放助手副作用，须先按 CAP-97 的动作回执对账。

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
- **FR-12 前端管理页**：后台「系统」组新增「任务管理」页（`/admin/tasks`），
  Segmented 分「任务 / 执行记录」两视图：任务列表（类型/名称/调度摘要/执行位置/状态/
  下次执行/已跑次数/操作：暂停·恢复·取消·立即触发·编辑）+ 执行记录列表（关联任务/
  状态/第几轮·第几次/耗时/错误），行点击开抽屉展示 run 详情与实时日志
  （订阅 `/ws/async-tasks/{runId}`）。布局遵循 docs/core/前端内容区布局约定.md，
  路由登记进布局巡检。

### M4 任务管理化（页面创建 + runner 执行位置）

- **FR-13 REST 创建与编辑（管理面闭环）**：
  - `POST /api/async-tasks`：创建任务（四形态全支持），body 承载
    taskType/name/payload/scheduleKind/runAt/intervalMs/cronExpr/maxRuns/
    retry*/timeoutMs/overlapPolicy/agentNodeId；校验与 Java API 同口径
    （未知 type 400、非法 cron 400、FIXED_DELAY 缺 intervalMs 400）；
  - `PUT /api/async-tasks/{id}`：编辑策略与 payload——终态（DONE/CANCELLED）409；
    payload 替换只影响后续轮次（在途 run 持创建时快照语义不变）；
    PAUSED 任务只改配置不复活调度；ACTIVE 任务调度字段变更即重算 next_run_at；
  - `GET /api/async-tasks/types`：已注册 handler 元数据清单
    `[{type, description, executor, payloadTemplate}]`，供新建表单下拉
    （executor=RUNNER 的类型表单才显示节点选择）；
  - 三个端点均仅 ADMIN（shell 任务 = 节点远程代码执行面，与节点管理同档），
    既有 `POST /api/async-tasks/**` ADMIN 规则覆盖创建；PUT 规则同档补齐。
- **FR-14 执行位置（executor）**：`AsyncTaskHandler` 增 default 元数据方法
  `description()` / `payloadTemplate()` / `executor()`（`LOCAL`=服务端虚拟线程，
  默认；`RUNNER`=下发 agent 节点执行）。任务落库增 `agent_node_id` 列（可空）：
  RUNNER 类任务执行时经 `AgentNodeRouter.route(task.agentNodeId,
  项目默认(projectId 非空), null)` 选节点——显式→项目默认→平台默认→皆无 409
  （对齐 CAP-34 会话路由语义，无本机回落）；节点离线/协议版本不足（exec 需 v3+）
  在执行时以可重试失败落 error（任务级 409 只在创建/编辑校验显式指定节点的
  存在性，在线性留给执行时判定——周期任务起止跨越节点上下线是常态）。
  LOCAL 类任务的 agent_node_id 一律忽略（创建不拒绝，避免表单态复杂化）。
  TaskOptions/ScheduleSpec 同步增 `agentNodeId`（Java API 与 REST 同能力）。
- **FR-15 builtin.shell（runner shell 命令）**：type=`builtin.shell`，
  executor=RUNNER；payload `{"command": "<多行脚本>", "workingDir": "<可选>",
  "env": {...}, "timeoutSec": <可选>}`。执行经
  `ObjectProvider<AgentNodeConnector>.exec(nodeId, AgentExecCommand, sink)`：
  execId=runId（帧路由键即 run，排障直达）、projectId=任务归属（可空）、
  repo=null（不备代码，runner 本地 workDir 执行）、timeoutSec 默认取
  `min(task.timeoutMs/1000, 1800)`；`exec_log` 行实时入 `ctx.log()`
  （复用 FR-05 日志枢纽/WS 链路零改动）；exit 0=成功，非零/超时/拉起失败
  抛异常按 FR-04 重试（非零退出视为可重试——远程环境抖动与脚本失败同路径）。
  **已知限制**：exec 帧族无取消协议——任务 cancel/超时只中断服务端等待线程
  （run 判 CANCELLED/FAILED），runner 侧进程由 exec 帧自身 timeoutSec 兜底
  整树 kill，最长残留 = timeoutSec；v1 接受（进程有界自死，不泄漏）。
- **FR-16 前端创建/编辑**：「任务管理」页 extra 增「新建任务」（ADMIN 可见）：
  Modal 表单——类型（types 端点下拉，带描述）、名称、执行节点（仅 RUNNER 类
  显示，ONLINE 过滤 + 平台默认标记，复用会话新建页的 Select 模式；空=走路由链
  兜底）、命令/payload（builtin.shell 渲染多行命令编辑框；其余类型 JSON
  textarea 预填 payloadTemplate）、调度形态（立即/定时 DatePicker/固定间隔秒/
  cron）、高级区（重试次数/退避/超时/重叠/maxRuns）。非终态任务行内「编辑」
  复用同一表单回读改 PUT。任务列表加「执行位置」列（节点名/服务端）。

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
- **（M4）新增 task_exec 帧族替代复用 exec**：exec 帧族（v3）语义即「非会话 shell
  下发 + 行流回传 + 退出码收口」，存量 runner 全量支持；另起帧族要动
  common/agent/runner 三侧 + 全环境 runner 升级后才可用，收益仅是去掉取消语义
  缺口与项目向字段，不成比例。取消缺口以 exec 自带 timeoutSec 兜底（FR-15）。
- **（M4）任务创建走「脚本库引用」而非内联命令**：脚本入库再引用多一层实体与
  版本管理；v1 内联 command 快照落 payload（创建即固化，FR-02 语义天然覆盖），
  复用脚本库留待真实复用诉求出现。

## 4. 架构与模块归属

```
devmind-task（新模块，统一异步任务层）
  api：AsyncTaskHandler(type/execute + description/payloadTemplate/executor 元数据) ·
      TaskContext(log/runId/runSeq/attempt) ·
      NonRetryableTaskException · TaskOptions/ScheduleSpec（Builder，含 agentNodeId）
  engine：AsyncTaskScheduler（主 tick + 乐观认领 + 僵尸收割）·
      AsyncTaskExecutor（虚拟线程池 + 超时中断）· TaskDispatcher（按 type 分发）
  builtin：EchoTaskHandler（自测/E2E）· ShellTaskHandler（builtin.shell，RUNNER 类——
      ObjectProvider<AgentNodeConnector> 下发 exec 帧 + AgentNodeRouter 选节点，
      两设施均在既有依赖 common/execution 内，零新 Maven 依赖）
  service：AsyncTaskService（submit/schedule/ensureScheduled/create/update/
      pause/resume/cancel/triggerNow/query）
  model：AsyncTaskEntity / AsyncTaskRunEntity + Repository
  ws：/ws/async-tasks/{runId}（ExecutionWsHandler + run 表 snapshot provider）
  controller：/api/async-tasks/**（FR-11/FR-13）
  依赖：devmind-common + devmind-execution（日志枢纽/WS Handler/AgentNodeRouter）；
      devmind-auth 角色判定走 SecurityContext 既有口径；
      agent 模块经 common SPI ObjectProvider 探测，未装配 = RUNNER 类任务执行 409

业务模块（任务类型拥有方）
  实现 AsyncTaskHandler 注册 Spring bean + pom 依赖 devmind-task，
  提交走 AsyncTaskService —— 同「执行器依赖 execution」的既有依赖方向

devmind-app：装配依赖 +1；frontend/src/features/async-task/ 自包含 +
  App.tsx 注册路由 + 后台「系统」组菜单登记（「任务管理」/admin/tasks）
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
  agent_node_id varchar(64),        -- M4：执行节点（RUNNER 类任务显式指定；空=路由链兜底；LOCAL 类忽略）
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
- `POST /api/async-tasks`：创建任务（FR-13，仅 ADMIN）。
- `PUT /api/async-tasks/{id}`：编辑策略/payload/调度（FR-13，仅 ADMIN，终态 409）。
- `GET /api/async-tasks/types`：已注册 handler 元数据清单（FR-13，仅 ADMIN，
  供新建表单类型下拉）。
- `GET /api/async-tasks/{id}`：任务详情（含策略字段与 run_count）。
- `POST /api/async-tasks/{id}/pause|resume|cancel|trigger`：生命周期操作，仅 ADMIN；
  trigger 仅 ACTIVE 任务可用，PAUSED/DONE/CANCELLED 均 409（先恢复再触发）。
- `GET /api/async-tasks/{id}/runs`：执行历史分页（run_seq 倒序）。
- `GET /api/async-task-runs/{runId}`：run 详情（状态/attempt/耗时/error/logs）；
  非 admin 强制 join task.actor 校验属主（同任务详情口径，防猜 runId 越权看日志）。
- WS：`/ws/async-tasks/{runId}`——CAP-12 通用 Handler，snapshot 帧回放历史日志，
  终态补 done。
- 任务创建双通道：Java API（业务方代码登记，FR-10）+ REST（管理面手工创建，
  FR-13）；两者同走 AsyncTaskService 同一校验与落库路径。

## 7. 安全

- 管理操作（pause/resume/cancel/trigger）与**创建/编辑/类型清单**（FR-13）仅 ADMIN——
  builtin.shell 任务是节点远程代码执行面，与节点管理（/api/agent-nodes 写操作）同档收敛；
  查询非 admin 强制本人 actor 过滤（对齐 CAP-62 owner 强制口径，不在前端藏按钮代替后端校验）。
- **runner 侧双层防线**：builtin.shell 命令到达节点后仍过 runner 本地
  `execAllowlist` 逐行前缀校验（空=拒绝一切，shell 内置命令豁免）——服务端 ADMIN
  认证是第一层，节点运维白名单是第二层，任一缺失即 fail-visible（错误入 run 日志）。
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
- **M4 任务管理化**：FR-13~16（REST 创建/编辑 + handler 元数据/types 端点 +
  agent_node_id 执行位置 + builtin.shell runner 执行 + 前端创建/编辑表单与改名）。
- **展望**：存量散点 @Scheduled 逐个迁入（清理类先行）；重试耗尽接通知；
  多实例部署时的选主/分片；任务依赖编排（流程层域）；exec 取消帧（task 级
  cancel 直达 runner 杀进程）。

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
- M4：REST 创建四形态任务（非法 type/cron/interval 400）；编辑终态任务 409；
  types 端点返回全部已注册 handler 含 executor 标记；非 admin 创建/编辑 403；
  builtin.shell 指定在线节点执行成功（exit 0 → run SUCCESS、runner 行流实时入
  WS/日志落库）；非零退出按策略重试；无可用节点执行时 run FAILED 且 error 可读；
  创建指定不存在节点 400；E2E 起真实 runner（execAllowlist 配置）全链通过；
  页面改名「任务管理」/admin/tasks 过布局巡检。
- 全量 `mvn -q test` 与 `npx tsc -b` 绿；单测覆盖重试退避计算、乐观认领并发、
  四形态 next_run_at 推进、权限过滤、shell payload 解析与节点选择路由。

## 10. 风险与开放问题

| # | 项 | 口径 |
|---|---|---|
| R1 | 主 tick 5s 粒度，cron 最小生效粒度受限 | 平台任务均为分钟级以上周期；接受，秒级需求走 FIXED_DELAY 或后续降 tick |
| R2 | 虚拟线程池无界，并发任务暴涨拖垮 DB | M1 执行靠重叠策略 + 超时兜底 + 单 tick 认领上限（FR-06）防重启积压风暴；后续可加全局并发闸（信号量） |
| R3 | cron 解析库选型 | 复用 Spring `CronExpression`（spring-context 既有），不引新依赖 |
| R4 | 乐观认领在 H2/PG/MySQL 三库语义 | 标准 UPDATE WHERE 全库通用；E2E 覆盖 H2，存量 PG/MySQL 部署验证 |
| R5 | 存量 @Scheduled 长期并存两套调度视图 | 非目标已声明不迁移；管理页注明只覆盖框架任务 |
| R6 | （M4）exec 帧族无取消协议：任务 cancel 后 runner 侧进程残留 | exec 帧自带 timeoutSec 整树 kill 兜底，残留有界；builtin.shell 的 timeoutSec 默认 ≤30min；根治留「exec 取消帧」展望项 |
| R7 | （M4）builtin.shell 命令经 execAllowlist 拒绝时用户体验 | 白名单拒绝是 runner 运维防线（安全语义正确），错误文本直达 run 日志与 error 列，运维按提示配 execAllowlist 即可 |
| O1 | 任务是否需要项目归属强隔离 | v1 project_id 仅过滤展示用；CAP-62 数据权限落地后再收紧 |
| ~~O2~~ | ~~是否开放 REST 创建任务~~ | **M4 已落地**（FR-13：ADMIN-only + handler 元数据驱动表单） |
| O3 | 四大执行器是否最终迁入 | 留待执行器记录语义稳定后单独评估，不在本期 |
