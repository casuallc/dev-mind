import { api } from '../../shared/api/client'
import type {
  CopilotAction,
  CopilotConfig,
  CopilotHealth,
  CopilotTool,
  CopilotTurn,
  CopilotTurnDetail,
  PageView,
} from './types'

// ---- CAP-76 FR-30/32 智能体运维端点（/api/copilot/**） ----
// 除 /health（登录可读）外均仅 ADMIN（SecurityConfig 集中登记）。

export function getCopilotHealth(): Promise<CopilotHealth> {
  return api.get<CopilotHealth>('/copilot/admin/health')
}

export function getCopilotConfig(): Promise<CopilotConfig> {
  return api.get<CopilotConfig>('/copilot/admin/config')
}

export function listCopilotTools(): Promise<CopilotTool[]> {
  return api.get<CopilotTool[]>('/copilot/admin/tools')
}

export function listCopilotTurns(params: {
  channel?: string
  status?: string
  actor?: string
  keyword?: string
  page?: number
  size?: number
} = {}): Promise<PageView<CopilotTurn>> {
  const q = new URLSearchParams()
  if (params.channel) q.set('channel', params.channel)
  if (params.status) q.set('status', params.status)
  if (params.actor) q.set('actor', params.actor)
  if (params.keyword) q.set('keyword', params.keyword)
  q.set('page', String(params.page ?? 0))
  q.set('size', String(params.size ?? 20))
  return api.get<PageView<CopilotTurn>>(`/copilot/admin/turns?${q.toString()}`)
}

export function getCopilotTurn(id: string): Promise<CopilotTurnDetail> {
  return api.get<CopilotTurnDetail>(`/copilot/admin/turns/${encodeURIComponent(id)}`)
}

export function listCopilotActions(params: {
  channel?: string
  status?: string
  risk?: string
  actor?: string
  toolName?: string
  keyword?: string
  page?: number
  size?: number
} = {}): Promise<PageView<CopilotAction>> {
  const q = new URLSearchParams()
  if (params.channel) q.set('channel', params.channel)
  if (params.status) q.set('status', params.status)
  if (params.risk) q.set('risk', params.risk)
  if (params.actor) q.set('actor', params.actor)
  if (params.toolName) q.set('toolName', params.toolName)
  if (params.keyword) q.set('keyword', params.keyword)
  q.set('page', String(params.page ?? 0))
  q.set('size', String(params.size ?? 20))
  return api.get<PageView<CopilotAction>>(`/copilot/admin/actions?${q.toString()}`)
}

export function cancelCopilotAction(id: string): Promise<{ ok: boolean; message: string }> {
  return api.post<{ ok: boolean; message: string }>(
    `/copilot/admin/actions/${encodeURIComponent(id)}/cancel`,
  )
}
