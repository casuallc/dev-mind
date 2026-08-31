# CAP-15 自动编排器（Orchestrator）

> 能力 ID：CAP-15 ｜ 分类：流程层 ｜ 状态：草案 ｜ 日期：2026-08-31

## 1. 目的

在 CAP-14（半自动流程）之上叠加**依赖驱动的自动派发**：人已在"确认拆分"时做过决策，
之后的派活不再靠人逐个触发。语义边界：

> **派发自动化，完成判定仍归人。** WI 的 DONE 永远由人验收后翻转（或 CAP-14 流程动作），
> 编排器只负责"依赖就绪 → 自动起会话"。

## 2. 功能需求

- **FR-01 DONE 触发调度**：订阅 `workitem.status.changed` 领域事件，当某 WI 翻转为 DONE 时，
  扫描同需求的全部 Work Item + `depends_on` 边，对**依赖全部 DONE 的 TODO 项**自动起会话
  （复用 CAP-14 `startWorkItemSession`：spec 自动注入 taskSpec，TODO→IN_PROGRESS）。
  WI 从 BLOCKED 翻回 DONE 时经同一触发点自然恢复调度。
- **FR-02 固化后首批派发**：CAP-14 `confirmSplit` 完成后，对无依赖（或依赖已 DONE）的首批 WI
  立即自动起会话，形成「需求 → 确认拆分 → 自动执行」闭环。
- **FR-03 并发兜底**：受 CAP-05 `maxConcurrent` 限制；并发满时本轮跳过（WI 保持 TODO），
  下一个 DONE 事件到来时重试，不排队不丢失。
- **FR-04 失败降级**：会话 FAILED / WI BLOCKED 不做自动处理，仅通知（CAP-06），等人介入。
- **FR-05 幂等**：只派发 TODO 状态的 WI；起会话成功后 WI→IN_PROGRESS，重复事件不会重复派发。
- **FR-06 派发通知**：每次自动派发发 P1 通知（"WI-x 依赖就绪，已自动派发会话"），关联需求实体。

## 3. 插件化接口

- 依赖就绪判定为纯函数 `DispatchPlanner.readyItems(workItems, edges)`，可单测、可替换。
- 调度入口 `WorkItemOrchestrator.dispatchReady(projectId, requirementId)` 公开，
  供事件订阅与 confirmSplit 复用；后续 CAP-14+ 的审批门禁可在同一入口前插入。

## 4. 依赖关系

- 依赖：CAP-13（WorkItem/Relation/rollup）、CAP-14（startWorkItemSession）、
  CAP-05（会话并发上限）、CAP-06（通知）、devmind-common 事件总线。
- 被依赖：无（流程层叶子）；后续审批门禁、跨需求调度在本模块叠加。

## 5. 数据模型

**不新增表**。复用 `work_items.status`（TODO→IN_PROGRESS 由派发驱动）与
`relations(depends_on)` 边。事件 `workitem.status.changed` 由 CAP-13 模块发布，
不转通知（监听器忽略清单），避免状态翻转噪音。

## 6. API 概要

无新增 REST 端点（编排是事件驱动的内部行为）。调试入口复用：
`POST /api/projects/{pid}/work-items/{wid}/start-session`（CAP-14）手工补派。

## 7. 验收标准

- 拆分固化后无依赖 WI 自动 IN_PROGRESS（会话已启动），有依赖 WI 保持 TODO；
- 依赖 WI 全部 DONE 后，下游 WI 在秒级内自动起会话并收到派发通知；
- 并发满时不报错不丢任务，下次 DONE 事件补派；
- 同一会话完成事件/重复状态翻转不会造成重复派发（WI 非 TODO 即跳过）。

## 8. MVP 范围（暂不做）

会话成功自动置 WI DONE（验收是人的决策点）、跨需求调度、并行上限 UI 配置、
优先级队列、审批门禁插入点、Agent Teams 分工。
