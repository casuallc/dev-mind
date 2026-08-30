// 会话能力（CAP-05）的接口封装：页面只依赖本文件，不直接碰 shared client
import { api } from '../../shared/api/client'
import type { SessionSummary } from './types'

export function listSessions(): Promise<SessionSummary[]> {
  return api.get<SessionSummary[]>('/sessions')
}

export function getSession(id: string): Promise<SessionSummary> {
  return api.get<SessionSummary>(`/sessions/${id}`)
}
