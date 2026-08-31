// CAP-04 知识库 API
import { api } from '../../shared/api/client'
import type {
  KnowledgeEntry,
  KnowledgeEntryInput,
  KnowledgeProposal,
  KnowledgeProposalInput,
  PreviewResult,
} from './types'

// ---------------- 条目 ----------------

export const listEntries = (params: { scope?: string; projectId?: string; status?: string } = {}) => {
  const q = new URLSearchParams()
  if (params.scope) q.set('scope', params.scope)
  if (params.projectId) q.set('projectId', params.projectId)
  if (params.status) q.set('status', params.status)
  const s = q.toString()
  return api.get<KnowledgeEntry[]>(`/knowledge/entries${s ? '?' + s : ''}`)
}

export const searchEntries = (q: string, projectId?: string) => {
  const p = projectId ? `&projectId=${encodeURIComponent(projectId)}` : ''
  return api.get<KnowledgeEntry[]>(`/knowledge/entries/search?q=${encodeURIComponent(q)}${p}`)
}

export const getEntry = (id: number) => api.get<KnowledgeEntry>(`/knowledge/entries/${id}`)
export const createEntry = (input: KnowledgeEntryInput) =>
  api.post<KnowledgeEntry>('/knowledge/entries', input)
export const updateEntry = (id: number, input: Partial<KnowledgeEntryInput>) =>
  api.put<KnowledgeEntry>(`/knowledge/entries/${id}`, input)
export const deleteEntry = (id: number) => api.del(`/knowledge/entries/${id}`)

// ---------------- 注入预览 ----------------

export const previewInjection = (projectId?: string, taskSpec?: string) => {
  const q = new URLSearchParams()
  if (projectId) q.set('projectId', projectId)
  if (taskSpec) q.set('taskSpec', taskSpec)
  const s = q.toString()
  return api.get<PreviewResult>(`/knowledge/preview${s ? '?' + s : ''}`)
}

// ---------------- 提案（inbox） ----------------

export const listProposals = (status?: string) => {
  const s = status ? `?status=${status}` : ''
  return api.get<KnowledgeProposal[]>(`/knowledge/proposals${s}`)
}

export const createProposal = (input: KnowledgeProposalInput) =>
  api.post<KnowledgeProposal>('/knowledge/proposals', input)
export const adoptProposal = (id: number, target: 'project' | 'global', projectId?: string) => {
  const q = new URLSearchParams({ target })
  if (projectId) q.set('projectId', projectId)
  return api.post<KnowledgeProposal>(`/knowledge/proposals/${id}/adopt?${q.toString()}`)
}
export const rejectProposal = (id: number) => api.post<KnowledgeProposal>(`/knowledge/proposals/${id}/reject`)
