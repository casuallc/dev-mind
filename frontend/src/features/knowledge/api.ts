// CAP-04 知识库 API（CAP-44 增库容器管理与检索）
import { api } from '../../shared/api/client'
import type {
  AdminIndexStats,
  AdminRebuildSubmit,
  ClassifyPreviewItem,
  DocType,
  DuplicateReport,
  EnrichConfig,
  EnrichEntry,
  EnrichSubmitResult,
  EvalReport,
  FeishuImportResult,
  FeishuIntegration,
  ImportBatch,
  ImportSubmitResult,
  IndexHealth,
  KnowledgeBase,
  KnowledgeBaseInput,
  KnowledgeChunk,
  KnowledgeEntry,
  KnowledgeEntryInput,
  KnowledgeEntrySummary,
  KnowledgePage,
  KnowledgeProposal,
  KnowledgeProposalInput,
  KnowledgeSearchResult,
  KnowledgeSearchV2Request,
  KnowledgeSearchV2Result,
  KnowledgeSyncSource,
  KnowledgeSyncSourceInput,
  KnowledgeVersion,
  KnowledgeVersionDetail,
  LifecycleReport,
  NegativeFeedbackReport,
  ReindexResult,
  SolutionSaveInput,
  SolutionSaveResult,
  SyncSubmitResult,
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

/** CAP-86 库内文档页：轻量摘要 + 服务端分页/过滤（万级文档不拉正文） */
export const pageBaseEntries = (
  id: number,
  params: {
    page?: number
    size?: number
    keyword?: string
    docType?: string
    source?: string
    indexStatus?: string
    status?: string
  } = {},
) => {
  const q = new URLSearchParams()
  q.set('page', String(params.page ?? 0))
  q.set('size', String(params.size ?? 20))
  if (params.keyword?.trim()) q.set('keyword', params.keyword.trim())
  if (params.docType) q.set('docType', params.docType)
  if (params.source) q.set('source', params.source)
  if (params.indexStatus) q.set('indexStatus', params.indexStatus)
  if (params.status) q.set('status', params.status)
  return api.get<KnowledgePage<KnowledgeEntrySummary>>(
    `/knowledge/bases/${id}/entries/page?${q.toString()}`,
  )
}

/** CAP-48 FR-08 全库重建索引；onlyMismatched=true 只重建血缘失配的条目（换端点后的定向修复） */
export const reindexBase = (id: number, onlyMismatched = false) =>
  api.post<ReindexResult>(
    `/knowledge/bases/${id}/reindex?onlyMismatched=${onlyMismatched ? 'true' : 'false'}`,
  )

export const searchChunks = (kbIds: number[], query: string, topK?: number) =>
  api.post<KnowledgeSearchResult>('/knowledge/search', { kbIds, query, topK })

// ---------------- CAP-84 全局检索 / 本地上传 / 索引治理 ----------------

/** 全局混合检索（BM25 + 向量 RRF，文档级聚合 + 高亮片段） */
export const searchV2 = (input: KnowledgeSearchV2Request) =>
  api.post<KnowledgeSearchV2Result>('/knowledge/search/v2', input)

/**
 * 文件/文件夹/ZIP 上传。relativePaths 必须与 files 顺序一致（文件夹选择携带
 * webkitRelativePath，ZIP 由服务端解压），受理后轮询批次看进度。
 */
export const uploadImport = (
  kbId: number,
  files: File[],
  relativePaths: string[],
  defaultDocType: DocType,
  tags: string[],
  onProgress?: (percent: number) => void,
) => {
  const form = new FormData()
  files.forEach((f) => form.append('files', f, f.name))
  relativePaths.forEach((p) => form.append('relativePaths', p))
  form.append('defaultDocType', defaultDocType)
  tags.forEach((t) => form.append('tags', t))
  return api.uploadWithProgress<ImportSubmitResult>(
    `/knowledge/bases/${kbId}/import/upload`,
    form,
    onProgress,
  )
}

export const listImports = (kbId?: number) =>
  api.get<ImportBatch[]>(`/knowledge/imports${kbId ? `?kbId=${kbId}` : ''}`)
export const getImport = (batchId: string) => api.get<ImportBatch>(`/knowledge/imports/${batchId}`)
export const retryImport = (batchId: string) =>
  api.post<ImportBatch>(`/knowledge/imports/${batchId}/retry`)
export const retryImportFile = (batchId: string, fileId: number) =>
  api.post<ImportBatch>(`/knowledge/imports/${batchId}/files/${fileId}/retry`)
/** 上传前分类预览：与入库共用目录规则，避免前端规则漂移 */
export const previewClassification = (relativePaths: string[], defaultDocType: DocType) =>
  api.post<ClassifyPreviewItem[]>('/knowledge/imports/classify-preview', {
    relativePaths,
    defaultDocType,
  })

export const indexHealth = () => api.get<IndexHealth>('/knowledge/index/health')
export const adminIndexStats = () => api.get<AdminIndexStats>('/knowledge/admin/index/stats')
export const adminRebuild = () => api.post<AdminRebuildSubmit>('/knowledge/admin/rebuild')

/** CAP-84 FR-24：把指定轮次的方案回答沉淀为 CAP-03 文档（含证据附录） */
export const saveSolution = (chatId: string, input: SolutionSaveInput = {}) =>
  api.post<SolutionSaveResult>(`/knowledge/solutions/${chatId}/save`, input)

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
/** CAP-85 FR-29：登记一次人工复核（刷新 lastReviewedAt） */
export const reviewEntry = (id: number) =>
  api.post<KnowledgeEntry>(`/knowledge/entries/${id}/review`)
export const listEntryChunks = (id: number) =>
  api.get<KnowledgeChunk[]>(`/knowledge/entries/${id}/chunks`)
export const listEntryVersions = (id: number) =>
  api.get<KnowledgeVersion[]>(`/knowledge/entries/${id}/versions`)
export const getEntryVersion = (id: number, version: number) =>
  api.get<KnowledgeVersionDetail>(`/knowledge/entries/${id}/versions/${version}`)

/** CAP-84 FR-25 负面反馈 Top 文档（ADMIN） */
export const negativeFeedbackTop = (days = 30, limit = 20) =>
  api.get<NegativeFeedbackReport>(`/chats/admin/knowledge-feedback/top?days=${days}&limit=${limit}`)

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
export const deleteProposal = (id: number) => api.del(`/knowledge/proposals/${id}`)

// ---------------- CAP-85 知识治理（ADMIN） ----------------

export const listSyncSources = (kbId?: number) =>
  api.get<KnowledgeSyncSource[]>(`/knowledge/admin/sync-sources${kbId ? `?kbId=${kbId}` : ''}`)
export const createSyncSource = (input: KnowledgeSyncSourceInput) =>
  api.post<KnowledgeSyncSource>('/knowledge/admin/sync-sources', input)
export const updateSyncSource = (id: number, input: KnowledgeSyncSourceInput) =>
  api.put<KnowledgeSyncSource>(`/knowledge/admin/sync-sources/${id}`, input)
export const deleteSyncSource = (id: number) => api.del(`/knowledge/admin/sync-sources/${id}`)
export const runSyncSource = (id: number) =>
  api.post<SyncSubmitResult>(`/knowledge/admin/sync-sources/${id}/run`)

export const getEnrichConfig = () => api.get<EnrichConfig>('/knowledge/admin/enrich/config')
export const runEnrich = (kbId?: number, limit?: number) =>
  api.post<EnrichSubmitResult>('/knowledge/admin/enrich', { kbId, limit })
export const runEntryEnrich = (id: number) =>
  api.post<EnrichSubmitResult>(`/knowledge/admin/entries/${id}/enrich`)
export const listEnrichEntries = (kbId?: number, limit = 100) => {
  const q = new URLSearchParams()
  if (kbId) q.set('kbId', String(kbId))
  q.set('limit', String(limit))
  return api.get<EnrichEntry[]>(`/knowledge/admin/enrich/entries?${q.toString()}`)
}

export const lifecycleReport = (expiringDays = 14, staleDays = 180, limit = 100) =>
  api.get<LifecycleReport>(
    `/knowledge/admin/governance/lifecycle?expiringDays=${expiringDays}&staleDays=${staleDays}&limit=${limit}`,
  )
export const duplicateReport = (threshold = 3, maxEntries = 2000) =>
  api.get<DuplicateReport>(
    `/knowledge/admin/governance/duplicates?threshold=${threshold}&maxEntries=${maxEntries}`,
  )
export const evalReport = (topK = 10, kbId?: number) =>
  api.get<EvalReport>(
    `/knowledge/admin/eval/report?topK=${topK}${kbId ? `&kbId=${kbId}` : ''}`,
  )
