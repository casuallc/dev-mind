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

/** FR-09 服务端托管的 runner 包（全局单份，上传即替换） */
export interface RunnerPackage {
  id: number
  version: string
  sha256: string
  sizeBytes: number
  originalFilename?: string
  uploadedAt?: string
  uploadedBy?: string
}

export type UpgradeStatus = 'ACCEPTED' | 'BUSY' | 'ALREADY_LATEST' | 'REJECTED'

/** 手动升级结果（后端恒 200，业务结果看 status） */
export interface UpgradeResult {
  status: UpgradeStatus
  message: string
  activeSessions?: number
}
