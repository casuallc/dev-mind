// 会话能力（CAP-05）的接口封装：页面只依赖本文件，不直接碰 shared client
import { api } from '../../shared/api/client'
import type { RepoDiffView, SessionSummary, SessionEvent } from './types'

// CAP-31：会话归属当前项目——projectId 为首参（工作台在 ProjectContextGate 内，必有当前项目）
export function listSessions(
  projectId?: string,
  status?: string,
  workItemId?: string,
  requirementId?: string,
): Promise<SessionSummary[]> {
  const params = new URLSearchParams()
  if (projectId) params.set('projectId', projectId)
  if (status && status !== 'ALL') params.set('status', status)
  if (workItemId) params.set('workItemId', workItemId)
  if (requirementId) params.set('requirementId', requirementId)
  const q = params.toString()
  return api.get<SessionSummary[]>(`/sessions${q ? `?${q}` : ''}`)
}

export function getSession(id: string): Promise<SessionSummary> {
  return api.get<SessionSummary>(`/sessions/${id}`)
}

export function createSession(body: {
  /** CAP-33：场景 code（骨架渲染 + 预装配上下文包；旧 templateCode 参数后端仍兼容） */
  scenarioCode?: string
  projectId?: string
  workItemId?: string
  requirementId?: string
  taskSpec: string
  baseBranch?: string
  model?: string
  permissionMode?: string
  agentNodeId?: string
  /** CAP-34 FR-07：标签要求（CSV），仅标签全覆盖的节点可被调度 */
  requiredLabels?: string
  /** CAP-31：关联仓库（project_repos id 列表）；空 = 主库；>1 = 多库聚合目录 */
  repoIds?: number[]
}): Promise<SessionSummary> {
  return api.post<SessionSummary>('/sessions', body)
}

export function sessionEvents(id: string, afterSeq = -1): Promise<SessionEvent[]> {
  return api.get<SessionEvent[]>(`/sessions/${id}/events?afterSeq=${afterSeq}`)
}

export function sendInput(id: string, text: string): Promise<void> {
  return api.post(`/sessions/${id}/input`, { text })
}

export function authorize(
  id: string,
  accepted: boolean,
  scope: string,
  requestId?: string,
): Promise<void> {
  return api.post(`/sessions/${id}/authorize`, { accepted, scope, requestId })
}

export function suspendSession(id: string): Promise<SessionSummary> {
  return api.post<SessionSummary>(`/sessions/${id}/suspend`)
}

export function resumeSession(id: string): Promise<SessionSummary> {
  return api.post<SessionSummary>(`/sessions/${id}/resume`)
}

export function killSession(id: string): Promise<SessionSummary> {
  return api.post<SessionSummary>(`/sessions/${id}/kill`)
}

/** 优雅结束：关闭 stdin，claude 自然退出。 */
export function finishSession(id: string): Promise<void> {
  return api.post(`/sessions/${id}/finish`)
}

export function sessionDiff(id: string): Promise<RepoDiffView[]> {
  return api.get<RepoDiffView[]>(`/sessions/${id}/diff`)
}

export function removeWorktree(id: string): Promise<void> {
  return api.del(`/sessions/${id}/worktree`)
}

export function deleteSession(id: string): Promise<void> {
  return api.del(`/sessions/${id}`)
}
