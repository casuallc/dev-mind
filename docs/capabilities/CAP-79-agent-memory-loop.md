# CAP-79 Agent 记忆闭环增强（经验自动回流 / 知识通道与度量 / 增量重规划）

> 能力 ID：CAP-79 ｜ 分类：流程层 ｜ 状态：设计定稿（v2，按 review 修订） ｜ 日期：2026-10-20
> 背景：平台对「Agent = 大脑 + 手 + 记忆 + 规划」的评估结论——手与护栏已生产级，
> 规划是清醒的能力降级（CAP-14/52），**记忆是最薄一层**：会话经验写回知识库的唯一
> 通道是「人手工提提案」，工作树 30 天 GC 后未沉淀的经验物理消失；执行会话瘦上下文
> （CAP-52）砍掉知识注入后，执行中的 agent **没有任何触达知识库的通道**（平台工具
> 只服务 copilot/bot，runner 侧无 MCP/tools 端点）；一个需求一次开发会话做完，
> verify 反复失败时只有人工整条重跑，WI 依赖编排设施闲置。
> 本能力按 M1→M3 递进补这三块，每个里程碑独立可用、独立验收。
>
> v2 修订（review 后）：M2 从「统计既有 search_knowledge 调用」升级为「**先建
> runner→平台知识检索通道，再度量**」——原前提不成立（执行会话无工具通道，统计恒 0）；
> replan.json 补 type 与 dependsOn 引用空间定义；WI 状态名对齐 TODO/BLOCKED；
> 提案表补 requirementId/tags 列；挂起与 rollup 口径写清分支。

> 后续衔接（2026-10-27，均未实施）：[CAP-96](CAP-96-assistant-task-runtime.md) 负责助手目标的动态计划和任务事实记忆；本 CAP 保留经验提案、Runner 知识通道和领域 WI diff，首版 WI 重规划仍由人触发。助手调整行动计划不等于自动改 WI。本文旧稿中的 targetScope/targetProjectId 与“项目自动命中”属于历史设计，实施前必须按 CAP-94 显式选库及 CAP-95 资源授权修订，禁止恢复旧 scope/项目授权语义；自动提案不得自动公开或采纳。

## 1. 目的

- **经验不再靠自觉**：开发会话收尾协议里让 agent 顺手产出经验候选，平台自动登记进
  知识提案 inbox——闭环上的人工闸门从「发起」收敛到「采纳」（采纳本来就有）。
- **补上执行会话的知识断供**：给 runner 上的 claude 建一条只读、按会话作用域鉴权的
  知识检索通道，让「该查能查」；同时全量落调用统计，用数据回答「查不查、查得中不中」。
- **规划不到位有正面答案**：verify 连续失败时支持「增量重规划」——WI 清单 diff
  （增/改/撤）而非整体重跑，激活 CAP-13 的 depends_on 编排价值。

## 2. 功能需求

### M1 会话经验自动回流

- **FR-01 lessons 产出契约**：`FlowOutputContract.devSpec()` 收尾要求增加第 4 条——
  可选写 `.devmind/output/lessons.md`，格式为若干条二级标题小节：
  `## <标题>` + 行内元信息行 `tags: a, b`（可省）+ 正文（Markdown）。
  内容引导写死在模板里：「踩过的坑/可复用的做法/环境与工具链陷阱；没有就不写，
  不要硬凑」。明确告知**这些经验经人工采纳后会注入后续会话**。
- **FR-02 自动登记提案**：`RequirementFlowService.handleDevOutput` 登记 dev-summary
  的同一点，读取 lessons.md 逐小节转成 `KnowledgeProposalEntity`：
  title=小节标题（截 200）、contentMd=小节正文、**tags=元信息行的标签（逗号分隔）**、
  targetScope=`project`、targetProjectId=需求所属项目、**requirementId=需求 id**、
  sourceSessionId=会话 id。提案表新增两列（均可空，存量零迁移）：
  `source`（`MANUAL`|`AUTO_SESSION`，null 视为 MANUAL）、`requirement_id`、`tags`。
- **FR-03 解析降级不阻塞**：lessons.md 缺失/格式不符/单条超长，跳过并计数，
  仅在该会话的 flow 通知里附一句降级说明；**绝不影响 dev-summary 登记与状态推进**
  （沿用 handlePlanOutput 的 best-effort 哲学）。
- **FR-04 去重**：去重键 = **requirementId + 规范化 title + status=open**（三列直接
  可查，不绕会话反查）；命中则跳过不重复建行。requirementId 为空的手工提案不参与
  自动去重（各建各的）。已被 rejected 的同键提案允许再次产生（新会话可能有新结论）。
- **FR-05 失败信号占位提案**：session.completed 监听中加规则（devmind-flow 内，
  与 dispatch 同级）：执行会话 FAILED，或会话事件流中 permission 拒绝次数 ≥3，
  自动产一条 `source=AUTO_SESSION` 占位提案，title=`【待回顾】<需求/任务摘要>`，
  正文=失败原因/拒绝摘要 + 会话链接，requirementId 按会话归属落（可空）。
  纯规则触发，不调模型。

### M2 执行会话知识检索通道与可达性度量

- **FR-06 会话作用域检索通道**：服务端新增 agent 工具端点
  `POST /api/agent/tools/{sessionId}/search_knowledge`——**节点 token 鉴权 +
  sessionId 路径参数**，会话归属（项目/需求）服务端按 sessionId 反查，调用方无法
  伪造；检索范围复用上下文装配的「项目自动命中」口径（本项目条目 + 项目 tags 命中的
  global 条目），只读、禁跨项目。检索走 `KnowledgeRetriever`（向量带 score，
  无端点降级 LIKE 且 score=null）。runner 侧把该端点桥接给 claude（MCP 桥或
  .devmind/bin 包装命令，实现选型在设计文档定），仅执行会话与规划会话注入，
  问答沙箱（sessionKind=chat）不注入。
- **FR-07 调用统计**：新增 `knowledge_search_stats` 表，通道端点每次调用落一行：
  session_id（路径参数天然可得，无需 ToolContext 扩字段）/ project_id /
  requirement_id（可空）/ query 摘要（截 500）/ hit_count / top_score（LIKE 降级时
  为 null）/ degraded（是否降级检索）/ created_at。原始 tool_use 事件仍由
  session_events 承载，本表只存聚合所需字段。
- **FR-08 统计口径**：查询率（有 ≥1 次检索的执行会话占比）、命中率（hit_count>0
  占比）、低分率（**仅统计 top_score 非空的向量检索行**，低于阈值占比；LIKE 降级
  行单独计 degraded 率，不混入低分率）。按项目/时间窗聚合，只读 REST 端点 +
  管理页报表卡片（复用内容区布局约定）。
- **FR-09 不改既有行为**：瘦上下文注入策略不动；copilot/bot 侧 `search_knowledge`
  PlatformTool 行为不动（其调用另行经 CopilotToolCallEntity 审计，不入本统计）。
  是否往瘦上下文回灌高命中条目，待统计结论另行决策。

### M3 执行中增量重规划

- **FR-10 触发与入口**：`VerifyService`（CAP-73）统计同一需求 verify run 连续
  FAILED 次数，≥2（可配 `devmind.flow.replan-threshold`）时发 P1 通知，并在
  **需求详情页与飞书**给出「增量重规划」入口（飞书侧走 bot 卡片动作 + CAP-76
  确认链，与页面同一 WRITE 级确认单口径）。**第一版坚持人触发**，不自动挂起会话、
  不自动起规划。
- **FR-11 增量规划会话**：确认后起 `[flow:replan]` 会话（挂 requirementId，
  taskSpec 由 `FlowOutputContract.replanSpec()` 生成），上下文 = 原 wi-plan 固化结果
  （WI 编号/标题/状态）+ 各 WI 完成情况 + 最近失败 verify 日志摘要（截断）。
  产出契约 `replan.json`，**diff 形态**：
  ```json
  {"add":[{"type":"DEVELOPMENT","title":"...","spec":"...","dependsOn":["WI-3","new:0"]}],
    "modify":[{"wiSeq":5,"spec":"..."}],
    "cancel":[7]}
  ```
  - add 项 **type 必填**（与 wi-plan 契约一致，五类 WI）；
  - **dependsOn 引用空间**：存量 WI 用 `"WI-<seq>"`，本次新增项互引用 `"new:<add 数组下标>"`；
    禁混用其他格式，解析不到的标识整体拒绝（不猜、不截断）。
- **FR-12 diff 固化**：`applySplitPlan` 增加 diff 模式——add 条目级校验复用
  `SplitPlanValidator` 的条目规则（spec 非空等），**依赖解析与环检测走新逻辑**：
  标识解析成节点后，对「存量 depends_on 边 + 新增边」的合并全图跑 DFS 环检测；
  存量+新增合计条数仍受 MAX_ITEMS 约束。modify **仅允许改 TODO/BLOCKED 的 WI**
  （改 spec 本就是解阻手段），IN_PROGRESS/DONE/CANCELLED 拒绝并逐项报错（其余项
  照常固化，不整单回滚）。cancel 置 CANCELLED 并清理指向它的 depends_on 边；
  已被依赖的 DONE 项禁 cancel。
- **FR-13 会话编排**：重规划确认时原 dev 会话**仍在活动态（RUNNING/WAITING_*）则
  挂起 SUSPENDED（工作区保留）；已 DONE/FAILED/TERMINATED 则跳过挂起**（常态——
  verify 失败常发生在会话结束后）。diff 固化完成后**起新 dev 会话**（taskSpec 只含
  新增/修改条目 + 已完成条目一句话回顾），不 resume 旧会话——旧上下文已被失败轨迹
  污染。
- **FR-14 状态口径**：WI 的 CANCELLED 终态语义**完全沿用现状**（isTerminal 已含，
  「全部 WI 非活动即收敛」的 rollup 行为不变，含「全部 cancel → ACCEPTANCE」），
  本能力不改 rollup，仅以回归测试钉住该口径；需求状态在重规划期间保持
  IN_PROGRESS，不回退 ANALYZING。

## 3. 非目标（本期不做）

- **会话结束自动起 one-shot 提取会话**：用额外模型调用换经验提取，token 成本高且
  与 dev-summary 信息重复；lessons.md 是「顺手产出」，零额外会话。
- **自动采纳提案**：inbox → 采纳仍走 CAP-55 分诊 + 人工裁决原链路，本能力只把
  「发起」自动化；自动提案永不直升 global。
- **通用 runner→平台工具网关**：M2 只开知识检索一个只读端点；写操作进执行会话
  （如 agent 直接建提案/改 WI）留待后续能力，届时复用本通道的会话作用域鉴权。
- **自动重规划**：M3 触发只给通知与入口，决策权在人。
- **回灌瘦上下文**：任何注入策略变更等 M2 统计数据说话（FR-09）。
- **跨会话语义记忆平台**：不建向量化的「agent 自传体记忆」；跨会话连续性仍靠
  CAP-53 的 claude memory/ 目录 + 本能力的知识回流通道。

## 4. 验收

**M1**
- 假模型 dev 会话预写 lessons.md（2 条，含 tags 行）→ 会话完成后 inbox 出现 2 条
  `AUTO_SESSION` 提案，title/正文/**tags**/requirementId/sourceSessionId/
  targetProjectId 正确。
- lessons.md 缺失 → 无提案、无报错，dev-summary 登记与状态推进不受影响。
- 格式损坏（无二级标题）→ 跳过计数，flow 通知附降级说明。
- 同需求 + 同 title（open）重复产出 → 不重复建行；rejected 后同键再产 → 建行；
  FAILED 会话 → 出现【待回顾】占位提案且 requirementId 正确。
- 存量库升级：`source`/`requirement_id`/`tags` 为空的历史提案在列表/详情中显示为
  MANUAL，无 500。

**M2**
- 真 runner 执行会话内 agent 经通道调用检索（有命中/无命中各一次）→ stats 表各落
  一行，session_id/project_id/requirement_id 正确；伪造他会话 sessionId 调用 → 403。
- 检索范围：本项目条目 + tags 命中 global 条目可查，他项目私有条目不可查。
- embedding 无端点时调用照常返回（degraded=true，top_score=null），REST 聚合的
  查询率/命中率/低分率/degraded 率与明细对得上（低分率不含 null 分行）。
- chat 问答会话无该通道注入。

**M3**
- verify 连续 FAILED ≥ 阈值 → 收到 P1 通知，需求页与飞书均出现入口；**两条路径
  未确认都不起任何会话**。
- 确认后起 `[flow:replan]` 会话，预写合法 replan.json（add 1 条 type=DEVELOPMENT
  且 dependsOn=["WI-<存量>","new:0"] 混合、modify 1 条 TODO、cancel 1 条）→
  固化正确：新 WI 建行、依赖边按标识落成、modify 仅 spec 更新、cancel 置 CANCELLED
  且指向它的边清理、合并全图无环。
- add 项缺 type / dependsOn 标识解析不到 → 整体拒绝不截断；modify 指向 DONE →
  该项拒绝、其余照常固化；add 与存量构成环 → 拒绝。
- dev 会话仍在跑时确认重规划 → 旧会话 SUSPENDED；已 DONE 时 → 无挂起动作不报错；
  diff 固化后新 dev 会话 taskSpec 不含已完成条目的完整 spec（只有回顾行）。
- 回归：需求全部 WI 被 cancel 后 rollup 落 ACCEPTANCE（现行行为不变）。
- E2E 复用 cap76 假模型 + cap52 真 runner 两套 harness；跑完清理自起进程。

## 5. 依赖与影响

- 依赖 CAP-52（devSpec 收尾协议与瘦上下文口径）、CAP-37（.devmind/output 产出回传）、
  CAP-04/44（知识提案 inbox、KnowledgeRetriever）、CAP-55（分诊链路不动）、
  CAP-73（verify_runs 与失败判定）、CAP-13/14（WI 状态机、depends_on 边、rollup）、
  CAP-34（节点 token 鉴权与会话路由）、CAP-76（写操作确认链）。
- 改动面：
  - `devmind-flow`：FlowOutputContract（devSpec 第 4 条 + replanSpec/replan.json
    契约）、RequirementFlowService（handleDevOutput 登记 lessons、失败信号监听、
    applySplitPlan diff 模式、标识解析与全图环检测）、VerifyService（连续失败计数
    与通知）。
  - `devmind-knowledge`：KnowledgeProposalEntity 加 `source`/`requirement_id`/`tags`
    三可空列、提案创建与去重逻辑、检索范围口径（复用项目自动命中规则）。
  - `devmind-agent`：`/api/agent/tools/{sessionId}/search_knowledge` 端点（节点
    token + 会话归属反查 + 只读）。
  - `devmind-agent-runner`：检索通道桥接与注入（MCP 桥或包装命令，设计文档选型；
    launch 帧如需新字段必须同步补 LaunchTest 断言——防静默丢字段）。
  - `devmind-bot`：M3 飞书侧入口（卡片动作 + bot 渠道确认链接入）。
  - `frontend`：features/knowledge inbox 加 source 过滤与【待回顾】标识；admin 加
    检索统计报表卡片；需求详情页加「增量重规划」入口与确认链。
- 表结构变更均为**加可空列/新表**，存量部署零手工 ALTER。
- 提交纪律：M1/M2/M3 各自独立 commit 批次（契约→登记→通道→前端→E2E 按功能
  拆分），不跨里程碑打包。
