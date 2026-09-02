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
