// CAP-02 项目管理的类型定义，与后端 devmind-project 模块对齐

export interface Project {
  id: string
  name: string
  path: string
  defaultBranch?: string
  tags: string[]
  description?: string
  status: string // ACTIVE / ARCHIVED
  apiDocSource?: string
  /** CAP-10 FR-05：部署成功后自动对该项目全部套件做回归 */
  autoRegressionOnDeploy?: boolean
  contextSummary?: string
  summaryGeneratedAt?: string
  ownerId?: string
  createdAt: string
  updatedAt: string
}

export interface ProjectInput {
  name: string
  path: string
  defaultBranch?: string
  tags: string[]
  description?: string
  status?: string
  apiDocSource?: string
  autoRegressionOnDeploy?: boolean
}

// P0-4 项目多库模型
export interface ProjectRepo {
  id: number
  projectId: string
  name: string
  path: string
  remoteUrl?: string
  defaultBranch?: string
  role: string // CODE / DOCS / CONFIG
  primary: boolean
  sortOrder: number
  createdAt: string
  updatedAt: string
}

export interface ProjectRepoInput {
  name: string
  path: string
  remoteUrl?: string
  defaultBranch?: string
  role?: string
  primary?: boolean
  sortOrder?: number
}

export interface ProjectServer {
  id: number
  projectId: string
  name: string
  env?: string
  accessType: string
  accessConfig?: string
  capabilities: string[]
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface ServerInput {
  name: string
  env?: string
  accessType: string
  accessConfig?: string
  capabilities: string[]
  enabled: boolean
}

// P1-1 环境模型（部署/测试目标）
export interface ProjectEnvironment {
  id: number
  projectId: string
  name: string // DEV / TEST / STAGING / PROD
  description?: string
  serverIds: number[]
  variables: Record<string, string>
  secrets: string[]
  createdAt: string
  updatedAt: string
}

export interface EnvironmentInput {
  name: string
  description?: string
  serverIds: number[]
  variables: Record<string, string>
  secrets: string[]
}

export interface BuildStep {
  id: number
  projectId: string
  sortOrder: number
  name?: string
  command: string
  workingDir?: string
  location: string
}

export interface BuildStepInput {
  sortOrder: number
  name?: string
  command: string
  workingDir?: string
  location: string
}

export interface ReleaseConfig {
  id: number
  projectId: string
  nexusRepo?: string
  scriptTemplateRef?: string
  versionRule?: string
  executor?: string // CAP-11: LOCAL / REMOTE
  remoteServerId?: number
}

export interface ReleaseConfigInput {
  nexusRepo?: string
  scriptTemplateRef?: string
  versionRule?: string
  executor?: string
  remoteServerId?: number
}

export interface ProjectLock {
  projectId: string
  activeWrites: number
  maxConcurrent: number
}

export interface ContextSummary {
  projectId: string
  summary: string
  generatedAt?: string
}

export interface WorktreeInfo {
  path: string
  branch: string
  sessionId: string
}

// P0-5 任务（项目内主线；Task 内嵌 Requirement，title/description 即需求内容）
export type TaskStatus =
  | 'DRAFT' | 'DESIGNING' | 'DEVELOPING' | 'TESTING' | 'ACCEPTANCE' | 'DONE' | 'CANCELLED'

export interface Task {
  id: string
  projectId: string
  seq: number
  code: string // TASK-<seq>
  title: string
  description?: string
  status: TaskStatus
  ownerId?: string
  branchSlug?: string
  docId?: number
  createdBy?: string
  createdAt: string
  updatedAt: string
}

export interface TaskInput {
  title: string
  description?: string
  ownerId?: string
  branchSlug?: string
  docId?: number
}

// 任务主线聚合视图（app 组装层 /tasks/{taskId}/overview）
export interface TaskOverview {
  task: Task
  docs: { id: number; kind: string; title: string; status: string; currentVersion: number; updatedAt: string }[]
  sessions: { id: string; status: string; taskSpec: string; model?: string; createdAt: string; finishedAt?: string }[]
  builds: { id: number; status: string; branch?: string; commit?: string; artifactRef?: string; createdAt: string; finishedAt?: string }[]
  testRuns: { id: number; status: string; summaryJson?: string; reportDocId?: number; triggeredBy?: string; createdAt: string; finishedAt?: string }[]
  deployments: { id: number; status: string; env?: string; serverId?: number; buildId?: number; createdBy?: string; createdAt: string; finishedAt?: string }[]
  releases: { id: number; version?: string; status: string; executor?: string; rollbackOf?: number; createdAt: string; finishedAt?: string }[]
  timeline: { time: string; type: string; label: string; refId: string }[]
}
