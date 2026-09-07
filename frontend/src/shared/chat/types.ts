// 对话式 agent 交互的共享类型（CAP-30 由 sessions 上移）：项目会话（/sessions）与
// 通用问答（/chats）共用同一套事件模型与 WS 帧协议，只有 REST/WS 路径前缀不同。

export type SessionState =
  | 'RUNNING'
  | 'WAITING_INPUT'
  | 'WAITING_AUTH'
  | 'DONE'
  | 'FAILED'
  | 'SUSPENDED'
  | 'TERMINATED'

/** CAP-32 消息图片附件引用（事件 payload.attachments 元素；二进制本体在附件模块，这里只有引用） */
export interface ChatImageAttachment {
  attachmentId: string
  name?: string
  contentType?: string
}

export interface ChatEvent {
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

// WebSocket 帧：服务端→客户端
export type WsServerFrame =
  | { type: 'snapshot'; sessionId: string; seq: number; events: ChatEvent[] }
  | { type: 'event'; seq: number; event: ChatEvent }
  | { type: 'error'; message: string }
  | { type: 'pong' }

/** ChatPanel 实时流连接状态（外层做徽标） */
export interface StreamMeta {
  connected: boolean
  fatal: boolean
}

/** ChatPanel 需要的最小摘要：项目会话传 taskSpec 作开场气泡，问答不传（首条消息已在事件流里） */
export interface ChatSummaryBase {
  id: string
  state: SessionState
  topic?: string
  model?: string
}

/** 两类对话资源的 REST/WS 前缀（路径段同名：/api<X> 与 /ws<X>） */
export type ChatApiBase = '/sessions' | '/chats'
