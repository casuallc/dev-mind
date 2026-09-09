// CAP-02 项目管理的类型定义，与后端 devmind-project 模块对齐

export interface Project {
  id: string
  name: string
  path: string
  defaultBranch?: string
  tags: string[]
  description?: string
  status: string // ACTIVE / ARCHIVED
  /** CAP-23：主库来源镜像（LOCAL / CLONE；空 = 存量纯本地项目） */
  sourceType?: string
  /** CAP-23：主库克隆状态镜像（NONE / CLONING / READY / FAILED） */
  cloneStatus?: string
  apiDocSource?: string
  /** CAP-10 FR-05：部署成功后自动对该项目全部套件做回归 */
  autoRegressionOnDeploy?: boolean
  /** CAP-21：默认执行节点 id（空 = 本机）；新建会话未显式选节点时继承 */
  agentNodeId?: string
  contextSummary?: string
  summaryGeneratedAt?: string
  ownerId?: string
  createdAt: string
  updatedAt: string
}

export interface ProjectInput {
  name: string
  /** LOCAL 模式必填；CLONE 模式由服务端计算（无需传） */
  path?: string
  /** CAP-23：LOCAL（默认）/ CLONE（从 Git 克隆） */
  sourceType?: string
  /** CAP-23 CLONE 模式必填：远端仓库地址（http/https） */
  remoteUrl?: string
  /** CAP-23 CLONE 模式可选：克隆认证所用集成实例（不传 = 公开仓库匿名克隆） */
  integrationId?: number | null
  defaultBranch?: string
  tags: string[]
  description?: string
  status?: string
  apiDocSource?: string
  autoRegressionOnDeploy?: boolean
  /** CAP-21：默认执行节点 id；空串 = 清除回本机（不传 = 保持不变） */
  agentNodeId?: string
}

// P0-4 项目多库模型
export interface ProjectRepo {
  id: number
  projectId: string
  name: string
  path: string
  /** CAP-23：LOCAL / CLONE */
  sourceType?: string
  remoteUrl?: string
  integrationId?: number | null
  defaultBranch?: string
  role: string // CODE / DOCS / CONFIG
  primary: boolean
  sortOrder: number
  /** CAP-23 克隆状态机：NONE / CLONING / READY / FAILED */
  cloneStatus?: string
  cloneError?: string
  clonedAt?: string
  createdAt: string
  updatedAt: string
}

export interface ProjectRepoInput {
  name: string
  /** LOCAL 模式必填；CLONE 模式由服务端计算（无需传） */
  path?: string
  /** CAP-23：LOCAL（默认）/ CLONE */
  sourceType?: string
  remoteUrl?: string
  integrationId?: number | null
  defaultBranch?: string
  role?: string
  primary?: boolean
  sortOrder?: number
}

/**
 * CAP-29 全局代码仓库视图（/api/repos，对应后端 GitRepoView）。
 * 与 features/repos/types.ts 的 GitRepo 同口径，此处自包含副本避免跨 feature 私引。
 * 项目「添加仓库 → 从已有仓库选择」用它列出可复用的全局行。
 */
export interface GlobalRepo {
  id: number
  name: string
  localPath: string
  remoteUrl?: string
  defaultBranch?: string
  sourceType: string // LOCAL / CLONE
  integrationId?: number
  cloneStatus?: string // NONE / CLONING / READY / FAILED
  status: string // ACTIVE / DISABLED
}

// P1-1 环境模型（部署/测试目标；CAP-36 起聚合 Agent 节点而非服务器）
export interface ProjectEnvironment {
  id: number
  projectId: string
  name: string // DEV / TEST / STAGING / PROD
  description?: string
  /** 环境内可用的 Agent 节点 id（字符串，引用 agent_nodes） */
  nodeIds: string[]
  variables: Record<string, string>
  secrets: string[]
  createdAt: string
  updatedAt: string
}

export interface EnvironmentInput {
  name: string
  description?: string
  nodeIds: string[]
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
