// CAP-11 发版执行器类型定义，与后端 devmind-release / release_config 对齐

export type ReleaseStatus = 'PLANNED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'ROLLED_BACK'
export type ReleaseExecutor = 'LOCAL' | 'REMOTE'

/** /projects/{id}/release-config（CAP-02 发版配置，已含 CAP-11 执行方式） */
export interface ReleaseConfig {
  id: number
  projectId: string
  nexusRepo?: string
  scriptTemplateRef?: string
  versionRule?: string
  executor?: string // LOCAL / REMOTE
  remoteServerId?: number
}

export interface ReleaseConfigInput {
  nexusRepo?: string
  scriptTemplateRef?: string
  versionRule?: string
  executor?: string
  remoteServerId?: number
}

export interface ReleaseRecord {
  id: number
  projectId: string
  taskId?: string
  buildId?: number
  version: string
  status: ReleaseStatus
  artifactRef?: string
  nexusRef?: string
  tagName?: string
  executor: string
  serverId?: number
  rollbackOf?: number
  errorSummary?: string
  createdBy?: string
  startedAt?: string
  finishedAt?: string
  createdAt: string
}

export interface CreateReleaseInput {
  projectId: string
  taskId?: string
  buildId?: number
  version?: string
  executor?: string
  serverId?: number
  force?: boolean
}
