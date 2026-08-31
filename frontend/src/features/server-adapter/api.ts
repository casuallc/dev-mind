// CAP-07 服务器适配器 API
import { api } from '../../shared/api/client'
import type {
  AuditView,
  ConnectResult,
  ExecResult,
  HealthResult,
  ServerListItem,
  StoredConfig,
  TemplateInput,
  TemplateView,
} from './types'

const BASE = '/servers'

// ---- 服务器运维 ----
export const listServers = () => api.get<ServerListItem[]>(BASE)
export const testServer = (id: number) => api.post<ConnectResult>(`${BASE}/${id}/test`)
export const healthServer = (id: number, body: { type: string; url?: string; expectedStatus?: number; command?: string }) =>
  api.post<HealthResult>(`${BASE}/${id}/health`, body)
export const execTemplate = (id: number, body: { templateCode: string; params: Record<string, string>; capability?: string }) =>
  api.post<ExecResult>(`${BASE}/${id}/execute`, body)
export const serverLogs = (id: number, template?: string) =>
  api.get<ExecResult>(`${BASE}/${id}/logs${template ? `?template=${template}` : ''}`)
export const storedConfig = (id: number) => api.get<StoredConfig>(`${BASE}/${id}/stored-config`)
export const serverAudit = (id: number, limit = 50) =>
  api.get<AuditView[]>(`${BASE}/${id}/audit?limit=${limit}`)

/** 上传（multipart，需自定义 fetch 不带 Content-Type） */
export const uploadToServer = async (id: number, file: File, remotePath: string) => {
  const form = new FormData()
  form.append('file', file)
  form.append('remotePath', remotePath)
  const res = await fetch(`/api/servers/${id}/upload`, { method: 'POST', body: form })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `上传失败(${res.status})`)
  }
  return res.json() as Promise<{ ok: boolean; message: string }>
}

export const downloadFromServer = async (id: number, remotePath: string) => {
  const res = await fetch(`/api/servers/${id}/download?path=${encodeURIComponent(remotePath)}`)
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `下载失败(${res.status})`)
  }
  return res.text()
}

// ---- 命令模板白名单 ----
export const listTemplates = (projectId?: string) =>
  api.get<TemplateView[]>(`/script-templates${projectId ? `?projectId=${projectId}` : ''}`)
export const createTemplate = (input: TemplateInput) => api.post<TemplateView>('/script-templates', input)
export const updateTemplate = (id: number, input: TemplateInput) => api.put<TemplateView>(`/script-templates/${id}`, input)
export const deleteTemplate = (id: number) => api.del(`/script-templates/${id}`)

// ---- 审计 ----
export const listAudit = (params: { projectId?: string; serverId?: number; action?: string; limit?: number } = {}) => {
  const q = new URLSearchParams()
  if (params.projectId) q.set('projectId', params.projectId)
  if (params.serverId) q.set('serverId', String(params.serverId))
  if (params.action) q.set('action', params.action)
  q.set('limit', String(params.limit ?? 100))
  return api.get<AuditView[]>(`/audit-logs?${q.toString()}`)
}
