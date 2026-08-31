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

// CAP-13 研发主线：Requirement（业务目标）/ Design（解决方案）/ WorkItem（工作单元）
export type RequirementStatus =
  | 'DRAFT' | 'ANALYZING' | 'DESIGNING' | 'IN_PROGRESS' | 'ACCEPTANCE' | 'DONE' | 'CANCELLED'

export interface Requirement {
  id: string
  projectId: string
  seq: number
  code: string // REQ-<seq>
  title: string
  description?: string
  status: RequirementStatus
  ownerId?: string
  docId?: number
  createdBy?: string
  createdAt: string
  updatedAt: string
}

export interface RequirementInput {
  title: string
  description?: string
  ownerId?: string
  docId?: number
}

export type WorkItemType = 'DESIGN' | 'DEVELOPMENT' | 'TEST' | 'DOCUMENT' | 'REVIEW'
export type WorkItemStatus = 'TODO' | 'IN_PROGRESS' | 'BLOCKED' | 'DONE' | 'CANCELLED'

export interface WorkItem {
  id: string
  projectId: string
  requirementId: string
  designId?: string
  seq: number
  code: string // WI-<seq>
  type: WorkItemType
  title: string
  spec?: string
  status: WorkItemStatus
  ownerId?: string
  branchSlug?: string
  createdBy?: string
  createdAt: string
  updatedAt: string
}

export interface WorkItemInput {
  type?: WorkItemType
  title: string
  spec?: string
  designId?: string
  ownerId?: string
  branchSlug?: string
}

export type DesignStatus = 'DRAFT' | 'CONFIRMED' | 'DISCARDED'

export interface Design {
  id: string
  projectId: string
  requirementId: string
  docId?: number
  version: number
  status: DesignStatus
  createdBy?: string
  createdAt: string
  updatedAt: string
}

// 需求主线聚合视图（app 组装层 /requirements/{requirementId}/overview）
export interface RequirementOverview {
  requirement: Requirement
  workItems: WorkItem[]
  docs: { id: number; kind: string; title: string; status: string; currentVersion: number; updatedAt: string }[]
  sessions: { id: string; status: string; taskSpec: string; model?: string; workItemId?: string; createdAt: string; finishedAt?: string }[]
  builds: { id: number; status: string; branch?: string; commit?: string; artifactRef?: string; workItemId?: string; createdAt: string; finishedAt?: string }[]
  testRuns: { id: number; status: string; summaryJson?: string; reportDocId?: number; triggeredBy?: string; workItemId?: string; createdAt: string; finishedAt?: string }[]
  deployments: { id: number; status: string; env?: string; serverId?: number; buildId?: number; workItemId?: string; createdBy?: string; createdAt: string; finishedAt?: string }[]
  releases: { id: number; version?: string; status: string; executor?: string; rollbackOf?: number; createdAt: string; finishedAt?: string }[]
  artifacts: { id: number; type: string; name?: string; path?: string; producerType?: string; createdAt: string }[]
  timeline: { time: string; type: string; label: string; refId: string }[]
}
