# CAP-79 Agent 记忆闭环增强（经验自动回流 / 知识可达性 / 增量重规划）

> 能力 ID：CAP-79 ｜ 分类：流程层 ｜ 状态：设计定稿 ｜ 日期：2026-10-20
> 背景：平台对「Agent = 大脑 + 手 + 记忆 + 规划」的评估结论——手与护栏已生产级，
> 规划是清醒的能力降级（CAP-14/52），**记忆是最薄一层**：会话经验写回知识库的唯一
> 通道是「人手工提提案」，工作树 30 天 GC 后未沉淀的经验物理消失；执行会话瘦上下文
> （CAP-52）砍掉知识注入后，agent 运行中到底查不查知识无任何数据；一个需求一次
> 开发会话做完，verify 反复失败时只有人工整条重跑，WI 依赖编排设施闲置。
> 本能力按 M1→M3 递进补这三块，每个里程碑独立可用、独立验收。

## 1. 目的

- **经验不再靠自觉**：开发会话收尾协议里让 agent 顺手产出经验候选，平台自动登记进
  知识提案 inbox——闭环上的人工闸门从「发起」收敛到「采纳」（采纳本来就有）。
- **改注入策略先看数据**：执行会话的 `search_knowledge` 调用全量落统计，先度量
  「该查不查」还是「查而无货」，再决定要不要回灌瘦上下文，不瞎调。
- **规划不到位有正面答案**：verify 连续失败时支持「增量重规划」——WI 清单 diff
  （增/改/撤）而非整体重跑，激活 CAP-13 的 depends_on 编排价值。

## 2. 功能需求

### M1 会话经验自动回流

- **FR-01 lessons 产出契约**：`FlowOutputContract.devSpec()` 收尾要求增加第 4 条——
  可选写 `.devmind/output/lessons.md`，格式为若干条二级标题小节：
  `## <标题>` + 正文（Markdown）+ 行内元信息行 `tags: a, b`（可省）。
  内容引导写死在模板里：「踩过的坑/可复用的做法/环境与工具链陷阱；没有就不写，
  不要硬凑」。明确告知**这些经验经人工采纳后会注入后续会话**。
- **FR-02 自动登记提案**：`RequirementFlowService.handleDevOutput` 登记 dev-summary
  的同一点，读取 lessons.md 逐小节转成 `KnowledgeProposalEntity`：
  title=小节标题（截 200）、contentMd=小节正文、targetScope=`project`、
  targetProjectId=需求所属项目、sourceSessionId=会话 id。新增 `source` 列
  （`MANUAL`|`AUTO_SESSION`，可空，null 视为 MANUAL——存量行零迁移）。
- **FR-03 解析降级不阻塞**：lessons.md 缺失/格式不符/单条超长，跳过并计数，
  仅在该会话的 flow 通知里附一句降级说明；**绝不影响 dev-summary 登记与状态推进**
  （沿用 handlePlanOutput 的 best-effort 哲学）。
- **FR-04 去重**：同需求 + 同 title（规范化后）已有 open 提案则跳过不重复建行；
  已被 rejected 的同 title 提案允许再次产生（新会话可能有新结论）。
- **FR-05 失败信号占位提案**：session.completed 监听中加规则（devmind-flow 内，
  与 dispatch 同级）：执行会话 FAILED，或会话事件流中 permission 拒绝次数 ≥3，
  自动产一条 `source=AUTO_SESSION` 占位提案，title=`【待回顾】<需求/任务摘要>`，
  正文=失败原因/拒绝摘要 + 会话链接，提示人去捞经验。纯规则触发，不调模型。

### M2 执行会话知识可达性度量

- **FR-06 检索调用统计**：新增 `knowledge_search_stats` 表——`KnowledgeTools.
  search_knowledge` 每次调用落一行：session_id / project_id / requirement_id（可空）/
  query 摘要（截 500）/ hit_count / top_score / created_at。只存聚合所需字段，
  原始 tool_use 事件仍由 session_events 承载，不重复存。
- **FR-07 统计口径**：查询率（有 ≥1 次检索的执行会话占比）、命中率（hit_count>0
  占比）、低分率（top_score 低于阈值占比），按项目/时间窗聚合。提供只读 REST
  端点，管理页加简单报表卡片（复用现有内容区布局约定）。
- **FR-08 M2 只做度量不改行为**：瘦上下文注入策略本期不动；M3 之后依据统计结论
  另行决策（候选：devSpec 加「涉及平台内部行为先 search_knowledge」引导语，或
  高命中条目 topN 回灌瘦上下文），届时立变更记录，不回改本文档口径。

### M3 执行中增量重规划

- **FR-09 触发与入口**：`VerifyService`（CAP-73）统计同一需求 verify run 连续
  FAILED 次数，≥2（可配 `devmind.flow.replan-threshold`）时发 P1 通知并在需求
  详情页/飞书给出「增量重规划」入口。**第一版坚持人触发**（WRITE 级确认链），
  不自动挂起会话、不自动起规划。
- **FR-10 增量规划会话**：确认后起 `[flow:replan]` 会话（挂 requirementId，
  taskSpec 由 `FlowOutputContract.replanSpec()` 生成），上下文 = 原 wi-plan 固化结果
  （WI 编号/标题/状态）+ 各 WI 完成情况 + 最近失败 verify 日志摘要（截断）。
  产出契约 `replan.json`，**diff 形态**：
  `{"add":[{title,spec,dependsOn}], "modify":[{wiSeq,spec}], "cancel":[wiSeq]}`，
  禁止整体重出清单。
- **FR-11 diff 固化**：`applySplitPlan` 增加 diff 模式——add 走 `SplitPlanValidator`
  原校验（与存量合计仍受 MAX_ITEMS 约束）；modify 仅允许改 NOT_STARTED 的 WI，
  进行中/已 DONE 拒绝并逐项报错；cancel 置 CANCELLED 并清理指向它的 depends_on
  边；环检测（DFS）对合并后的全图重跑。
- **FR-12 会话编排**：重规划进行中原 dev 会话挂起（SUSPENDED，工作区保留）；
  diff 固化完成后**起新 dev 会话**（taskSpec 只含新增/修改条目 + 已完成条目一句话
  回顾），不 resume 旧会话——旧上下文已被失败轨迹污染，新会话上下文干净。
- **FR-13 状态口径**：CANCELLED 的 WI 不参与 Requirement rollup 的「全部 DONE」
  判定（视同不存在）；需求状态在重规划期间保持 IN_PROGRESS，不回退 ANALYZING。

## 3. 非目标（本期不做）

- **会话结束自动起 one-shot 提取会话**：用额外模型调用换经验提取，token 成本高且
  与 dev-summary 信息重复；lessons.md 是「顺手产出」，零额外会话。
- **自动采纳提案**：inbox → 采纳仍走 CAP-55 分诊 + 人工裁决原链路，本能力只把
  「发起」自动化；自动提案永不直升 global。
- **自动重规划**：M3 触发只给通知与入口，决策权在人。
- **回灌瘦上下文**：M2 只有度量；任何注入策略变更等数据说话（FR-08）。
- **跨会话语义记忆平台**：不建向量化的「agent 自传体记忆」；跨会话连续性仍靠
  CAP-53 的 claude memory/ 目录 + 本能力的知识回流通道。

## 4. 验收

**M1**
- 假模型 dev 会话预写 lessons.md（2 条，含 tags 行）→ 会话完成后 inbox 出现 2 条
  `AUTO_SESSION` 提案，title/正文/sourceSessionId/targetProjectId 正确。
- lessons.md 缺失 → 无提案、无报错，dev-summary 登记与状态推进不受影响。
- 格式损坏（无二级标题）→ 跳过计数，flow 通知附降级说明。
- 同需求同 title 重复产出 → 不重复建行；FAILED 会话 → 出现【待回顾】占位提案。
- 存量库升级：`source` 列为空的历史提案在列表/详情中显示为 MANUAL，无 500。

**M2**
- 执行会话调用 search_knowledge（有命中/无命中各一次）→ stats 表各落一行，
  字段完整；REST 聚合口径与明细对得上。

**M3**
- verify 连续 FAILED ≥ 阈值 → 收到 P1 通知，需求页出现入口；未确认不起任何会话。
- 确认后起 `[flow:replan]` 会话，预写合法 replan.json（add 1 + modify 1 未开始 WI +
  cancel 1）→ 固化结果正确：新 WI 建行、modify 仅 spec 更新、cancel 置 CANCELLED
  且依赖边清理、无环。
- modify 指向 DONE 的 WI → 该项拒绝并报错，其余项照常固化。
- diff 固化完成 → 旧 dev 会话 SUSPENDED，新 dev 会话 taskSpec 不含已完成条目的
  完整 spec（只有回顾行）。
- E2E 复用 cap76 假模型 + cap52 真 runner 两套 harness；跑完清理自起进程。

## 5. 依赖与影响

- 依赖 CAP-52（devSpec 收尾协议与瘦上下文口径）、CAP-37（.devmind/output 产出回传）、
  CAP-04/44（知识提案 inbox 与条目模型）、CAP-55（分诊链路不动）、CAP-73
  （verify_runs 与失败判定）、CAP-13/14（WI 状态机、depends_on 边、rollup 口径）。
- 改动面：
  - `devmind-flow`：FlowOutputContract（devSpec 第 4 条 + replanSpec/replan.json
    契约）、RequirementFlowService（handleDevOutput 登记 lessons、失败信号监听、
    applySplitPlan diff 模式）、VerifyService（连续失败计数与通知）。
  - `devmind-knowledge`：KnowledgeProposalEntity 加 `source` 列（ddl-auto 加可空列，
    存量行 null=MANUAL，列表查询口径同步）、KnowledgeTools.search_knowledge 落统计、
    stats 只读端点。
  - `frontend`：features/knowledge inbox 加 source 过滤与【待回顾】标识；admin 加
    检索统计报表卡片；需求详情页加「增量重规划」入口与确认链。
- 表结构变更均为**加可空列/新表**，存量部署零手工 ALTER。
- 提交纪律：M1/M2/M3 各自独立 commit 批次（契约→登记→前端→E2E 按功能拆分），
  不跨里程碑打包。
