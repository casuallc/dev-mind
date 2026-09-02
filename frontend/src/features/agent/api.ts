// Agent 节点能力（CAP-21）的接口封装：页面只依赖本文件，不直接碰 shared client
import { api } from '../../shared/api/client'
import type { AgentNode, IssuedNode } from './types'

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
