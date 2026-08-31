// 会话能力（CAP-05）的接口封装：页面只依赖本文件，不直接碰 shared client
import { api } from '../../shared/api/client'
import type { DiffView, SessionSummary, SessionEvent, SessionTemplate } from './types'

export function listSessions(status?: string, requirementId?: string): Promise<SessionSummary[]> {
  const params = new URLSearchParams()
  if (status && status !== 'ALL') params.set('status', status)
  if (requirementId) params.set('requirementId', requirementId)
  const q = params.toString()
  return api.get<SessionSummary[]>(`/sessions${q ? `?${q}` : ''}`)
}

export function getSession(id: string): Promise<SessionSummary> {
  return api.get<SessionSummary>(`/sessions/${id}`)
}

export function createSession(body: {
  templateCode?: string
  projectId?: string
  requirementId?: string
  taskSpec: string
  baseBranch?: string
  model?: string
  permissionMode?: string
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

export function sessionDiff(id: string): Promise<DiffView> {
  return api.get<DiffView>(`/sessions/${id}/diff`)
}

export function removeWorktree(id: string): Promise<void> {
  return api.del(`/sessions/${id}/worktree`)
}

export function deleteSession(id: string): Promise<void> {
  return api.del(`/sessions/${id}`)
}

// ---------------- 模板 ----------------

export function listTemplates(): Promise<SessionTemplate[]> {
  return api.get<SessionTemplate[]>('/session-templates')
}

export function createTemplate(t: SessionTemplate): Promise<SessionTemplate> {
  return api.post<SessionTemplate>('/session-templates', t)
}

export function updateTemplate(t: SessionTemplate): Promise<SessionTemplate> {
  return api.put<SessionTemplate>(`/session-templates/${t.id}`, t)
}

export function deleteTemplate(id: number): Promise<void> {
  return api.del(`/session-templates/${id}`)
}

export function previewTemplate(code: string, vars: Record<string, string>): Promise<string> {
  return api
    .post<{ rendered: string }>(`/session-templates/${code}/preview`, vars)
    .then((r) => r.rendered)
}
