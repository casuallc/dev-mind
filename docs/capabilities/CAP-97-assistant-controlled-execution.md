# CAP-97 助手任务授权、可靠动作与证据验收

> 能力 ID：CAP-97 ｜ 分类：底座/治理 ｜ 状态：定稿未实施 ｜ 日期：2026-10-27
> 上游能力：CAP-01/62（身份与资源权限）、CAP-76（PlatformTool/确认单）、CAP-34/71（Runner/审计）、CAP-67（用量）；服务 CAP-96 任务与 CAP-98 长期委托，知识领域遵守 CAP-95。
> 决策记录：
> 1. **任务授权不等于账号权限**：有效许可为当前账号权限、资源范围、有效任务授权与平台动作策略的交集；后台执行不借用 ADMIN/local/default 服务身份。
> 2. **授权的是有限操作范围，不是模型未来任意计划**：用户确认服务端规范化的范围、工具、条件、预算与期限；每次实际动作再次检查。主干合并/推送、生产部署/发版、永久删除和安全配置变更首版始终逐次人工确认。
> 3. **复用工具注册表，增加任务适配契约**：旧 ToolSpec/ToolResult/确认单默认行为不变；未声明任务能力、幂等/对账、结果与范围约束的工具不能无人值守运行。
> 4. **先记动作，再派发；超时不等于未执行**：承认跨系统受理的不确定性，以接收端持久幂等键、业务回执查询和安全阻塞处理，不能只靠网关锁防重复。
> 5. **验收结果独立于执行者的完成声明**：结果须绑定目标 revision、代码提交/制品或文档来源；UNKNOWN/缺证据不算 PASS。
> 6. 所有新增契约均为目标设计；本 CAP 不改变 CAP-76 已交付写必确认路径，只有显式授权的 CAP-96 任务走新增执行链。

## 1. 目的

解决连续助手的两个阻碍：用户必须逐动作确认，导致任务断链；一旦自动执行，又容易越权、重复产生副作用或把“已提交”说成“已完成”。

本能力提供可撤销的任务级授权、可对账的动作流水、足够完整的证据读取和可解释的目标验收，让“允许在这个需求工作区修复并验证”的授权能够被实际执行和审计。

不做新的全站 RBAC/组织目录、不开放模型任意 HTTP/SQL/shell、不替代业务 Service 状态机、不把工作树当安全沙箱、不承诺模型生成内容绝对正确。

## 2. 功能需求

### M1 身份、授权与最小受控动作

- **FR-01 可信任务执行上下文**：定义服务端生成的 TaskExecutionContext，含 actor、域、taskId、taskRevision、grantId/revision、actionId、fenceToken、实际资源、来源及接收范围。模型参数不能覆盖这些字段。后台线程显式传播身份，账号禁用/角色或成员变更后立即影响下一次操作。
- **FR-02 授权预览与签发**：用户在 Web 或个人已映射的飞书身份下确认规范化授权快照。范围必须明确到项目、仓库、允许分支/路径、允许节点/环境、工具白名单、参数限制、动作次数/预算、有效期与成果接收范围；首版不接受任意表达式和默认全资源通配符。任务创建本身不签发授权，模型不能签发、延期或增加权限。
- **FR-03 动作分类与逐次闸门**：READ 也必须检查可读资源及模型出口。明确注册的非生产 WRITE 可在任务授权内执行；DANGEROUS、主干合并/推送、生产部署/发版、永久删除、安全策略或凭据变更首版均生成具体参数确认单。确认沿用 copilot_actions 的参数快照、短码和原子消费；关联 taskId/actionId，确认后恢复任务，不能将一次危险确认升级成通用许可。
- **FR-04 执行前/释放前复核**：派发前比较 taskRevision、grantRevision、租约 fence、实时账号及实际资源；业务受理端也检查任务动作是否仍可受理，拒绝过期执行者的新派发。资源归属由 Service/adapter 反查，不能只信模型 projectId 或 bot 默认项目。模型调用、证据读取、结果外发也分别复核。授权缺失/失效/策略实现缺失/异常均拒绝，不能通过 ObjectProvider 缺 bean 放行。
- **FR-05 撤销与变更**：授权 ACTIVE/REVOKED/EXPIRED，撤销立即阻断新派发并唤醒任务；已开始作业仅能尽力停止，界面明确残留影响。续期/扩权必须人工确认新修订；缩权即时生效。等待中的旧确认单在消费时复核任务/授权/参数，失效确认返回明确原因，不执行旧目标。
- **FR-06 动作登记与最小执行链**：每个动作派发前落 `assistant_actions` 与全局 operationKey；记录 task/decision、实际参数摘要/hash、工具契约版本、授权修订、fence 和动作状态。首批只开放已适配只读诊断工具及一个有接收端幂等受理的测试构建工具；同时交付最小规则验收（诊断结果来源可读、或指定 SHA 的构建终态成功），缺证据不能完成。尚未支持的目标模板不得启动，完整 repair_build 在 M3 开放。工具执行走 task 专用受控入口，保留 CAP-76 的 schema/风险/资源检查与审计，不通过伪造确认单调用原执行链。

### M2 工具契约、受理对账与证据通道

- **FR-07 TaskToolAdapter 契约**：在 common 注册每个可用于任务的工具适配器，声明 toolName/contractVersion、scope 解析与校验、执行形态、幂等策略、对账能力、取消能力、输出 schema、证据类型与约束。execute 仍委托原工具所属 Service；不在 assistant 模块下探业务 Repository。重复注册/缺关键策略在启动或模板发布时拒绝。
- **FR-08 标准结果封装**：在旧 ToolResult 外增加任务专用 TaskActionResult，含 `phase=COMPLETED/ACCEPTED/FAILED/UNKNOWN`、jobRef、typedOutputs、evidenceRefs、errorCategory、retryHint。ACCEPTED 仅代表已受理；UNKNOWN 代表副作用不明。模型获得经过白名单筛选的结构化字段及证据引用，不直接暴露整份 data、原始路径或凭据。
- **FR-09 幂等与结果不确定窗口**：副作用工具必须将 operationKey 在业务受理端持久化，并能按同主体/key 查询原回执；关键资源创建与幂等映射需同业务事务提交。崩溃发生于“已受理、网关未保存回执”时 recover 查回原 jobId；相同 key/不同参数必须冲突。同一逻辑动作的传输重试复用 key，诊断后产生的新修复动作才用新 key。无幂等/对账能力的副作用工具只允许逐次人工处置，不能自动重试。
- **FR-10 动作状态机与恢复**：`PREPARED → DISPATCHING → ACCEPTED / SUCCEEDED / FAILED / UNKNOWN`，ACCEPTED 经作业探针转终态；确认前为 WAITING_CONFIRM。DISPATCHING 崩溃遗留必须对账，不能视为未执行。UNKNOWN 不得导致目标 DONE、自动重复调用或简单置“已取消”。状态更新比较 action revision/fence，迟到回执仅补充事实，不复活已撤销授权或旧任务目标。
- **FR-11 证据读取工具**：按场景补齐构建/测试分页日志（cursor、总量、截断标志）、会话状态与产出、代码 diff/候选 SHA、测试报告、制品来源和节点工具链只读诊断；知识原文/邻节/引用由 CAP-95 提供。原始内容走受控分页/附件引用，大日志可按错误附近补读；证据被截断必须明示，不能仅凭尾部几十行作完整结论。adapter 保留当前账号与来源资源校验。
- **FR-12 子会话授权传递与 Runner 边界**：start_session 接收服务端签发的能力子集（任务/需求工作区、工具/命令、凭据用途、有效期），且不可宽于父授权。仅靠 taskSpec 提示词和 acceptEdits 不算边界。模板启用前必须验证节点 OS 账户/工作目录访问、Git 凭据分支权限、网络出口及 CLI 权限机制能约束所开放的动作；具体限制由受支持的专用执行配置落实并输出能力清单。不能落实的能力不予自动授权，回退人工会话/受控具体动作，不开放任意终端。取消/撤权需下发停止并显示是否真正停止。
- **FR-13 分类重试与预算**：区分参数错误、权限拒绝、业务冲突、瞬时失败、资源缺失和副作用未知。只有明确无副作用或能对账/幂等的瞬时失败可自动重试。次数预算派发前原子预留，运行费用由 CAP-96 统一累计；拒绝或无效参数不应消耗实际写动作额度。外部调用响应丢失的预留不释放，待对账结算。

### M3 独立验收与交付记录

- **FR-14 验收契约**：TaskVerifier 输入任务 revision、冻结验收条件、候选产物和证据引用，输出逐项 `PASS/FAIL/UNKNOWN/NEEDS_HUMAN`、依据和建议补证。必需项不能由模型删除或改成可选；模型可提出新标准，但改变完成要求必须经用户确认并增加 taskRevision。验收适配器随模板发布，未注册不能启用模板。
- **FR-15 证据绑定与有效性**：证据记录来源资源、revision/commit/artifactHash/release、位置、采集时间、内容 hash 和授权依赖。修复验收中的构建、测试、diff 必须对应同一候选提交/制品；新提交或环境变化使对应旧检查失效。链接失效、原文不可读、权限撤销或版本不匹配返回 UNKNOWN，不能把历史成功复用为当前 PASS。
- **FR-16 `repair_build` 验收器**：至少检查原失败事实、实际变更或经授权的环境修复记录、候选 SHA/配置版本、该候选构建成功，以及用户要求的测试/复现检查结果。构建成功仅证明构建问题被解除，不能替代功能行为验证；需要人工业务验收时返回 NEEDS_HUMAN，先标“待验收”，不将需求置 DONE。涉及全局工具安装、共享配置修改、节点切换时分别检查授权。
- **FR-17 领域验收扩展**：CAP-95 负责方案的需求覆盖、适用版本、证据支持和冲突检查；CAP-98 负责一次上游巡检的比较范围与游标有效性。统一框架只负责调用、记录和收口，不用同一个 LLM 打分替代所有业务验收。模型检查须标明为模型判断，保留不确定性及人工复核要求。
- **FR-18 交付记录**：生成可追溯的目标/变更/执行/验收/剩余风险清单，作为任务视图和通知的数据来源；已完成动作、尚在跑动作、未执行事项分别展示。记录验收器版本、目标 revision 和证据引用，重新验收生成新记录。对外发送前再次校验接收范围，默认仅发状态摘要和授权详情链接。

## 3. 否决方案

| 被否方案 | 理由 |
|---|---|
| 给助手一个平台 ADMIN 服务账号 | 丢失用户权限边界，后台执行与数据读取均可能越权 |
| 用户确认“自动执行”就允许任意工具 | 无法表达资源/环境/预算/期限，未来计划可无限扩权 |
| 全局取消 CAP-76 写必确认 | 破坏既有短对话与危险操作安全语义 |
| 只在网关记幂等键 | 无法覆盖业务已受理但回执丢失的窗口，接收端也须支持 |
| 工具超时一律重试/作业 SUCCESS 即任务成功 | 可能产生重复副作用，且成功对象/版本可能不匹配 |
| 使用工作树与提示词作为完整执行隔离 | 不约束工作区外文件、网络、凭据与共享环境变更 |

## 4. 架构与模块归属

- common：TaskExecutionContext、TaskToolAdapter、TaskActionResult、TaskVerifier、EvidenceRef、OperationReceipt 查询 SPI；保留旧 ToolSpec/ToolResult 的构造与默认语义兼容。
- devmind-assistant（CAP-96 同一模块）：任务 grants/actions/evidence/verifications、授权策略与执行漏斗；本 CAP 是该模块的治理切面，不为每份 CAP 再拆 Maven 模块。
- devmind-copilot：开放工具注册查询和已有执行检查的复用接口，提供危险确认单关联；不让 assistant 依赖 copilot 内部 Repository/实现类，跨模块经 common SPI。
- build/session/test/agent 等模块：分别实现工具、受理幂等/查询、探针、证据与取消适配；runner 继续负责实际代码/脚本执行。
- knowledge：CAP-95 实现知识证据与领域验收；frontend/features/assistant 展示授权、证据与交付记录，bot 使用统一业务接口。

## 5. 数据模型

逻辑 DDL 示意，新增表由统一 JPA 约定建模；所有正文/JSON 用 LONGVARCHAR，唯一键与并发条件在服务层和数据库共同约束。

```sql
assistant_grants(id varchar(32) primary key, owner_id varchar(64), domain_code varchar(64),
 task_id varchar(32), task_revision bigint, mission_id varchar(32), mission_revision bigint,
 parent_grant_id varchar(32),
 grant_status varchar(24), revision bigint, scope_json text, limits_json text,
 expires_at timestamp, confirmed_by varchar(64), confirmed_at timestamp, revoked_at timestamp);
assistant_actions(id varchar(32) primary key, task_id varchar(32), task_revision bigint,
 decision_id varchar(32), grant_id varchar(32), grant_revision bigint, fence_token bigint,
 operation_key varchar(128), tool_name varchar(128), contract_revision varchar(64),
 args_json text, args_hash varchar(64), action_status varchar(24), action_revision bigint,
 job_kind varchar(32), job_id varchar(64), confirm_action_id varchar(32),
 result_json text, error_category varchar(32), created_at timestamp, updated_at timestamp);
assistant_evidence(id varchar(32) primary key, task_id varchar(32), action_id varchar(32),
 source_kind varchar(32), source_id varchar(128), source_revision varchar(256),
 location_json text, content_hash varchar(64), access_refs_json text, captured_at timestamp);
assistant_verifications(id varchar(32) primary key, task_id varchar(32), task_revision bigint,
 verifier_code varchar(64), verifier_revision varchar(64), verdict varchar(24),
 checks_json text, evidence_refs_json text, created_at timestamp);
```

- `operation_key` 全局唯一；业务受理端增加 `(domain,actor,operation_key)` 唯一映射，参数 hash 与 jobRef 同受理事务保存。不能向有业务事实表的模块复制一份作业状态表。
- `task_id/mission_id` 授权目标恰好一个非空；子任务引用 mission 授权时签发受限子授权并保留父链，父撤销立即使全部子授权无效。
- 持久化参数和证据应脱敏、受访问控制；敏感全文不直接进入任务审计列，引用原存储/受控附件。内容哈希不能代替权限，也不能证明业务结论真实。
- 业务模块只加可空 operation_key/receipt 关联或新增映射表；存量数据不自动授权、不伪造幂等回执。新增唯一约束上线前输出冲突检查；ddl-auto 之外的数据回填需幂等应用级初始化，不要求人工改生产数据。

## 6. API 概要

| API/契约 | 职责 |
|---|---|
| POST `/api/assistant/tasks/{id}/grant-preview` | 规范化拟授权范围、工具与风险；不能签发 |
| POST `/api/assistant/tasks/{id}/grants` | 已认证属主确认签发；绑定预览 hash/目标 revision |
| POST `/api/assistant/grants/{id}/revoke` | 立即撤销并通知任务，不保证回滚已经发生的动作 |
| GET `/api/assistant/tasks/{id}/actions`、`/evidence`、`/verifications` | 授权范围内分页记录 |
| POST `/api/assistant/tasks/{id}/verify` | 人工申请重验，不接受客户端写 verdict |
| POST `/api/assistant/tasks/{id}/confirmations/{actionId}/confirm`、`/reject` | 复用确认状态机；Web/飞书同一原子消费路径 |
| `AssistantExecutionPort.prepare/execute/recover/cancel` | 任务内受控动作入口，无公网任意工具调用接口 |
| `OperationReceipt.lookup(operationKey, trustedContext)` | 业务端按真实身份对账；不能让用户枚举他人回执 |

## 7. 安全

资源授权、任务授权、模型出口和消息接收范围是四项独立检查；读操作不免除它们。CAP-95 未交付前不得用任务授权绕过知识资源权限。机器人群内用户有权读取，不等于群全员获准接收全文。

防止参数别名/大小写、路径穿越、符号链接、分支重定向和环境名称伪装绕过范围；实际资源归属在执行点验证。注册模板必须声明需要的节点能力，无法验证执行隔离时拒绝自动启动子会话。授权记录、工具内容和模型输出均不能成为扩大权限的依据。

## 8. 里程碑

| 里程碑 | FR 范围 | 交付边界 |
|---|---|---|
| M1 最小受控执行 | FR-01～06 | 显式授权、逐次危险确认、动作登记与最小规则验收；与 CAP-96 M1 联合，只读诊断及已适配测试构建 |
| M2 可靠工具与证据 | FR-07～13 | 完整受理对账、结果封装、日志/产出读取、子会话边界、预算与分类重试 |
| M3 验收收口 | FR-14～18 | repair_build 验收、版本一致证据、交付记录；与 CAP-96 M3 联合交付 |

M1 就必须实现首个写工具的接收端幂等回执，不能以“M2 后续支持”为由开放不可靠自动重试。M2 的 Runner 能力检查是无人值守代码修复的前置。

## 9. 验收标准

测试名为计划新增，不表示已运行。

| 阶段 | 场景 | 验证载体 |
|---|---|---|
| M1 | 未授权不能写；授权内测试构建可连续执行；PROD/主干推送/任意 shell 仍被拦；旧 CAP-76 写调用仍需确认 | `AssistantGrantPolicyTest`、`tests/cap97_grant_e2e.py`、CAP-76 回归 |
| M1 | 授权撤销/过期/角色变化/任务 revision 变化后下一动作拒绝；伪造 actor/grant/项目归属无效 | `AssistantExecutionContextTest` |
| M2 | 接收端已受理后人为丢回执/杀进程，恢复仅一条业务作业；同 key 不同参数 409；无对账支持时阻塞 | `tests/cap97_receipt_recovery_e2e.py` |
| M2 | 大日志分页无缺口且截断可见；未知异步结果不显示完成；跨用户读报告/附件/回执拒绝 | `AssistantEvidenceAccessTest` |
| M2 | 真 runner 子会话不能越出已声明能力范围；无隔离能力节点拒绝自动模板；撤销后显示真实停止结果 | `tests/cap97_runner_scope_e2e.py`，隔离节点/测试仓库 |
| M3 | 候选 SHA=A，只有 B 的成功构建/测试不能通过；模型说完成但缺日志/复现结果不能 DONE | `AssistantVerifierTest` |
| M3 | 新目标 revision/证据撤权/来源版本变化使旧验收失效；需人工验收项保留 NEEDS_HUMAN | `tests/cap97_verification_e2e.py` |

所有恢复实验在隔离实例/测试节点进行；副作用次数从业务源表和实际仓库核对，不能仅根据助手日志自证“无重复”。

## 10. 风险与开放问题

| 编号 | 风险/问题 | 口径 |
|---|---|---|
| R1 | 旧工具成功只代表受理，数据字段未充分传模型 | 缓解：显式 adapter 与结果封装，逐工具接入，不批量自动放开 |
| R2 | 接收端无法提供幂等/对账 | 接受：该动作不进入自动重试集合，UNKNOWN 需人处理 |
| R3 | Runner 用过宽账号/凭据运行 | 缓解：专用执行配置与能力测试是模板前置；未满足不自动编码 |
| R4 | 模型验收自证正确 | 缓解：规则/领域证据为主，模型判断显式标注，业务无法自动验证时保留人工项 |
| O1 | 首批 runner 限制采用的具体平台实现 | 待决：以现有 Windows/Linux 节点做能力验证后冻结支持矩阵；首批上线前须关闭此项 |
| O2 | 全任务金额上限的精度 | 接受：无可靠计价上界时只提供 token/动作/时限硬限额及成本未知标识，不承诺精确金额封顶 |
