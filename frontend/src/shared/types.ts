// 跨能力共享的只读视图类型（后端 WorkItemBrief 出参）。
// 执行器（构建/部署/测试/发版）列表按 workItemId 批量补全，用于回链需求详情。

/** 工作单元摘要：执行器记录回链需求展示用；未关联或已删除时整条为 null */
export interface WorkItemBrief {
  id: string
  /** 项目内编号，如 WI-12 */
  code: string
  title: string
  requirementId: string | null
  /** 项目内编号，如 REQ-30 */
  requirementCode: string | null
  requirementTitle: string | null
}
