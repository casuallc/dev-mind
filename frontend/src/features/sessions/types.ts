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
  taskSpec: string
  status: SessionState
  baseBranch: string
  worktreePath?: string
  summary?: string
  createdAt: string
  updatedAt: string
  finishedAt?: string
}
