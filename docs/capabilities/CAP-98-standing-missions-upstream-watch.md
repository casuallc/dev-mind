# CAP-98 长期委托与主动跟踪（上游变更首个场景）

> 能力 ID：CAP-98 ｜ 分类：组装层/流程层 ｜ 状态：定稿未实施 ｜ 日期：2026-10-27
> 上游能力：CAP-96/97（持续助手任务与受控执行）、CAP-80（调度）、CAP-29/18/22（仓库及平台）、CAP-06/75（通知）、CAP-62（资源权限）；漏洞消费依赖 CAP-66，知识方案依赖 CAP-95。
> 需求来源：194 dev-mind 项目 REQ-9（上游跟踪）与 REQ-10（漏洞提醒及修复闭环）。REQ-9 明确只做感知与评估、不自动推送代码，本 CAP 保持此边界。
> 决策记录：
> 1. **长期委托与单次任务分开**：mission 保存长期目标、周期和处理台账；每次巡检生成一个有限的 CAP-96 task。本次检查完成不等于长期委托完成。
> 2. **先做只读上游跟踪，验证主动履职**：定期比较、影响评估、安静去重、处理反馈；不把自动 merge/cherry-pick/push 放入首个场景。
> 3. **复用 CAP-80 周期调度和 CAP-96 运行机制**：不再建一套 cron/工作流引擎；周期重复触发也只产生一个业务轮次，长期状态可恢复。
> 4. **游标、观察、评估和通知分开记**：扫描成功才能推进游标；评估未完成可以后续补做；发消息失败只重发，不重新扫描/创建问题。
> 5. **漏洞扫描归 CAP-66，方案检索归 CAP-95**：本 CAP 只消费可信发现、管理长期跟踪和后续任务关联，不重造扫描引擎或 RAG。
> 6. 本文均为目标设计；本次仅立项，不新建真实监控、不发送通知、不自动创建线上需求。

## 1. 目的

让用户能够委托“持续跟踪这个上游维护分支，发现影响企业版的安全修复或破坏性变化时告诉我”，系统随后定期履职、保存检查进度、评估具体影响，并记住哪些事项已经处理，不再等待用户每次主动查询。

首版面向多仓库中的一个明确上游/企业版分支对。先证明长期可恢复、判断有依据、通知不刷屏；后续增加多源及漏洞到需求的关联。多人技术支持问答（REQ-8）是独立领域，不在本 CAP 建帖子/讨论状态机。

## 2. 功能需求

### M1 长期委托、周期执行与台账

- **FR-01 委托定义与确认**：创建 mission，记录目标、属主、数据源、关注类型、周期/时区、通知规则、有效期、每周期预算与跨周期总限额、成果接收范围。Web/飞书展示规范化预览并由用户确认启用；请求中明确的“一次查看”只建 task，不暗中变成长期监控。
- **FR-02 生命周期与轮次**：mission 状态 ACTIVE/PAUSED/EXPIRED/CANCELLED，另有 lastCheckStatus/healthReason 表示执行健康，不将一次失败直接取消长期委托。每次触发登记一个 cycle（QUEUED/RUNNING/DONE/FAILED/SKIPPED），引用一个 CAP-96 task。ACTIVE 只有当前有效授权下才能产生新任务；任务使用受限子授权，父授权失效时阻断。
- **FR-03 调度与重叠**：注册 CAP-80 内部 handler `assistant.mission_tick`，payload 只引用 missionId/revision；首次运行、定时和手动 check-now 都经同一轮次登记。默认不重叠：已有在途 cycle 时合并新触发并记 skipped，不并行重复扫描。修改周期更新服务端规范化规则；CAP-80 ADMIN 人工调度接口权限不变。
- **FR-04 重启与补检**：持久保存 cycle、源游标和未处理观察。重启后恢复原任务，扫描期间宕机不能清空游标；漏过多个周期只合并补检一次，不为每个错过时间点连续刷任务。任务创建与 cycle 关联有幂等 requestKey，关联保存前崩溃可从 requestKey 找回任务。
- **FR-05 观察/处理台账**：每条变化有稳定 sourceKey、首次/最后发现、证据引用、处理状态与结论。状态 OPEN/ACKNOWLEDGED/IGNORED/RESOLVED；忽略需原因与适用范围/可选期限，重新出现但内容或受影响版本发生实质变化时可重开。相同观察可关联多个评估 revision，不能因通知成功直接标为已解决。
- **FR-06 安静与失效提醒**：无新增重要变化不发日常状态；重要性、适用范围或处理状态发生有意义变化才通知。检查失败、凭据失效、授权过期等运营问题用独立去重键，首次及升级/恢复时通知；不能在长期检查失败时持续显示“没有风险”。静默时段按用户时区推迟普通通知，紧急覆盖规则启用时须明示。

### M2 上游变更跟踪与影响评估

- **FR-07 数据源和比较基线**：登记已授权上游仓库/分支及企业版仓库/维护分支，服务端解析真实仓库 ID/凭据，不接收模型任意 URL 抓取。每轮执行 CAP-26 fetch，冻结本轮 upstream SHA、enterprise SHA、上次已扫描 SHA 和源状态。首次启用先展示初始化范围，默认当前 HEAD 为基线；历史补检必须显式指定时间/提交范围及预算。
- **FR-08 变化发现与可证明的扫描完成**：检测新 commit/tag/release，分页保留遍历位置和限流游标。取完既定范围并持久化观察后才能推进扫描游标；超量/分页中断仅保存扫描进度，标部分完成，下周期续查，不能跳到新 HEAD 丢变化。force-push/分支删除/历史不连通时报告基线失效并请求重设，禁止按“零新增”处理。
- **FR-09 差异计算**：Git 操作经 runner 或已有受控仓库适配器执行，固定 SHA 后计算 merge-base、双方 commit 差集、可快进与文件差异；不同仓库找不到共同祖先时明确“无法按祖先关系比较”，不虚构 ahead/behind。冲突评估在一次性工作树中模拟并回收，不污染用户工作区；无法运行模拟时只报告“潜在冲突文件”，不能声称实际合并成功。
- **FR-10 影响评估与补查**：CAP-96 任务结合提交 diff、关联 issue/release、企业版实际差异和获准知识进行评估，按安全修复/bugfix/feature/breaking/未知分类，并写影响模块、证据、适用性、建议和不确定性。模型可补读相关文件与测试信息，读取受授权和预算约束；上游自称“安全修复”只是线索，缺证据时标待复核。无关变化也保留紧凑结论，避免下轮重复分析。
- **FR-11 通知与人工结论**：默认只通知新的高影响发现或已有发现显著升级；合并建议为“建议评估合入/暂不建议/需人工判断”，附双方 SHA、原因、潜在冲突与详情链接。用户可确认已处理/忽略并说明原因；不自动推代码、不自动确认实际合入。检测到相关修复提交也要核对分支和验证依据，不能仅按相似标题判解决。
- **FR-12 每轮验收器**：`upstream_watch` verifier 检查数据源读取成功、比较 SHA 固定、既定范围分页完成、观察持久化、评估覆盖与游标一致。扫描完成但评估预算用尽时保留待评估队列，不宣布“全部已评估/无风险”；一次 task 仅在声明的本轮验收条件完成后 DONE，mission 继续 ACTIVE。

### M3 发现到需求的受控处理与漏洞接入

- **FR-13 从发现创建后续任务/需求**：用户可从台账确认“建需求”或“安排修复评估”，在 CAP-97 授权下创建关联对象。默认只读 mission 不包含自动建单许可；允许配置精确的“高危且确认影响本项目 → 创建需求草稿”策略，需单独授权项目、次数和去重规则。现有链接优先复用；禁止仅凭模型决定把项目全部风险自动派给开发。
- **FR-14 CAP-66 漏洞适配**：只消费 CAP-66 的扫描结果/fingerprint、扫描完整性、受影响版本与误报/接受状态；定时扫描触发通过新增领域适配器实现，不修改 CAP-66 手动/API 语义来假称已有定时能力。扫描不完整或漏洞数据源过期时标检查不完整，不能把 Finding 数量为零等同无漏洞。首批接入一个已验证 SCA 引擎即可，不以全部引擎完成为前置。
- **FR-15 闭环关联与复验**：观察关联需求/工作项/修复任务/验证证据。任务完成不直接把漏洞标 RESOLVED；必须复扫同一目标制品/提交、确认目标版本不再受影响，或记录有权限用户显式风险接受。重启/并发回调/重复发现不重复建单；删除或撤销关联对象保留历史并提示待处置。
- **FR-16 长期结果与经验**：页面展示最后成功检查、覆盖截止点、未处理重要发现、失败健康状态和后续进度。按需生成汇总，事实来自 cycle/观察/证据，不把模型摘要作为唯一台账。可将已确认结论提交 CAP-79 知识提案，仍需人工采纳及 CAP-94 显式选库/CAP-95 授权，不直接自动公开。

## 3. 否决方案

| 被否方案 | 理由 |
|---|---|
| 一个无限 RUNNING 的助手任务永久盯仓库 | 无法清晰区分周期、预算、恢复和本次验收 |
| 每次 cron 直接 LLM 搜索并发送消息 | 无检查游标、证据覆盖、去重和失败恢复，难以持续履职 |
| 用通知已发送作为已处理状态 | 提醒不代表风险已解决，重发也不应重做业务操作 |
| 先自动 merge/push 才算主动助手 | 超出 REQ-9 边界，且无法先验证只读评估价值 |
| 重建一套漏洞扫描与误报治理 | 与 CAP-66 职责重叠，应消费同一发现模型 |

## 4. 架构与模块归属

- devmind-assistant：mission、cycle、观察/评估/处理台账、通知发件箱、CAP-96 子任务组织；CAP-80 触发复用 CAP-96 在 app 装配的薄调度桥，不在 assistant 反向依赖 task 实现。
- common：`MissionSourceAdapter`（读取/游标/证据）、`MissionAssessmentTemplate`、`MissionFindingLinkPort`、通知去重引用等 SPI；adapter 按 type 唯一注册。
- integration/project/agent：仓库身份与只读 Git 平台/runner 适配；Git 执行不搬到服务端 shell。security（CAP-66 目标模块）实现漏洞来源 adapter；业务模块通过 SPI 关联需求和验证。
- frontend/features/assistant：任务与长期委托两个视图，委托详情含周期记录、发现、处理和健康信息；飞书卡片与 Web 调用同一 API。

本 CAP 不是全站监控/告警平台；一个 mission 首版只允许一种明确 sourceType 和授权范围，多源聚合可用多个 mission 后续组合。

## 5. 数据模型

逻辑 DDL 示意；文本/JSON 沿用仓库跨数据库 JPA 映射。

```sql
assistant_missions(id varchar(32) primary key, owner_id varchar(64), domain_code varchar(64),
 project_id varchar(32), mission_status varchar(24), revision bigint,
 source_type varchar(64), source_config_json text, objective_json text,
 schedule_json text, limits_json text, notification_policy_json text,
 async_task_id varchar(32), last_check_status varchar(24), health_reason text,
 last_success_at timestamp, expires_at timestamp, created_at timestamp, updated_at timestamp);
assistant_mission_cycles(id varchar(32) primary key, mission_id varchar(32), mission_revision bigint,
 cycle_key varchar(128), task_id varchar(32), cycle_status varchar(24),
 scan_range_json text, cursor_before_json text, cursor_after_json text,
 created_at timestamp, finished_at timestamp);
assistant_mission_cursors(id varchar(32) primary key, mission_id varchar(32), source_key varchar(256),
 cursor_json text, revision bigint, updated_at timestamp);
assistant_observations(id varchar(32) primary key, mission_id varchar(32), source_key varchar(256),
 content_hash varchar(64), observation_status varchar(24), severity varchar(24),
 evidence_refs_json text, assessment_json text, resolution_json text,
 first_seen_at timestamp, last_seen_at timestamp, revision bigint);
assistant_observation_links(id varchar(32) primary key, observation_id varchar(32),
 target_kind varchar(32), target_id varchar(64), link_key varchar(128), created_at timestamp);
assistant_notification_outbox(id varchar(32) primary key, mission_id varchar(32),
 observation_id varchar(32), delivery_key varchar(256), target_ref_json text,
 notice_status varchar(24), payload_refs_json text, attempt_count integer,
 next_attempt_at timestamp, delivered_at timestamp);
```

- 唯一约束：`(mission_id,cycle_key)`、`(mission_id,source_key)` 的 cursor/observation、`link_key`、`delivery_key`；索引覆盖 owner/status、cycle/taskId、outbox status/next_attempt_at。
- Git sourceKey 包含规范化 repoId、ref/比较对和 commit/release 标识；漏洞键引用 CAP-66 fingerprint 与受影响目标，不能仅按 CVE 全局去重而隐藏不同项目风险。
- 游标推进与观察持久化在短事务内比较 revision；评估和消息网络调用在事务外。通知发件箱仅存业务证据引用，发送前重新授权，失效不外发正文。
- 通知尽力去重；外部通道发送成功但回执丢失时不能承诺绝不重复，优先使用通道幂等能力，缺失时记录不确定投递并抑制盲重发。
- schema 初始化仅新增表；旧定时任务/通知不自动改成 mission，旧授权不隐式扩大。

## 6. API 概要

| API | 职责 |
|---|---|
| POST `/api/assistant/missions/preview`、`/missions` | 预览/创建未启用委托，确认目标与周期 |
| GET `/api/assistant/missions`、`/{id}` | 本人且当前资源可见的委托列表/详情 |
| POST `/api/assistant/missions/{id}/grant-preview`、`/grants` | 复用 CAP-97 策略签发长期许可，任务获得受限子许可 |
| POST `/{id}/activate`、`/pause`、`/resume`、`/cancel`、`/check-now` | 生命周期/手动检查，含 revision 和幂等键 |
| GET `/{id}/cycles`、`/observations` | 本轮覆盖、健康信息、观察与结论 |
| POST `/api/assistant/observations/{id}/acknowledge`、`/ignore`、`/resolve` | 有原因的人工处理；resolve 记录人工结论或引用验证证据 |
| POST `/api/assistant/observations/{id}/follow-up-preview`、`/follow-ups` | 预览/确认建立需求或后续任务，CAP-97 再次检查 |

省略前缀的 `/{id}` 均为 `/api/assistant/missions/{id}`。所有源配置保存时校验仓库/分支/凭据用途，不允许客户端提交任意执行脚本。

## 7. 安全

长期授权有期限、可撤销，每轮启动及每次读取/调用/发送都重校验账号、资源、父授权与接收范围。凭据仅引用既有账号体系，权限不足进入健康异常与待介入，不换用平台高权限账号补救。

上游 commit/release/issue 文本和 diff 都是不可信输入，不能命令助手扩大监控范围、发送私有代码或执行脚本。合并模拟不执行上游构建脚本，独立工作树检查后回收；需要运行测试时另建显式授权任务。首版通知优先摘要和详情链接，禁止把私有差异直接发到未授权群。

## 8. 里程碑

| 里程碑 | FR 范围 | 交付边界 |
|---|---|---|
| M1 长期委托基础 | FR-01～06 | 周期、台账、恢复、授权过期、静默/失败提醒；依赖 CAP-96/97 已具备可靠单次任务 |
| M2 上游跟踪试点 | FR-07～12 | 单上游/企业版比较对，新增变化→差异评估→重要提醒→处理反馈；不推代码 |
| M3 风险后续闭环 | FR-13～16 | 受控建单/后续任务、CAP-66 漏洞接入与复验；不以 CAP-66 未交付阻塞 M1/M2 |

建议实施顺序：CAP-96/97 首个修复任务闭环 → 本 CAP M1/M2 → 按真实使用反馈扩展 CAP-95 方案任务与 CAP-66/本 CAP M3。REQ-9 不以完整安全扫描平台或多人问答先上线为前置。

## 9. 验收标准

以下为计划新增测试，不表示已存在/已执行。

| 阶段 | 场景 | 验证载体 |
|---|---|---|
| M1 | 一次确认后自动检查；重复 tick/手动重叠只建一个 cycle/task；暂停/过期不再执行 | `StandingMissionServiceTest`、`tests/cap98_mission_e2e.py` |
| M1 | 重启漏过多个周期只合并补检一次；创建任务后关联前崩溃不重建任务 | `tests/cap98_recovery_e2e.py` |
| M1 | 无变化不通知；首次检查失败有健康提醒；重复失败不刷屏，恢复有一次提示 | `MissionNotificationPolicyTest` |
| M2 | 测试上游新增安全修复与无关提交，冻结双方 SHA 后能解释影响/证据；相同提交第二轮不重分析/重通知 | `tests/cap98_upstream_e2e.py`；本地 bare 测试库 + 真 runner |
| M2 | 分页中断、限流、超预算、force-push、分支删除和无共同祖先均不丢变化、不宣称零风险 | `UpstreamCursorTest`、`UpstreamAssessmentTest` |
| M2 | 用户确认忽略并记录原因；随后实质变化重新评估；仓库与维护分支无任何自动推送 | 业务源 Git 引用对账、任务证据与人工抽查 |
| M3 | 同发现重复触发仅一条需求；无建单授权只出建议；扫描不完整/数据库过期不清除风险 | `tests/cap98_findings_e2e.py` + CAP-66 adapter 测试 |
| M3 | 修复任务结束但无匹配目标复扫时不自动解决；复扫证据一致才可关闭，人工风险接受单独标识 | `MissionResolutionVerifierTest` |

真实质量评估另冻结一批上游安全/普通修复/破坏性/无关变更样本，人工标注与企业版关系，观察重要变化漏报、无关提醒、证据充分性与人工处理成本。假模型通过只能证明调度/协议，不证明影响判断准确。

## 10. 风险与开放问题

| 编号 | 风险/问题 | 口径 |
|---|---|---|
| R1 | 以 commit message 判断真实安全影响 | 缓解：diff/关联证据/企业版范围共同判断，不确定时显式待复核 |
| R2 | 大仓库历史补查耗时与 API 限流 | 缓解：明确初始化范围、分页游标、退避与跨周期预算，禁止静默跳页 |
| R3 | 通知刷屏或静默掩盖检查失败 | 缓解：重要变化去重与独立健康状态；初次失败/升级/恢复提醒 |
| R4 | CAP-66 未交付而方案声称已能扫描 | 接受：M3 明确依赖，M1/M2 独立可用，不实现替代扫描器 |
| R5 | 生命周期与副作用状态混淆 | 缓解：mission、cycle、task、观察、外部作业分别保存，取消不伪称回滚 |
| O1 | 首个试点的上游仓库/企业版维护分支 | 待决：实施前由用户选定并确认凭据/基线；本次规划不擅自启动真实跟踪 |
| O2 | 默认检查周期、重要程度阈值与静默规则 | 待决：首批试点冻结配置并可调整；默认仅重要变化和健康异常提醒 |
