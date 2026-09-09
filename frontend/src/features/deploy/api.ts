// CAP-09 部署执行器 + CAP-11 发版执行器接口封装
import { api } from '../../shared/api/client'
import type {
  CreateReleaseInput,
  DeployConfig,
  DeployStepInput,
  DeploymentRecord,
  ReleaseConfig,
  ReleaseConfigInput,
  ReleaseRecord,
} from './types'

export function getDeployConfig(projectId: string): Promise<DeployConfig> {
  return api.get<DeployConfig>(`/projects/${projectId}/deploy-config`)
}

export function saveDeployConfig(
  projectId: string,
  input: { steps: DeployStepInput[]; rollbackSteps: DeployStepInput[] },
): Promise<DeployConfig> {
  return api.put<DeployConfig>(`/projects/${projectId}/deploy-config`, input)
}

export function deleteDeployConfig(projectId: string): Promise<void> {
  return api.del(`/projects/${projectId}/deploy-config`)
}

export interface CreateDeploymentInput {
  projectId: string
  /** 目标执行节点 id（可空 = 环境首节点 → 路由链：项目默认 → 平台默认）；与环境同传时校验属于该环境 */
  agentNodeId?: string
  /** P1-1 环境：提供节点组与变量注入，env 名以环境名为准 */
  environmentId?: number
  buildId?: number
  workItemId?: string
  env?: string
  confirmRequired?: boolean
  force?: boolean
  plan?: DeployStepInput[]
}

export function createDeployment(input: CreateDeploymentInput): Promise<DeploymentRecord> {
  return api.post<DeploymentRecord>('/deployments', input)
}

export function getDeployment(id: number): Promise<DeploymentRecord> {
  return api.get<DeploymentRecord>(`/deployments/${id}`)
}

export function executeDeployment(id: number): Promise<DeploymentRecord> {
  return api.post<DeploymentRecord>(`/deployments/${id}/execute`)
}

export function confirmDeployment(id: number): Promise<DeploymentRecord> {
  return api.post<DeploymentRecord>(`/deployments/${id}/confirm`)
}

export function rollbackDeployment(id: number): Promise<DeploymentRecord> {
  return api.post<DeploymentRecord>(`/deployments/${id}/rollback`)
}

export function listDeployments(projectId: string, status?: string): Promise<DeploymentRecord[]> {
  const q = status ? `?projectId=${projectId}&status=${status}` : `?projectId=${projectId}`
  return api.get<DeploymentRecord[]>(`/deployments${q}`)
}

/** 日志为纯文本，走原生 fetch */
export async function getDeploymentLogs(id: number): Promise<string> {
  const res = await fetch(`/api/deployments/${id}/logs`)
  if (!res.ok) throw new Error(`${res.status}`)
  return res.text()
}

// ---------------- CAP-11 发版配置（/projects/{id}/release-config） ----------------

export function getReleaseConfig(id: string): Promise<ReleaseConfig | null> {
  return api.get<ReleaseConfig | null>(`/projects/${id}/release-config`)
}

export function saveReleaseConfig(id: string, input: ReleaseConfigInput): Promise<ReleaseConfig> {
  return api.post<ReleaseConfig>(`/projects/${id}/release-config`, input)
}

// ---------------- 发版记录 ----------------

export function createRelease(input: CreateReleaseInput): Promise<ReleaseRecord> {
  return api.post<ReleaseRecord>('/releases', input)
}

export function getRelease(id: number): Promise<ReleaseRecord> {
  return api.get<ReleaseRecord>(`/releases/${id}`)
}

export function executeRelease(id: number): Promise<ReleaseRecord> {
  return api.post<ReleaseRecord>(`/releases/${id}/execute`)
}

export function rollbackRelease(id: number): Promise<ReleaseRecord> {
  return api.post<ReleaseRecord>(`/releases/${id}/rollback`)
}

export function listReleases(projectId: string, status?: string): Promise<ReleaseRecord[]> {
  const q = status ? `?projectId=${projectId}&status=${status}` : `?projectId=${projectId}`
  return api.get<ReleaseRecord[]>(`/releases${q}`)
}

export function deleteRelease(id: number): Promise<void> {
  return api.del(`/releases/${id}`)
}

/** 全量日志为纯文本，走原生 fetch */
export async function getReleaseLogs(id: number): Promise<string> {
  const res = await fetch(`/api/releases/${id}/logs`)
  if (!res.ok) throw new Error(`${res.status}`)
  return res.text()
}
