// 通用问答能力（CAP-30）的类型定义，与后端 devmind-chat 模块对齐。
// 对话事件/状态/WS 帧等通用类型直接用 src/shared/chat。
import type { ChatExecutor, SessionState } from '../../shared/chat/types'

export type { SessionState, ChatEvent, WsServerFrame, ChatExecutor } from '../../shared/chat/types'

export interface ChatSummary {
  id: string
  title: string
  status: string
  state: SessionState
  pid?: number
  model?: string
  permissionMode?: string
  summary?: string
  /** 远程执行节点 id；空 = 模型执行体（不需要节点）或历史本机问答（CAP-34 起新 Agent 问答恒有值） */
  agentNodeId?: string
  createdBy?: string
  createdAt: string
  updatedAt: string
  finishedAt?: string
  /** CAP-46：绑定的知识库 id；空 = 普通问答 */
  knowledgeBaseId?: number | null
  /** CAP-49：执行体，恒有值（历史行回落 AGENT） */
  executor?: ChatExecutor
  /** CAP-49：模型执行体钉住的端点 id（仅 MODEL 有值） */
  modelEndpointId?: number | null
  /** CAP-49：端点名；端点被停用/删除后为 null（会话仍可查看/删除，继续提问会 409） */
  modelEndpointName?: string | null
  /** CAP-49：端点当时配置的模型名 */
  modelEndpointModel?: string | null
}
