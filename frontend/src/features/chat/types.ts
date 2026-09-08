// 通用问答能力（CAP-30）的类型定义，与后端 devmind-chat 模块对齐。
// 对话事件/状态/WS 帧等通用类型直接用 src/shared/chat。
import type { SessionState } from '../../shared/chat/types'

export type { SessionState, ChatEvent, WsServerFrame } from '../../shared/chat/types'

export interface ChatSummary {
  id: string
  title: string
  status: string
  state: SessionState
  pid?: number
  model?: string
  permissionMode?: string
  summary?: string
  /** 远程执行节点 id；空 = 历史本机问答（CAP-34 起新问答恒有值） */
  agentNodeId?: string
  createdBy?: string
  createdAt: string
  updatedAt: string
  finishedAt?: string
}
