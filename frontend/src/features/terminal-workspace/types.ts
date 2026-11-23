// CAP-115 终端工作区 DTO 前端镜像（字段对齐 devmind-session dto 驼峰序列化）

/** 终端工作区视图（TerminalWorkspaceView） */
export interface TerminalWorkspaceView {
  id: string
  projectId: string
  requirementId?: string | null
  workItemId?: string | null
  /** 工作区键：term-<8位短id> 或挂接的 req-<需求id>/sid-<会话id> */
  workspaceKey: string
  branch: string
  owner: string
  agentNodeId: string
  baseBranch: string
  /** OPEN / FINALIZED / RELEASED */
  state: string
  lockSessionId?: string | null
  lockOwner?: string | null
  lockAt?: string | null
  lastActiveAt?: string | null
  createdBy: string
  createdAt: string
  finalizedAt?: string | null
  /** repos_json 快照（camelCase 键） */
  repos: Record<string, unknown>[]
}

/** 终端会话视图（TerminalSessionView） */
export interface TerminalSessionView {
  id: string
  workspaceId: string
  userId: string
  /** RUNNING / SUSPENDED / CLOSED */
  state: string
  startedAt: string
  lastHeartbeatAt?: string | null
  endedAt?: string | null
}

/** 打开终端应答（TerminalOpenView） */
export interface TerminalOpenView {
  session: TerminalSessionView
  workspaceKey: string
  /** runner 侧聚合根绝对路径（展示用） */
  path: string
  branch: string
}

/** 命令历史条目（AgentAuditBridge.Entry） */
export interface TerminalHistoryEntry {
  createdAt: string
  event: string
  nodeName?: string | null
  actor?: string | null
  target?: string | null
  result?: string | null
  durationMs?: number | null
  detail?: string | null
}

/** 文件直写应答（{path, sha}） */
export interface TerminalFileWriteResult {
  path: string
  sha: string
}
