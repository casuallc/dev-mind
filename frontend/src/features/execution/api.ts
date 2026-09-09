// CAP-36 执行底座 API：命令模板白名单（/script-templates）+ 执行审计（/audit-logs）
// （原 CAP-07 server-adapter 接口；路由不变，仅归属迁移到 devmind-execution）
import { api } from '../../shared/api/client'
import type { AuditView, TemplateInput, TemplateView } from './types'

// ---- 命令模板白名单 ----
export const listTemplates = (projectId?: string) =>
  api.get<TemplateView[]>(`/script-templates${projectId ? `?projectId=${projectId}` : ''}`)
export const createTemplate = (input: TemplateInput) => api.post<TemplateView>('/script-templates', input)
export const updateTemplate = (id: number, input: TemplateInput) => api.put<TemplateView>(`/script-templates/${id}`, input)
export const deleteTemplate = (id: number) => api.del(`/script-templates/${id}`)

// ---- 执行审计（nodeId 对应 audit_logs.server_id 列，存 agent_nodes 数值 id） ----
export const listAudit = (params: { projectId?: string; nodeId?: number; action?: string; limit?: number } = {}) => {
  const q = new URLSearchParams()
  if (params.projectId) q.set('projectId', params.projectId)
  if (params.nodeId != null) q.set('serverId', String(params.nodeId))
  if (params.action) q.set('action', params.action)
  q.set('limit', String(params.limit ?? 100))
  return api.get<AuditView[]>(`/audit-logs?${q.toString()}`)
}
