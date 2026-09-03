// Agent 节点能力（CAP-21）的接口封装：页面只依赖本文件，不直接碰 shared client
import { api } from '../../shared/api/client'
import { getAccessToken } from '../auth/authStore'
import type { AgentNode, IssuedNode, RunnerPackage, UpgradeResult } from './types'

export function listAgentNodes(): Promise<AgentNode[]> {
  return api.get<AgentNode[]>('/agent-nodes')
}

export function createAgentNode(body: { name: string; labels?: string }): Promise<IssuedNode> {
  return api.post<IssuedNode>('/agent-nodes', body)
}

export function disableAgentNode(id: number): Promise<AgentNode> {
  return api.post<AgentNode>(`/agent-nodes/${id}/disable`)
}

export function enableAgentNode(id: number): Promise<AgentNode> {
  return api.post<AgentNode>(`/agent-nodes/${id}/enable`)
}

export function deleteAgentNode(id: number): Promise<void> {
  return api.del(`/agent-nodes/${id}`)
}

export function setAgentNodeDefault(id: number): Promise<AgentNode> {
  return api.post<AgentNode>(`/agent-nodes/${id}/default`)
}

export function unsetAgentNodeDefault(id: number): Promise<AgentNode> {
  return api.post<AgentNode>(`/agent-nodes/${id}/unset-default`)
}

// ---------------- FR-09 runner 包托管与手动升级 ----------------

/** 当前托管包；未上传时后端 404，调用方 catch 视为 null */
export function getRunnerPackage(): Promise<RunnerPackage> {
  return api.get<RunnerPackage>('/agent-nodes/runner-package')
}

export function uploadRunnerPackage(file: File): Promise<RunnerPackage> {
  const form = new FormData()
  form.append('file', file)
  return api.upload<RunnerPackage>('/agent-nodes/runner-package', form)
}

export function upgradeAgentNode(id: number): Promise<UpgradeResult> {
  return api.post<UpgradeResult>(`/agent-nodes/${id}/upgrade`)
}

/** 管理员下载托管 jar（api client 只解 JSON，二进制走原生 fetch + blob） */
export async function downloadRunnerPackage(): Promise<void> {
  const res = await fetch('/api/agent-nodes/runner-package/download', {
    headers: { Authorization: `Bearer ${getAccessToken() ?? ''}` },
  })
  if (!res.ok) throw new Error(`下载失败: ${res.status}`)
  const blob = await res.blob()
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'devmind-agent-runner.jar'
  a.click()
  URL.revokeObjectURL(a.href)
}
