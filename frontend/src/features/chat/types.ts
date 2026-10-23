// 通用问答能力（CAP-30）的类型定义，与后端 devmind-chat 模块对齐。
// 对话事件/状态/WS 帧等通用类型直接用 src/shared/chat。
import type { ChatExecutor, SessionState } from '../../shared/chat/types'

export type { SessionState, ChatEvent, WsServerFrame, ChatExecutor } from '../../shared/chat/types'

/** CAP-84：知识会话检索/方案选项（仅 MODEL 执行体、绑库时生效） */
export interface ChatKnowledgeOptions {
  topK?: number
  docTypes?: string[]
  tags?: string[]
  requireCitation?: boolean
  /** SOLUTION 模板：FAULT | DEPLOY | DEV | FAQ（空 = 按问题关键词自动判别） */
  template?: string
}

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
  /** CAP-84：多库绑定（最多 5 个）；历史 CAP-46 单库会话为空 */
  knowledgeBaseIds?: number[] | null
  /** CAP-84：知识模式 ANSWER | SOLUTION；null = 历史单库（CAP-46）会话 */
  knowledgeMode?: string | null
  /** CAP-84：检索/方案选项 */
  knowledgeOptions?: ChatKnowledgeOptions | null
  /** CAP-84 FR-20：检索通道降级原因（NONE/NO_EMBEDDING/DIMENSION_MISMATCH） */
  knowledgeDegradedReason?: string | null
  /** CAP-49：执行体，恒有值（历史行回落 AGENT） */
  executor?: ChatExecutor
  /** CAP-49：模型执行体钉住的端点 id（仅 MODEL 有值） */
  modelEndpointId?: number | null
  /** CAP-49：端点名；端点被停用/删除后为 null（会话仍可查看/删除，继续提问会 409） */
  modelEndpointName?: string | null
  /** CAP-49：端点当时配置的模型名 */
  modelEndpointModel?: string | null
  /** 用量账本：累计成本（null = 历史问答或无用量上报的执行体，下同） */
  costUsd?: number | null
  inputTokens?: number | null
  outputTokens?: number | null
  cacheReadTokens?: number | null
  cacheCreationTokens?: number | null
  /** 已入账回合数 */
  turnCount?: number | null
}
