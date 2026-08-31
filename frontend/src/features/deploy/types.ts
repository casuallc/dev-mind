// CAP-09 部署执行器
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
  taskId: string | null
  serverId: number
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
