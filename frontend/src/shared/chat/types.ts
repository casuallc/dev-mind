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

/** CAP-54：单个变更文件（git porcelain + numstat 合并；未跟踪文件无 adds/dels） */
export interface WorkspaceChange {
  path: string
  /** 原始 porcelain XY 状态码（如 'M '、' M'、'A '、'??'、'R '） */
  code: string
  adds?: number
  dels?: number
}

/** CAP-54：一个仓库的变更快照（工作区可能是多仓聚合目录） */
export interface WorkspaceRepoStatus {
  /** 相对工作区根的库名（代码目录本身是库时为 ''） */
  name: string
  branch?: string
  changes: WorkspaceChange[]
  /** 该库采集失败（git 缺失/超时…），不拖垮整组 */
  error?: string
}

/** CAP-54：工作区 git 变更快照（旁路最新值，不进事件回放；问答沙箱非 git 时 gitAvailable=false） */
export interface WorkspaceSnapshot {
  /** 采集时刻（epoch millis） */
  ts?: number
  gitAvailable: boolean
  repos: WorkspaceRepoStatus[]
  total?: { files: number; adds: number; dels: number }
}

// WebSocket 帧：服务端→客户端。error = 致命（会话已无运行时，前端关连接不再重连）；
// notice = 非致命提示（CAP-49：某个上行动作被拒，如"没有正在生成的回答"），连接与事件流照旧。
// workspace = CAP-54 工作区快照旁路（最新值语义，老前端忽略未知帧天然兼容）。
export type WsServerFrame =
  | { type: 'snapshot'; sessionId: string; seq: number; events: ChatEvent[] }
  | { type: 'event'; seq: number; event: ChatEvent }
  | { type: 'error'; message: string }
  | { type: 'notice'; message: string }
  | { type: 'pong' }
  | { type: 'workspace'; snapshot: WorkspaceSnapshot }

/** ChatPanel 实时流连接状态（外层做徽标） */
export interface StreamMeta {
  connected: boolean
  fatal: boolean
}

/** 执行体：AGENT = runner 节点上的 claude CLI（默认）｜MODEL = 服务端直连已接入的 CHAT 端点（CAP-49） */
export type ChatExecutor = 'AGENT' | 'MODEL'

/** ChatPanel 需要的最小摘要：项目会话传 taskSpec 作开场气泡，问答不传（首条消息已在事件流里） */
export interface ChatSummaryBase {
  id: string
  state: SessionState
  topic?: string
  model?: string
  /** CAP-49：缺省视作 AGENT（项目会话不走这条维度） */
  executor?: ChatExecutor
  /** 模型执行体的端点名（端点被停用/删除后为 null，此时继续提问会 409） */
  modelEndpointName?: string | null
}

/** 两类对话资源的 REST/WS 前缀（路径段同名：/api<X> 与 /ws<X>） */
export type ChatApiBase = '/sessions' | '/chats'
