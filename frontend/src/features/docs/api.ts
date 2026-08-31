// CAP-03 文档 API
import { api } from '../../shared/api/client'
import type {
  DiffResult,
  DocDetail,
  DocInput,
  DocMeta,
  DocTemplate,
  DocVersion,
  SaveVersionInput,
} from './types'

export const listDocs = (params: { kind?: string; projectId?: string; status?: string } = {}) => {
  const q = new URLSearchParams()
  if (params.kind) q.set('kind', params.kind)
  if (params.projectId) q.set('projectId', params.projectId)
  if (params.status) q.set('status', params.status)
  const s = q.toString()
  return api.get<DocMeta[]>(`/documents${s ? '?' + s : ''}`)
}

export const searchDocs = (q: string) =>
  api.get<DocMeta[]>(`/documents/search?q=${encodeURIComponent(q)}`)

export const listTemplates = () => api.get<DocTemplate[]>('/documents/templates')
export const createDoc = (input: DocInput) => api.post<DocDetail>('/documents', input)
export const getDoc = (id: number, version?: number) =>
  api.get<DocDetail>(`/documents/${id}${version ? `?version=${version}` : ''}`)
export const listDocVersions = (id: number) => api.get<DocVersion[]>(`/documents/${id}/versions`)
export const saveDocVersion = (id: number, input: SaveVersionInput) =>
  api.post<DocDetail>(`/documents/${id}/versions`, input)
export const docDiff = (id: number, v: number) =>
  api.get<DiffResult>(`/documents/${id}/versions/${v}/diff`)
export const revertDoc = (id: number, v: number) =>
  api.post<DocDetail>(`/documents/${id}/versions/${v}/revert`)
export const transitionDoc = (id: number, action: string) =>
  api.post<DocDetail>(`/documents/${id}/status`, { action })
export const deleteDoc = (id: number) => api.del(`/documents/${id}`)
export const pushDocs = () => api.post<{ message: string }>('/documents/push')
export const repoInfo = () => api.get<{ repoPath: string; headSha: string }>('/documents/repo')
