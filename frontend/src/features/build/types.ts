// CAP-08 构建执行器（CAP-36：REMOTE/SSH 已下线，远程执行统一走 runner 节点 exec 帧）
export type BuildExecutor = 'LOCAL' | 'AGENT'
export type BuildStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED'

export interface BuildConfig {
  projectId: string
  executor: BuildExecutor
  /** AGENT 执行的目标节点 id（空 = 路由链：项目默认 → 平台默认 → 标签） */
  agentNodeId: string | null
  concurrencyLimit: number
}

export interface TriggerInput {
  commit?: string
  branch?: string
  executor?: BuildExecutor
  agentNodeId?: string
  workItemId?: string
}

export interface BuildRecord {
  id: number
  projectId: string
  workItemId: string | null
  commit: string | null
  branch: string | null
  executor: BuildExecutor
  artifactRef: string | null
  status: BuildStatus
  exitCode: number | null
  errorSummary: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
}
