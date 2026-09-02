// Agent 节点能力（CAP-21）的类型定义，与后端 devmind-agent 模块对齐

export type AgentNodeStatus = 'ONLINE' | 'OFFLINE' | 'DISABLED'

export interface AgentNode {
  id: number
  name: string
  status: AgentNodeStatus
  os?: string
  labels?: string
  capabilities?: string
  runnerVersion?: string
  lastHeartbeatAt?: string
  createdAt?: string
}

/** 创建节点响应：token 仅此一次可见 */
export interface IssuedNode {
  node: AgentNode
  token: string
}
