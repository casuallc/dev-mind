// CAP-08 构建执行器
export type BuildExecutor = 'LOCAL' | 'REMOTE'
export type BuildStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED'

export interface BuildConfig {
  projectId: string
  executor: BuildExecutor
  remoteServerId: number | null
  concurrencyLimit: number
}

export interface TriggerInput {
  commit?: string
  branch?: string
  executor?: BuildExecutor
  remoteServerId?: number
  requirementId?: string
}

export interface BuildRecord {
  id: number
  projectId: string
  requirementId: string | null
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
