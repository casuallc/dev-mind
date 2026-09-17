// CAP-04 知识库 API（CAP-44 增库容器管理与检索）
import { api } from '../../shared/api/client'
import type {
  FeishuImportResult,
  FeishuIntegration,
  KnowledgeBase,
  KnowledgeBaseInput,
  KnowledgeEntry,
  KnowledgeEntryInput,
  KnowledgeProposal,
  KnowledgeProposalInput,
  KnowledgeSearchResult,
  PreviewResult,
} from './types'

// ---------------- 知识库（CAP-44） ----------------

export const listBases = () => api.get<KnowledgeBase[]>('/knowledge/bases')
export const getBase = (id: number) => api.get<KnowledgeBase>(`/knowledge/bases/${id}`)
export const createBase = (input: KnowledgeBaseInput) =>
  api.post<KnowledgeBase>('/knowledge/bases', input)
export const updateBase = (id: number, input: Partial<KnowledgeBaseInput>) =>
  api.put<KnowledgeBase>(`/knowledge/bases/${id}`, input)
export const deleteBase = (id: number, force = false) =>
  api.del(`/knowledge/bases/${id}${force ? '?force=true' : ''}`)
export const listBaseEntries = (id: number) =>
  api.get<KnowledgeEntry[]>(`/knowledge/bases/${id}/entries`)

export const searchChunks = (kbIds: number[], query: string, topK?: number) =>
  api.post<KnowledgeSearchResult>('/knowledge/search', { kbIds, query, topK })

// ---------------- 飞书导入（CAP-45） ----------------

export const listFeishuIntegrations = () => api.get<FeishuIntegration[]>('/knowledge/feishu/integrations')
export const importFeishuDocs = (kbId: number, integrationId: number, urls: string[]) =>
  api.post<FeishuImportResult[]>(`/knowledge/bases/${kbId}/import/feishu`, { integrationId, urls })
export const resyncEntry = (entryId: number) =>
  api.post<FeishuImportResult>(`/knowledge/entries/${entryId}/resync`)

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
export const reindexEntry = (id: number) =>
  api.post<KnowledgeEntry>(`/knowledge/entries/${id}/reindex`)

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
