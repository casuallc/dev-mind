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
  /** CAP-21：远程执行节点 id；空 = 历史本机会话（CAP-34 起新会话恒有值） */
  agentNodeId?: string
  /** CAP-31：会话关联仓库名快照（主库在前；空 = 兼容旧单库路径） */
  repoNames?: string[]
  /** CAP-42：创建者用户名（收口鉴权：本人或 admin） */
  createdBy?: string
  /** CAP-42：固定工作区收口状态（OPEN=占用中 / FINALIZED=已收口；空 = 旧会话或非代码会话） */
  workspaceState?: string | null
  createdAt: string
  updatedAt: string
  finishedAt?: string
}

/** CAP-42：手动收口结果（POST /sessions/{id}/finalize；失败走 409 错误透传） */
export interface FinalizeAck {
  ok: boolean
  detail?: string
  error?: string
}

/** CAP-31：单仓库 diff 摘要（GET /sessions/{id}/diff 按库返回列表；单库失败只填 error） */
export interface RepoDiffView {
  repoName: string
  primary: boolean
  stat: string
  files: string[]
  hasChanges: boolean
  error?: string
}

/** CAP-39：会话产出文件列表项（GET /sessions/{id}/outputs） */
export interface SessionOutputFile {
  fileName: string
  sizeBytes: number
  updatedAt: string
}

export interface SessionOutputContent {
  fileName: string
  content: string
}

/** CAP-39：按需回传结果（POST collect；message = 降级提示：历史会话/节点离线/老 runner 等） */
export interface CollectOutputsResult {
  collected: boolean
  message?: string
  files: SessionOutputFile[]
}

export type PublishDocKind = 'analysis' | 'design' | 'requirement'

/** CAP-39 FR-03：推送产出为需求文档请求（POST /sessions/{id}/outputs/publish） */
export interface PublishOutputRequest {
  fileName: string
  kind: PublishDocKind
  requirementId: string
  mode: 'create' | 'update'
  /** update 必填（须与需求/类型一致） */
  docId?: number
  title?: string
  changeNote?: string
}

export interface PublishOutputResult {
  docId: number
  versionNo: number
  designId?: string
}
