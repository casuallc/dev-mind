// 会话能力（CAP-05）的类型定义，与后端 devmind-session 模块对齐。
// 对话事件/状态/WS 帧等通用类型已上移 src/shared/chat（CAP-30），此处仅 re-export 兼容既有引用。
import type { SessionState } from '../../shared/chat/types'

export type { SessionState, ChatEvent as SessionEvent, WsServerFrame } from '../../shared/chat/types'

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
  /** CAP-21：远程执行节点 id；空 = 本地 */
  agentNodeId?: string
  createdAt: string
  updatedAt: string
  finishedAt?: string
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
