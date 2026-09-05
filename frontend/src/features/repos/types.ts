// CAP-29 全局代码仓库登记 类型定义

/** 全局仓库视图（对应后端 GitRepoView） */
export interface GitRepo {
  id: number
  name: string
  localPath: string
  remoteUrl?: string
  defaultBranch?: string
  sourceType: string // LOCAL / CLONE
  integrationId?: number
  cloneStatus?: string // NONE / CLONING / READY / FAILED
  cloneError?: string
  branches: string[]
  lastFetchAt?: string
  lastFetchError?: string
  status: string // ACTIVE / DISABLED
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

/** 登记/编辑请求（对应后端 GitRepoRequest） */
export interface GitRepoPayload {
  name: string
  sourceType: string
  localPath?: string
  remoteUrl?: string
  integrationId?: number
  defaultBranch?: string
  status?: string
}

/** 克隆状态颜色（与 projects feature 的 CLONE_STATUS_COLOR 同口径，避免跨 feature 私引） */
export const CLONE_STATUS_COLOR: Record<string, string> = {
  NONE: 'default',
  CLONING: 'processing',
  READY: 'success',
  FAILED: 'error',
}
