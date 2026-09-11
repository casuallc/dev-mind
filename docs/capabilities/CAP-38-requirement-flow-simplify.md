# CAP-38 需求流程简化：阶段 Tab 与自动拆分固化

> 能力 ID：CAP-38 ｜ 分类：流程层 ｜ 状态：草案 ｜ 日期：2026-09-12

## 1. 目的

CAP-37 修通产出链路后，需求流程仍存在「入口多、人工接力多、车道混用」的割裂感。
本能力把主流程收敛为一条**单向、可跳过、自动接力**的流水线：

```
需求分析 → 方案设计 → 拆分工作单元（自动）→ 执行 → 验收
   可跳过      可跳过        ↑ 方案产出后自动起拆分会话、产出自动固化为正式 WI
```

六条原则（用户拍板）：

1. 需求分析与方案设计**都可以跳过**；
2. 分析与方案**独立于工作单元**，前端分两个独立 Tab，排在工作单元前面；
3. 方案设计完成后**自动拆分**，产出**自动固化**为正式工作单元——拆分的定位就是
   「辅助快速创建工作单元」，不再要人工确认草稿；
4. 工作单元状态**中文展示**，且**关联会话可跳转**（WI 行内直达会话详情）;
5. 流程**不可逆**：前面完成（或显式跳过）才解锁后面，由前端交互引导；
6. sessions 页新建会话**关联需求即自动创建工作单元**（会话挂到该单元上）——
   因此可关联的需求须过滤为「还能建工作单元」的。

核心原则不变：**AI 负责产出，人负责验收与最终确认；流程引擎负责自动接力。**

## 2. 功能需求

- **FR-01 阶段跳过与不可逆解锁**：
  - 需求实体新增 `analysis_skipped` / `design_skipped` 持久化标记；
  - `POST /api/projects/{pid}/requirements/{rid}/flow/skip {stage}` 幂等置标记；
    跳过分析且需求为 DRAFT 时推进到 ANALYZING（下游门禁自然放行）；
    已完成（有对应产出）的阶段跳过报 409；
  - 解锁语义：方案设计可用 ⟸ 分析完成（有 analysis 文档）或已跳过；
    工作单元区可用 ⟸ 方案完成或已跳过（前端引导，后端 WI 创建不加硬门禁，
    保护编排器与既有自由用法）。
- **FR-02 阶段 Tab（前端）**：需求详情页 Tab 顺序固定为
  `需求分析 → 方案设计 → 工作单元 → 时间线 → 关联记录`；
  删除 CAP-37 的「流程」Tab。分析 Tab：状态卡（未开始/进行中/已完成/已跳过）
  + 开始/重新分析 + 查看分析文档 + 跳过；方案 Tab：生成方案 + 跳过 + 方案列表
  （CONFIRMED 降为纯标记，不再门控拆分）。
- **FR-03 自动拆分与自动固化**：
  - 方案会话产出登记（design.md → Design）成功后，流程引擎**自动起拆分会话**
    （spec 注入刚产出的方案 + 最近分析；唯一门禁：无进行中的非 DESIGN 工作单元，
    不满足则发 P1 通知说明不自动拆）；
  - 拆分会话完成后，wi-plan.json **直接固化**：批量建 WI + depends_on 依赖边 +
    发布 `flow.split.confirmed`（CAP-15 编排器自动派发首批 WI 不变），
    发 P1 `flow.split.done`；产出缺失/解析为空 → `flow.split.missing` 降级通知人工；
  - 删除 `GET /flow/split-draft` 与 `POST /flow/confirm-split` 端点及草稿确认 UI；
  - 手动 `POST /flow/split` 保留（跳过方案后的路径），删除「有方案须 CONFIRMED」
    门禁：有 CONFIRMED 用其内容，否则用最新方案文档，都没有则注入分析。
- **FR-04 工作单元中文化与会话跳转（前端）**：WI 状态中文标签
  （TODO 待办 / IN_PROGRESS 进行中 / BLOCKED 阻塞 / DONE 已完成 / CANCELLED 已取消）；
  WI 表格新增「会话」列，按 overview.sessions 反查该 WI 最近会话跳转详情页。
- **FR-05 会话关联需求自动建工作单元**：`POST /api/sessions` 携带 requirementId、
  无 workItemId、taskSpec 非 `[flow:` 流程会话时，自动创建 DEVELOPMENT 型 WI
  （title=taskSpec 首行截 60 字符，spec=taskSpec 全文）并把会话挂到该 WI；
  需求状态仅 DRAFT/ANALYZING/DESIGNING/IN_PROGRESS 允许，其余 409；
  前端可选需求同步排除 ACCEPTANCE/DONE/CANCELLED。
- **FR-06 通知深链分 Tab**：`flow.analysis.*` → `?tab=analysis`；
  `flow.design.*` → `?tab=design`；`flow.split.*` / `flow.dispatched` → `?tab=workItems`。

## 3. 门禁矩阵

| 动作 | 条件 | 不满足 |
|------|------|--------|
| 开始/重新分析 | 需求 DRAFT/ANALYZING（现状不变） | 409 |
| 生成方案 | 需求 ANALYZING/DESIGNING（跳过分析会推进状态） | 409 |
| 自动拆分 | 方案产出登记成功 且 无进行中执行 WI | 发 P1 说明不自动拆 |
| 手动拆分 | 需求 ANALYZING/DESIGNING 且 无进行中执行 WI | 409 |
| 跳过分析/方案 | 该阶段尚无产出 | 409 |
| 会话关联需求自动建 WI | 需求 DRAFT/ANALYZING/DESIGNING/IN_PROGRESS | 409 |

## 4. 非目标

- 不改 CAP-15 编排器的派发策略；WI 完成后仍由人工标 DONE（自动收口另行立项）。
- 后端不给 WI 手工创建加阶段硬门禁（保持既有自由用法与编排器兼容，引导在前端）。
- 不处理历史数据迁移：旧需求无跳过标记（默认 false），既有 WI 的需求工作单元 Tab 始终可用。

## 5. 验收标准

1. 新需求：开始分析 → 产出落 analysis 文档；生成方案 → 产出落 Design，**自动**起拆分会话；
   预写 wi-plan.json 后**自动固化**为 N 个 WI（含 depends_on 边）并收到 flow.split.done；
   全程无手动拆分/固化动作。
2. 新需求：跳过分析 → 跳过方案 → 工作单元 Tab 解锁可手工新建 / 手动 AI 拆分。
3. 已完成分析的需求点「跳过分析」→ 409。
4. sessions 页/REST 新建会话挂非终态需求 → 自动建 DEVELOPMENT WI 且会话挂到该 WI；
   挂 ACCEPTANCE 需求 → 409；可选需求列表不含 ACCEPTANCE/DONE/CANCELLED。
5. WI 表格状态为中文，行内可跳最近会话详情；flow.* 通知点击落到对应 Tab。
