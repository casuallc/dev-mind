// CAP-66 安全扫描 API 封装
import { api } from '../../shared/api/client'
import type {
  CveSuppression,
  FindingDetail,
  PageResult,
  ScanDetail,
  SecurityConfig,
  SecurityFinding,
  SecurityScan,
  Suppression,
  ToolcheckItem,
} from './types'

const SCANS = '/security/scans'

export interface CreateScanInput {
  repoId?: number
  branch?: string
  engines: string[]
  agentNodeId?: string
}

export const createScan = (projectId: string, input: CreateScanInput) =>
  api.post<SecurityScan>(`${SCANS}?projectId=${encodeURIComponent(projectId)}`, input)

export const listScans = (projectId: string, page: number, size: number) =>
  api.get<PageResult<SecurityScan>>(
    `${SCANS}?projectId=${encodeURIComponent(projectId)}&page=${page}&size=${size}`)

export const getScan = (id: number) => api.get<ScanDetail>(`${SCANS}/${id}`)

export const cancelScan = (id: number) => api.post(`${SCANS}/${id}/cancel`)

export interface FindingQuery {
  engine?: string
  status?: string // 缺省=NEW；ALL=不过滤
  severity?: string
  directDep?: boolean
  kev?: boolean
  epssMin?: number
  includeTestScope?: boolean
  kevFirst?: boolean
}

export function listFindings(scanId: number, q: FindingQuery): Promise<SecurityFinding[]> {
  const p = new URLSearchParams()
  if (q.engine) p.set('engine', q.engine)
  if (q.status) p.set('status', q.status)
  if (q.severity) p.set('severity', q.severity)
  if (q.directDep !== undefined) p.set('directDep', String(q.directDep))
  if (q.kev) p.set('kev', 'true')
  if (q.epssMin !== undefined) p.set('epssMin', String(q.epssMin))
  if (q.includeTestScope) p.set('includeTestScope', 'true')
  if (q.kevFirst) p.set('kevFirst', 'true')
  const s = p.toString()
  return api.get<SecurityFinding[]>(`${SCANS}/${scanId}/findings${s ? `?${s}` : ''}`)
}

/** 导出：落附件返回附件 id，前端拼 raw 地址下载（原始报告 ADMIN 可见由附件 PRIVATE scope 保证） */
export const exportScan = (scanId: number, format: 'csv' | 'sarif') =>
  api.get<{ attachmentId: string; fileName: string; count: number }>(
    `${SCANS}/${scanId}/export?format=${format}`)

export const getFinding = (id: number) => api.get<FindingDetail>(`/security/findings/${id}`)

export const startAiReview = (id: number) =>
  api.post<{ sessionId: string }>(`/security/findings/${id}/review`)

export const startAiFix = (id: number) =>
  api.post<{ sessionId: string }>(`/security/findings/${id}/fix`)

export interface CreateSuppressionInput {
  findingId: number
  type: string
  reason: string
  expiresAt?: string
}

export const createSuppression = (input: CreateSuppressionInput) =>
  api.post<Suppression>('/security/suppressions', input)

export const deleteSuppression = (id: number) => api.del(`/security/suppressions/${id}`)

export const listSuppressions = (projectId?: string) =>
  api.get<Suppression[]>(`/security/suppressions${projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''}`)

export interface CreateCveSuppressionInput {
  groupId: string
  artifactId: string
  cve: string
  projectId?: string
  type: string
  reason: string
  expiresAt?: string
}

export const createCveSuppression = (input: CreateCveSuppressionInput) =>
  api.post<CveSuppression>('/security/cve-suppressions', input)

export const deleteCveSuppression = (id: number) => api.del(`/security/cve-suppressions/${id}`)

export const listCveSuppressions = (projectId?: string, cve?: string) => {
  const p = new URLSearchParams()
  if (projectId) p.set('projectId', projectId)
  if (cve) p.set('cve', cve)
  const s = p.toString()
  return api.get<CveSuppression[]>(`/security/cve-suppressions${s ? `?${s}` : ''}`)
}

export const getSecurityConfig = (projectId: string) =>
  api.get<SecurityConfig>(`/security/projects/${encodeURIComponent(projectId)}/config`)

export const saveSecurityConfig = (projectId: string, internalGroupPrefixes: string) =>
  api.put<SecurityConfig>(`/security/projects/${encodeURIComponent(projectId)}/config`, {
    internalGroupPrefixes,
  })

/** 节点工具自检（ADMIN） */
export const toolcheck = (nodeId: number) =>
  api.post<ToolcheckItem[]>(`/security/nodes/${nodeId}/toolcheck`)
