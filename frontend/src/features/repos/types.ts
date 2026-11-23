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

/** CAP-109 FR-04 提交历史行（对应后端 RepoCommitView；time 已是 'yyyy-MM-dd HH:mm:ss' 字符串） */
export interface RepoCommitView {
  sha: string
  authorName: string
  authorEmail: string
  time: string
  subject: string
}

/** CAP-111 FR-05 分页视图（对应后端 common PageView；page 从 0 起） */
export interface PageViewResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** CAP-111 FR-04 变更文件（status 为 git name-status 原始值 A/M/D/R100/C…） */
export interface RepoCommitFile {
  status: string
  path: string
  oldPath?: string | null
}

/** CAP-111 FR-04 提交详情（对应后端 RepoCommitDetailView） */
export interface RepoCommitDetail {
  sha: string
  authorName: string
  authorEmail: string
  time: string
  subject: string
  body: string
  files: RepoCommitFile[]
}

/** CAP-113 FR-01 单文件 diff（对应后端 RepoFileDiffView；patch 为 unified diff 原文） */
export interface RepoFileDiff {
  path: string
  additions?: number | null
  deletions?: number | null
  binary: boolean
  truncated: boolean
  patch: string
}

/** CAP-113 FR-02 单文件原文（对应后端 RepoFileContentView） */
export interface RepoFileContent {
  path: string
  ref: string
  size: number
  binary: boolean
  truncated: boolean
  content: string
}
