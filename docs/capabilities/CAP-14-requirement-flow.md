# CAP-14 需求流程引擎（Requirement Flow）

> 能力 ID：CAP-14 ｜ 分类：流程层 ｜ 状态：草案 ｜ 日期：2026-08-31

## 1. 目的

把 CAP-13 §7 的主流程 `分析 → 设计 → 拆分 → 执行 → 验收` 从纯手工驱动升级为**半自动流程**：
每个阶段一个流程动作（起会话/生成/拆分），产出就绪后**等人确认**再进下一阶段。
只做流程粘合与门禁校验，不做自动调度（depends_on 就绪自动起会话属 CAP-15 Orchestrator）。

核心原则：

> **AI 负责产出，人负责确认；流程引擎负责推进状态与登记产物。**

### 输出契约（agent ↔ 流程层的唯一耦合点）

流程型会话的 taskSpec 要求 agent 把结构化产出写到 worktree 约定路径：

| 会话类型 | 约定路径 | 内容 |
|---|---|---|
| 分析会话 | `.devmind/output/analysis.md` | 影响面/复杂度/建议（Markdown） |
| 方案会话 | `.devmind/output/design.md` | 设计方案（Markdown） |
| 拆分会话 | `.devmind/output/wi-plan.json` | `[{type,title,spec,dependsOn:[序号]}]` |

agent 未写产出文件时**不阻塞状态机**：通知降级为"请人工处理"，拆分草稿为空时退回手工建 Work Item（CAP-13 现有功能）。

## 2. 功能需求

- **FR-01 开始分析**：`startAnalysis(projectId, requirementId)`——校验需求状态为 DRAFT/ANALYZING（可重新分析）；
  组装分析 taskSpec（需求标题+描述+输出契约）；起**分析型会话**（requirementId 直挂，CAP-05 既有约定）；
  需求状态置 ANALYZING（rollup 不管这个状态，由流程引擎推进）。
- **FR-02 生成方案**：`startDesign(projectId, requirementId)`——创建 DESIGN 型 Work Item 并起会话，
  spec 为方案输出契约（taskSpec 自动带入 WI.spec）。
- **FR-03 AI 拆分**：`startSplit(projectId, requirementId)`——校验无进行中的 Work Item；
  若存在 Design 则要求至少一份 CONFIRMED（简单需求可无方案直接拆）；起分析型拆分会话，
  taskSpec 带需求内容 + CONFIRMED 方案摘要 + JSON 输出契约。
- **FR-04 工作单元执行**：`startWorkItemSession(projectId, workItemId)`——WI.spec 自动复制为 taskSpec
  起会话（补齐现状缺口：此前靠前端手工复制）。
- **FR-05 会话完成分流**：监听 `session.completed` 领域事件，按会话归属分流——
  - 分析会话 DONE → 读 `analysis.md`，登记 Artifact(ANALYSIS, produced_by=session)，通知"分析就绪待确认"；
  - DESIGN 型 WI 会话 DONE → 读 `design.md` → `DocumentService.create(kind=design)` +
    `DesignService.create(docId)`（DRAFT）+ Artifact(DOC)，通知"方案待确认"；
  - 拆分会话 DONE → 不解析固化，仅通知"拆分草稿就绪"（wi-plan.json 留在 workspace，确认时才解析）；
  - 会话 FAILED 或产出文件缺失 → 通知降级"请人工处理"，状态不变。
- **FR-06 拆分草稿查询**：`getSplitDraft(projectId, requirementId)`——定位该需求最近一次拆分会话的
  workspace，读 wi-plan.json 解析为草稿列表返回（**不落库**，确认前可重复生成覆盖）。
- **FR-07 拆分确认固化**：`confirmSplit(projectId, requirementId, items[])`——人编辑后的清单批量
  `WorkItemService.create`（触发既有 rollup），按 dependsOn 序号建 `depends_on` Relation；
  提交前做**环检测**（DFS），有环则整体拒绝并提示。
- **FR-08 门禁语义**：所有阶段动作只校验前置条件（状态/产物存在性），不强制状态机路径；
  人工仍可走 CAP-13 原生 API 直接操作（流程引擎是粘合层，不是围墙）。

## 3. 插件化接口

- 对外：`RequirementFlowService` 各阶段动作 + `getSplitDraft`/`confirmSplit`，供看板/后续 Orchestrator 调用。
- 依赖注入点：会话创建（CAP-05 `SessionManagerService`）、文档登记（CAP-03 `DocumentService`）、
  产物登记（CAP-13 `ArtifactService.registerInfo`）、通知（CAP-06 `NotificationService.emit`）。
- 输出契约路径常量集中在 `FlowOutputContract`（`.devmind/output/` 前缀），可整体替换。

## 4. 依赖关系

- 依赖：CAP-01（actor）、CAP-02/CAP-13（Requirement/Design/WorkItem/Relation 与 rollup）、
  CAP-03（方案文档登记）、CAP-05（会话创建 + `session.completed` 事件）、
  CAP-13 artifacts（产物登记）、CAP-06（等待人确认的通知）。
- 被依赖：后续 CAP-15 Orchestrator（消费 depends_on DAG 自动调度时将复用本能力的阶段动作）。

## 5. 数据模型

**不新增表**。复用：
- `requirements.status`（ANALYZING 由本能力推进；其余状态仍走 CAP-13 rollup/人工翻转）；
- `designs` / `work_items` / `relations`（CAP-13 既有）；
- `artifacts`（ANALYSIS/DOC 信息类产物，path 存引用：docId/sessionId/文件路径）；
- 拆分草稿不落库：wi-plan.json 存于会话 workspace，确认时才解析固化。

## 6. API 概要

```
POST /api/projects/{pid}/requirements/{rid}/flow/analyze          开始/重新分析
POST /api/projects/{pid}/requirements/{rid}/flow/design           生成方案（建 DESIGN WI + 起会话）
POST /api/projects/{pid}/requirements/{rid}/flow/split            AI 拆分（起拆分会话）
GET  /api/projects/{pid}/requirements/{rid}/flow/split-draft      拆分草稿（解析 wi-plan.json）
POST /api/projects/{pid}/requirements/{rid}/flow/confirm-split    确认固化（批量建 WI + depends_on 边）
POST /api/projects/{pid}/work-items/{wid}/start-session           WI 起会话（spec 自动带入）
```

confirm-split 请求体：`{items: [{type, title, spec, branchSlug?, dependsOn: [序号]}]}`，
序号为本次清单内的下标引用，固化时翻译为真实 WI 的 depends_on 边。

## 7. 验收标准

- DRAFT 需求可一键起分析会话，状态推进 ANALYZING；会话 DONE 后产物列表出现 ANALYSIS 产物；
- 方案会话 DONE 后自动登记 design 文档 + Design(DRAFT)，人工 CONFIRMED 后可起拆分会话；
- 无方案需求可直接拆分；存在 Design 但无 CONFIRMED 时拆分被拒绝并提示；
- 拆分会话 DONE 后可查看草稿（可编辑），确认固化后 Work Item 与 depends_on 边正确建立，
  需求状态按既有 rollup 推进 IN_PROGRESS；草稿有环时整体拒绝；
- WI 行级起会话自动带入 spec 作为 taskSpec；agent 未写产出文件时通知降级，不卡状态机。

## 8. MVP 范围（暂不做）

自动编排调度（depends_on 就绪自动起会话，属 CAP-15）、并行编排、审批会签、
流程实例/步骤持久化表、拆分草稿落库、需求级集成分支。
