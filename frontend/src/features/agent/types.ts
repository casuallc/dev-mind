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
  /** 最近一次接入的远端地址（IP:端口），离线后保留便于排查 */
  remoteAddr?: string
  /** 平台默认执行节点：会话/项目未指定节点时调度到此（全平台至多一个） */
  isDefault?: boolean
  /** 工作区磁盘占用（字节，CAP-34 FR-05；旧 runner 未上报为 undefined） */
  workspaceBytes?: number
  /** WS 协议版本（CAP-34 FR-08；未上报的老 runner 按 v1 对待） */
  protocolVersion?: number
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

/** 节点连接事件类型 */
export type ConnLogEvent = 'CONNECT' | 'REJECT' | 'DISCONNECT'

/** 节点连接流水（REJECT 时 nodeId/nodeName 为空，只有来源地址） */
export interface AgentConnLog {
  id: number
  nodeId?: number
  nodeName?: string
  event: ConnLogEvent
  remoteAddr?: string
  detail?: string
  createdAt?: string
}
