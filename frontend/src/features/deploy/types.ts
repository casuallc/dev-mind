// CAP-09 部署执行器 + CAP-11 发版执行器（发版归属交付域，与部署同模块）
export type DeployStatus = 'PLANNED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'ROLLED_BACK'
export type StepStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED'
export type StepType = 'artifact' | 'backup' | 'deploy' | 'start' | 'health'

export interface DeployStepInput {
  name: string
  type: string
  templateCode: string
  params: Record<string, string>
}

export interface DeployConfig {
  projectId: string
  steps: DeployStepInput[]
  rollbackSteps: DeployStepInput[]
  updatedAt: string | null
}

export interface DeployStep {
  id: number
  seq: number
  name: string
  type: string
  status: StepStatus
  detail: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface DeploymentRecord {
  id: number
  projectId: string
  workItemId: string | null
  /** 执行节点 id（runner；创建时显式指定或经环境/路由链解析） */
  agentNodeId: string
  environmentId: number | null
  buildId: number | null
  env: string
  status: DeployStatus
  currentStep: number
  backupRef: string | null
  rollbackOf: number | null
  confirmRequired: boolean
  confirmed: boolean
  errorSummary: string | null
  createdBy: string
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  plan: DeployStepInput[]
  steps: DeployStep[]
}

// ---- CAP-11 发版执行器（与后端 devmind-release / release_config 对齐） ----

export type ReleaseStatus = 'PLANNED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'ROLLED_BACK'
/** CAP-36：REMOTE/SSH 已下线，远程执行统一 AGENT（runner 节点） */
export type ReleaseExecutor = 'LOCAL' | 'AGENT'

/** /projects/{id}/release-config（CAP-02 发版配置，已含 CAP-11 执行方式） */
export interface ReleaseConfig {
  id: number
  projectId: string
  nexusRepo?: string
  scriptTemplateRef?: string
  versionRule?: string
  executor?: string // LOCAL / AGENT
  /** AGENT 执行的目标节点 id（空 = 路由链） */
  agentNodeId?: string
}

export interface ReleaseConfigInput {
  nexusRepo?: string
  scriptTemplateRef?: string
  versionRule?: string
  executor?: string
  agentNodeId?: string
}

export interface ReleaseRecord {
  id: number
  projectId: string
  workItemId?: string
  buildId?: number
  version: string
  status: ReleaseStatus
  artifactRef?: string
  nexusRef?: string
  tagName?: string
  executor: string
  agentNodeId?: string
  rollbackOf?: number
  errorSummary?: string
  createdBy?: string
  startedAt?: string
  finishedAt?: string
  createdAt: string
}

export interface CreateReleaseInput {
  projectId: string
  workItemId?: string
  buildId?: number
  version?: string
  executor?: string
  agentNodeId?: string
  force?: boolean
}
