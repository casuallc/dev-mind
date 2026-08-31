// 指挥中心（CAP-16）聚合视图类型
export interface DashboardView {
  /** 需求状态分布：status -> 数量 */
  requirements: Record<string, number>
  activeSessions: ActiveSessionItem[]
  pendingAcceptance: PendingRequirementItem[]
  pendingDesigns: PendingDesignItem[]
  recentFailures: FailureItem[]
}

export interface ActiveSessionItem {
  id: string
  projectId?: string
  requirementId?: string
  workItemId?: string
  taskSpec: string
  status: string
  createdAt: string
}

export interface PendingRequirementItem {
  id: string
  projectId: string
  code: string
  title: string
}

export interface PendingDesignItem {
  id: string
  projectId: string
  requirementId: string
  version: number
  docId?: number
}

export interface FailureItem {
  type: 'BUILD' | 'DEPLOYMENT' | 'TEST_RUN' | 'RELEASE'
  id: string
  projectId: string
  label: string
  time: string
}
