// 会话能力（CAP-05）的类型定义，与后端 devmind-session 模块对齐

export type SessionState =
  | 'RUNNING'
  | 'WAITING_INPUT'
  | 'WAITING_AUTH'
  | 'DONE'
  | 'FAILED'
  | 'SUSPENDED'
  | 'TERMINATED'

export interface SessionSummary {
  id: string
  projectId: string
  workItemId?: string
  requirementId?: string
  taskSpec: string
  status: string
  state: SessionState
  worktreePath?: string
  pid?: number
  model?: string
  summary?: string
  createdAt: string
  updatedAt: string
  finishedAt?: string
}

export interface SessionEvent {
  seq: number
  type:
    | 'system'
    | 'assistant'
    | 'user'
    | 'tool_use'
    | 'tool_result'
    | 'text_delta'
    | 'permission_request'
    | 'permission_result'
    | 'result'
    | 'error'
    | 'state'
    | 'log'
  content?: string
  source?: string
  timestamp: number
  payload?: Record<string, unknown>
}

export interface SessionTemplate {
  id?: number
  code: string
  name: string
  prompt: string
  sortOrder: number
  enabled: boolean
}

export interface DiffView {
  stat: string
  files: string[]
  hasChanges: boolean
}

// WebSocket 帧：服务端→客户端
export type WsServerFrame =
  | { type: 'snapshot'; sessionId: string; seq: number; events: SessionEvent[] }
  | { type: 'event'; seq: number; event: SessionEvent }
  | { type: 'error'; message: string }
  | { type: 'pong' }
