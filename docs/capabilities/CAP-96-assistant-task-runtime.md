# CAP-96 目标驱动的持续助手任务内核

> 能力 ID：CAP-96 ｜ 分类：底座/会话 ｜ 状态：定稿未实施 ｜ 日期：2026-10-27
> 上游能力：CAP-76（工具网关与计划）、CAP-80（异步调度）、CAP-05/34（执行会话）、CAP-62（数据权限）、CAP-67（用量）；首批执行依赖 CAP-97 M1，长期委托消费方为 CAP-98。
> 需求来源：194 环境 dev-mind 项目 REQ-4（现为 CANCELLED，仅作问题来源，不改变其状态），以及 REQ-5/9/10 对连续处理的共同诉求。
> 决策记录：
> 1. **助手任务是一等业务对象**：聊天轮次、确认单、异步 run、开发会话都不是目标任务；新增持久任务及决策记录，引用已有执行对象，不复制其状态机。
> 2. **复用 CAP-80 调度、CAP-76 工具、JobTerminalProbe 作业探针**；助手只推进一个有界决策片段，等待时落库退出，不占一个线程轮询数小时。不引第二套调度器或消息中间件。
> 3. **计划可以调整，完成必须有证据**：步骤完成进入验收，只有 CAP-97 的必需验收项通过才能完成；模型最终回答不是终态依据。
> 4. **重启恢复以持久状态与业务对账为准**：事件只是加速信号，数据库轮询可独立恢复；未知副作用先对账，不承诺跨系统 exactly-once。
> 5. **自主执行与受控授权同时交付**：CAP-97 的身份、授权、动作登记与最小验证不是后置增强，缺失时停止任务执行。
> 6. 本文所有新增类型、表和端点均为目标设计；不表示已实现，也不自动迁移 CAP-76 历史计划。

## 1. 目的

用户交代“定位并修复指定构建失败，重新验证”后，系统能够自行读取证据、安排执行、等待结果、判断是否需要补查或修复，直到完成或带着明确问题请求介入。用户无需重复发送“看日志”“继续”“再构建”。

现状：CAP-76 已有模型工具循环、一次确认的多步计划、异步执行和 await，但计划失败即停；完成通知主要面向人，缺少通用的结果驱动重新决策。CAP-80 负责调度和 run 重试，不负责判断业务目标是否完成。

首个交付场景为**单项目、单需求工作区的构建失败诊断与受控修复**。目标不明确、验收条件不全时先澄清；权限或工具不足时明确阻塞。不将“操作已提交”“会话结束”“步骤跑完”视为“问题已解决”。

## 2. 功能需求

### M1 持久任务、单步推进与最小闭环

- **FR-01 任务创建与确认**：Web 显式创建、飞书自然语言确认目标后创建同一任务。保存目标、结构化验收条件、项目/需求/仓库/分支范围、来源会话、执行模板、预算和期限；actor/部署域由服务端认证上下文提供。仅聊天提问不隐式变成后台长期任务。创建接口的客户端幂等键在属主范围内唯一。
- **FR-02 状态机**：`QUEUED → RUNNING → WAITING_JOB / WAITING_USER / VERIFYING / PAUSED / DONE / FAILED / CANCELLED`；等待作业就绪回 QUEUED，用户回答或恢复经校验回 QUEUED，验收不足回 QUEUED 或 WAITING_USER。`DONE/FAILED/CANCELLED` 为终态；重新做创建关联新任务，不覆盖历史。WAITING_USER 必含问题类型、所需信息/授权和已有证据；超预算默认暂停并写明原因，不谎称完成。
- **FR-03 有界决策片段**：一次推进装配当前目标、有效授权、最近证据、已有动作和失败轨迹，模型只输出 `OBSERVE/ACT/WAIT/ASK/PROPOSE_COMPLETE` 的结构化决策。服务端校验工具、参数、资源、预算和状态后执行；计划是可修订的意图快照，不是另一份执行事实。每片段有模型轮次/墙钟上限，未完成则持久化后续推进请求并让出执行权。
- **FR-04 调度与认领**：新增内部 handler `assistant.advance` 与平台周期 handler `assistant.reconcile`，复用 CAP-80 API/主 tick；推进请求只带 taskId、期望 revision 和唤醒编号。服务端以任务 revision + 有期限租约原子认领，推进中的决策、工具派发与结果提交均带 fencing token；失去租约的旧执行者不能发新动作或覆盖任务。CAP-80 run FAILED 不直接等价于助手任务 FAILED。
- **FR-05 动作与作业关联**：所有动作先通过 CAP-97 登记，再派发。作业受理后保存 kind/id、预期目标版本、检查时间和截止时间，置 WAITING_JOB 后退出片段。原有 BUILD/SESSION/DEPLOYMENT/TEST_RUN 状态以所属模块为准，任务表只存引用与最近观察。没有工具能力时不能退回任意 HTTP、SQL 或服务端 shell。
- **FR-06 首批可用界面**：新增个人“助手任务”列表/详情，展示目标、状态、当前动作、计划修订、证据、预算、阻塞和介入入口；普通用户可管理本人任务，且仍受项目当前权限限制。Web 与飞书返回同一 taskId 与详情链接。机器人身份未个人映射时拒绝创建可执行任务，禁止回落 local/defaultActor。

### M2 结果唤醒、纠错与重启恢复

- **FR-07 持久唤醒与补偿扫描**：`assistant_events` 记录唤醒原因及去重键。作业完成事件可经 SPI 提交提示，但最终状态由授权探针核验；`assistant.reconcile` 定期扫描到期等待、未消费事件和过期租约。事件遗漏、事件先到后登记、重复到达、离线重启均能靠探针补偿。相同 task/job/终态版本只产生一次有效状态推进。
- **FR-08 UNKNOWN 与超时**：增加携带可信主体的作业观察封装，区分“临时不可判定”“记录缺失”“权限失效”；旧 JobTerminalProbe 的 UNKNOWN 不包含原因，不能未经适配就声称已区分。已有终态查询可复用，失败详情也必须经带主体的授权读取，不能调用无身份的摘要接口泄露内容。不得无限按 RUNNING 等待；临时状态退避重查，超过任务配置期限进入 WAITING_USER；权限失效立即禁止继续读取或动作。工具调用超时且可能受理时先调用 CAP-97 对账，禁止直接重新启动构建或开发会话。
- **FR-09 失败后重新决策**：作业失败变为输入证据，模型可读取更完整日志、检查环境、提出修复、在授权范围内执行并再次验证。按错误分类限制同类重试与修复次数；连续相同动作/参数/证据指纹无进展达到阈值，停止自动尝试并提出具体问题。权限拒绝、主干冲突、生产动作、无法解释的副作用不按普通瞬时错误重试。
- **FR-10 重启对账**：启动时登记/恢复 CAP-80 内部调度，扫描未终态任务和动作。纯模型决策可重算；已知 jobId 继续查询；派发状态不明的写动作保持 UNKNOWN 等 CAP-97 对账。不能把遗留 RUNNING 全部直接重放。恢复不依赖模型会话内存、线程或客户端在线。
- **FR-11 预算与上下文**：任务记录累计 token、已确认成本、模型估算上界、动作/修复次数和总截止时间。模型调用前预留上界，返回后结算；子会话也必须分配预算并回收实际用量，不能只统计编排模型。迟到结果仍记用量，不得因重试清零。缺少可靠价格时明确“成本未知”，仅能采用 token/动作/时限硬限额，不能声称已保证金额上限。等待期限与活跃计算预算分开，空转不消耗模型调用。初始建议默认：每片段最多 2 次模型调用/90 秒、单任务最多 30 次动作/2 轮修复/2 小时、同类无进展 3 次停止；最终默认值须经样本验证，用户可在平台上限内收紧，任务不能自行上调。

### M3 人工介入、验收与首个业务模板

- **FR-12 暂停/取消/补充**：操作带 revision；暂停停止新派发，已受理作业继续被对账；取消立即禁止后续自主动作，并分别记录底层作业的取消请求/已停止/仍在运行/不支持停止。用户补充或改目标形成新 revision，旧验收结果失效，旧动作仍追溯原 revision；扩大范围必须重新授权。
- **FR-13 目标验收与收口**：模型只能申请完成。服务端调用 CAP-97 verifier，全部必需项 PASS 且无未知副作用/未处置在途动作后才置 DONE；不足则继续补证或等待用户。默认不自动将需求置 DONE，不自动收口合并、推主干、生产部署或发版。结束输出已完成、证据、未完成/风险和需用户决定的事项。
- **FR-14 `repair_build` 模板**：输入项目、失败 buildId、目标需求与允许工作分支；冻结初始提交和失败证据，查询环境与日志，必要时启动受限修复会话，取实际候选提交后显式按 SHA 构建并运行约定检查。若修改配置/选择节点未获授权、所需工具未实现或 runner 无法执行边界，进入 WAITING_USER，不能以提示词替代约束。候选 SHA 无法确认时不得拿默认 HEAD 验收。
- **FR-15 任务记忆与交接**：持久事实含尝试、失败原因、证据引用、待回答问题与外部作业。模型摘要仅为可重建投影，恢复时复核权威事实和当前可见性；不把全量 transcript 永久塞回上下文。经验提案仍归 CAP-79，任务记录不自动发布到知识库。提供人工接管摘要，用户可自行完成操作并提交待验证证据。
- **FR-16 进度与观测**：仅在有意义的阶段变化、阻塞、授权请求和终态推送；无变化扫描保持安静。记录任务完成率、每任务人工“继续”次数、修复轮数、无进展停止次数、重复副作用数及验收证据缺失率；不以调用次数或模型“成功”话术衡量价值。

## 3. 否决方案

| 被否方案 | 理由 |
|---|---|
| 调高 CAP-76 的 max-steps/timeout 即视作自主助手 | 不能解决跨作业续接、重启恢复、授权和目标验收 |
| 在 copilot_actions.args_json 中持续覆写动态计划 | 历史确认参数必须不可变；动态目标与已确认操作不是同一事实 |
| 为助手另建调度框架/统一迁移四大执行器 | CAP-80/既有执行器已有职责，增加调度源与状态迁移风险 |
| 只靠内存 DomainEvent 唤醒 | 丢事件和重启后无法恢复；事件提示必须有持久补偿 |
| 任务恢复直接重新调用上次写工具 | 受理回执可能丢失，重复会话/构建/修改会产生副作用 |
| 所有步骤成功或模型输出“完成”即 DONE | 操作成功与目标达成不同，必须独立验收 |

## 4. 架构与模块归属

新模块 `devmind-assistant`：目标任务、决策推进、等待/恢复、预算、用户 API/页面投影，只依赖 common 的跨能力契约；app 负责装配。

common 新增 `AssistantTaskGateway`、`AssistantWakeSink`、`AssistantDispatchPort`、`AssistantRuntimeControlPort`、`AssistantExecutionPort`、`AssistantVerificationPort` 与可信执行上下文；业务模块通过 SPI 实现，不依赖 assistant 实现。app 中的薄调度桥实现 DispatchPort、注册 CAP-80 的 AsyncTaskHandler 并调用 RuntimeControlPort，复用 task 公开 API，不复制主 tick/线程池。授权/工具适配和验收归 CAP-97；copilot 将明确的委托请求转给 gateway，原短问答与 submit_plan 保持原语义。

```text
Web / 飞书 → AssistantTaskGateway → devmind-assistant
                                  ├─ CAP-80：有界推进与定期对账
                                  ├─ CAP-97：授权 → 动作登记 → 受控工具
                                  ├─ 业务探针：构建/会话/测试结果
                                  └─ CAP-97：证据验收 → 再决策 / 收口
CAP-98 长期委托 ──按周期创建有限任务──┘
```

框架的“LOCAL handler”仅运行模型编排/查询，不能在服务端执行代码、构建或任意脚本。CAP-80 当前人工创建接口 ADMIN-only 保持不变；普通用户经助手业务 API 提交白名单内部 handler，服务端继承用户身份，不借用 ADMIN 或默认账号。周期对账器使用显式登记的内部调度身份，只扫描 taskId/到期/租约等控制元数据；实际读取业务、决策与动作必须切换到任务的当前已验证属主上下文，不能用调度身份读取正文。

## 5. 数据模型

下列为逻辑 DDL 示意；JSON 用 LONGVARCHAR + `@JdbcTypeCode`，不使用数据库专属 JSON 类型。枚举由服务层校验；ddl-auto 仅建新增表/可空引用列，不修改历史计划内容。

```sql
assistant_tasks(id varchar(32) primary key, owner_id varchar(64), domain_code varchar(64),
 project_id varchar(32), requirement_id varchar(32), parent_mission_id varchar(32),
 goal_text text, acceptance_json text, scope_json text, template_code varchar(64),
 task_status varchar(24), revision bigint, budget_json text, usage_json text,
 lease_owner varchar(64), lease_until timestamp, fence_token bigint,
 next_check_at timestamp, deadline_at timestamp, blocked_reason text,
 origin_json text, created_at timestamp, updated_at timestamp);
assistant_decisions(id varchar(32) primary key, task_id varchar(32), task_revision bigint,
 decision_no bigint, decision_type varchar(32), plan_revision bigint,
 input_refs_json text, decision_json text, async_run_id varchar(32), created_at timestamp);
assistant_events(id varchar(32) primary key, task_id varchar(32), wake_key varchar(256),
 event_type varchar(32), payload_json text, consume_status varchar(24), created_at timestamp);
assistant_waits(id varchar(32) primary key, task_id varchar(32), action_id varchar(32),
 job_kind varchar(32), job_id varchar(64), target_revision bigint,
 wait_status varchar(24), next_check_at timestamp, deadline_at timestamp);
```

- 约束：`(task_id, decision_no)`、`(task_id, wake_key)` 唯一；创建幂等键另加 owner/domain/clientRequestId 唯一索引。索引覆盖 status/next_check_at、lease_until、owner/created_at、wait job kind/id。
- `assistant_actions`、grants、evidence、verification 由 CAP-97 唯一管理；decision 只引用 actionId，不复制执行状态。CAP-80 run 记录调度 attempt，不是任务进度事实源。
- revision/fence 必须比较更新；认领、事件落库和等待登记等短事务可以原子提交，但事务不得包住模型/网络/工具调用。所有异步提交在任务/动作持久化完成后进行，且可被补偿扫描重建。
- 单部署域未启用 CAP-63 时固定为服务端部署域；未来租户不可由任务正文自选。新增字段索引需在 H2/PG/MySQL 验证，启动重复初始化应幂等。

## 6. API 概要

| API | 职责 |
|---|---|
| POST `/api/assistant/tasks` | 创建目标与验收快照；不自动授予写权限 |
| GET `/api/assistant/tasks`、`/{id}` | 本人且当前可见范围内列表/详情 |
| GET `/api/assistant/tasks/{id}/timeline` | 决策、动作引用、证据与介入记录分页 |
| POST `/{id}/start`、`/pause`、`/resume`、`/cancel` | revision 校验与生命周期控制 |
| POST `/{id}/input` | 补充信息/修改目标，返回新 revision 与授权变化预览 |
| GET `/{id}/handoff` | 人工接管摘要，重新校验每个证据来源 |
| `AssistantWakeSink.wake(taskId, cause, sourceRef)` | 仅受信内部调用；外部事件先经所属模块鉴权 |

`/{id}` 均为 `/api/assistant/tasks/{id}`。任务控制不复用 CAP-80 ADMIN 管理接口。Web 初版采用分页时间线 + 运行态短轮询，不强制新增 WS 协议。

## 7. 安全

创建、恢复、工具调用、验收、证据读取和发送结果时均重新解析真实用户与资源权限；后台线程显式传播主体，失效拒绝。ADMIN 管理视图不自动获得私有正文；知识依赖 CAP-95 当前授权，未交付前首批验收不接入敏感知识库。

任务授权和预算不能由模型扩大。Runner 执行仍走受控会话；工作树隔离不等于操作系统隔离，CAP-97 未验证的执行能力不得加入无人值守模板。日志/工具内容为不可信数据，不得作为新授权或系统指令。

## 8. 里程碑

| 里程碑 | FR 范围 | 交付边界 |
|---|---|---|
| M1 任务与推进 | FR-01～06 | 持久任务、单步认领、动作引用、用户页；依赖 CAP-97 M1，只有注册过的最小安全工具可运行 |
| M2 持续推进 | FR-07～11 | 等待退出、结果唤醒、失败重决策、重启对账、有界预算 |
| M3 业务闭环 | FR-12～16 | 人工介入、证据收口、repair_build 模板、真实用户验收；依赖 CAP-97 M2/M3 |

首批可用交付是 **CAP-97 M1 → 本 CAP M1/M2 + CAP-97 M2 → 双方 M3 联合验收**；不能把只建任务表或只跑固定计划宣称为自主助手交付。

## 9. 验收标准

以下测试文件为实施目标，当前不宣称已存在/已执行。

| 阶段 | 场景 | 验证载体 |
|---|---|---|
| M1 | 同请求重试只建一个任务；越权用户猜 ID 不能读/控；缺 CAP-97 实现不得开始执行 | `AssistantTaskServiceTest`、`tests/cap96_task_e2e.py` |
| M1/M2 | 两个 worker 争同任务仅一个派发；租约过期旧 worker 不能覆盖或再派发 | `AssistantLeaseTest` |
| M2 | 构建等待期间无长期线程；事件丢失/重复/先到、重启后均能继续且不重复构建 | `tests/cap96_recovery_e2e.py` |
| M2 | 模拟业务已受理但回执丢失，对账拿回 jobId；不可判定时阻塞而非自动重试 | 与 CAP-97 动作对账联合 E2E |
| M2 | 同样失败连续出现触发无进展停止；模型/动作/token 超预算停止且迟到用量计入 | `AssistantBudgetTest`、`AssistantProgressTest` |
| M3 | 用户仅发一次目标；初始构建失败→日志诊断→授权工作区修复→指定候选 SHA 构建及检查通过→带证据完成 | `tests/cap96_repair_build_e2e.py`；隔离 app + 真 runner/真实测试仓库 |
| M3 | 暂停时无新动作；取消后显示仍在跑的底层作业；改目标使旧验收失效；原需求不自动 DONE | `tests/cap96_intervention_e2e.py` |

假模型测试覆盖协议与状态机，不能证明自主判断质量；另用人工冻结的真实构建失败样本验证结果正确性及“下一步”指令次数，至少覆盖代码问题、环境缺工具、权限不足和不可重试失败。真机实验只操作隔离分支与测试环境，结束清理自起进程。

## 10. 风险与开放问题

| 编号 | 风险/问题 | 口径 |
|---|---|---|
| R1 | 副作用与 DB 不能同一事务提交 | 缓解：CAP-97 幂等受理/查询；不明时等待人工，绝不承诺通用 exactly-once |
| R2 | CAP-80 自动重试放大助手写动作 | 缓解：内部 handler 不直接重放写操作，始终按 task/action 状态对账 |
| R3 | 模型循环扩大费用与失败噪声 | 缓解：全任务累计预算、进展指纹、分类重试、只推有意义变化 |
| R4 | 多目标跨项目产生超大编排范围 | 接受：首版仅单项目/单需求；跨项目需另行授权和设计 |
| R5 | CAP-79 旧稿仍以项目自动注入和人工重规划为前提 | 缓解：实现时遵守 CAP-94/95，助手动态计划不自动修改 WI；WI diff 仍走 CAP-79 的显式领域操作 |
| O1 | 默认预算与片段长度 | 待决：用隔离样本测量后确定配置默认值；所有硬限额须在首批开发完成前冻结 |

### 附录：整体规划与已有能力边界

| 能力 | 负责什么 | 与本次规划的关系 |
|---|---|---|
| CAP-76 | 短对话工具调用、不可变确认单、固定 submit_plan | 保持原语义；显式委托才进入本 CAP，不能全局放开旧写工具 |
| CAP-79 | 会话经验提案、会话知识通道、WI 增量规划 | 保留领域边界；本 CAP 记录任务事实并调用领域操作，不另建知识库 |
| CAP-80 | 调度、重试、run 日志 | 复用；run 完成不代表用户目标完成 |
| CAP-95 | 知识授权、版本、需求覆盖/引用质量 | 知识方案 verifier 的领域实现，避免本次重做 RAG |
| CAP-96 | 目标任务、连续推进、等待恢复、人工接管 | 本文 |
| CAP-97 | 授权范围、可靠动作、证据与目标验收 | 最小安全能力是 CAP-96 的前置 |
| CAP-98 | 长期委托、周期去重、上游跟踪试点 | 在可持续任务内核上增加长期责任；漏洞扫描仍由 CAP-66 提供 |

194 需求映射：REQ-4 → CAP-96/97；REQ-5 → CAP-95 的领域检索与验收 + CAP-96 的持续推进；REQ-9 → CAP-98 M1/M2；REQ-10 → CAP-66 + CAP-98 M3 + CAP-96/97；REQ-8 的多人问答时间线仍需独立领域规划，不以新增助手任务冒充交付。
