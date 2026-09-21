// CAP-04 知识库类型定义，与后端 devmind-knowledge 模块对齐（CAP-44 库容器化重构）

export type EntryScope = 'global' | 'project'

/** 注入模式：FULL=全量注入 CLAUDE.local.md（经验库）；RAG=仅向量检索 */
export type InjectMode = 'FULL' | 'RAG'

export type BaseStatus = 'active' | 'archived'

export interface KnowledgeBase {
  id: number
  name: string
  description: string | null
  scope: EntryScope
  projectId: string | null
  projectName: string | null
  injectMode: InjectMode
  /** CAP-48 库级向量端点覆盖；null = 跟随平台默认端点 */
  modelEndpointId: number | null
  /** 实际生效的端点名（含回落平台默认的结果）；null = 无可用端点（索引停用、检索降级） */
  modelEndpointName: string | null
  status: BaseStatus
  entryCount: number
  chunkCount: number
  indexStats: IndexStats
  createdAt: string
  updatedAt: string
}

/** CAP-48 FR-06 索引健康度：mismatched = 已索引但血缘维度/端点与当前端点对不上（需重建） */
export interface IndexStats {
  ready: number
  pending: number
  failed: number
  disabled: number
  mismatched: number
}

/** 全库/失配重建索引结果 */
export interface ReindexResult {
  queued: number
}

export interface KnowledgeBaseInput {
  name: string
  description?: string
  scope?: EntryScope
  projectId?: string
  injectMode?: InjectMode
  /** 库级端点覆盖；传 0 或 null = 清除覆盖、跟随平台默认 */
  modelEndpointId?: number | null
  status?: BaseStatus
}

/** 向量索引状态：pending 待索引 | ready 已索引 | failed 失败 | disabled（embedding 未配置，检索降级关键词） */
export type IndexStatus = 'pending' | 'ready' | 'failed' | 'disabled'

export interface KnowledgeEntry {
  id: number
  kbId: number | null
  scope: EntryScope
  projectId: string | null
  name: string
  path: string
  contentMd: string
  tags: string[]
  sourceProject: string | null
  hitCount: number
  status: 'active' | 'deprecated'
  source: 'manual' | 'feishu'
  /** 飞书判重键 {integrationId}:{docToken}；manual 为 null */
  externalId: string | null
  indexStatus: IndexStatus
  indexError: string | null
  createdAt: string
  updatedAt: string
}

export interface KnowledgeEntryInput {
  kbId?: number
  scope?: EntryScope
  projectId?: string
  name: string
  contentMd: string
  tags?: string[]
  sourceProject?: string
  status?: 'active' | 'deprecated'
}

/** 检索命中块（POST /knowledge/search） */
export interface RetrievedChunk {
  entryId: number
  entryName: string
  kbId: number
  content: string
  score: number
}

/** 检索降级原因（CAP-48 FR-06）：NONE 正常 | NO_EMBEDDING 无可用端点走关键词 | DIMENSION_MISMATCH 维度失配需重建索引 */
export type DegradedReason = 'NONE' | 'NO_EMBEDDING' | 'DIMENSION_MISMATCH'

export interface KnowledgeSearchResult {
  /** false = 无可用 embedding 端点，本次走关键词 LIKE 降级 */
  vector: boolean
  chunks: RetrievedChunk[]
  degradedReason: DegradedReason
}

export type ProposalStatus = 'open' | 'adopted' | 'rejected'

export interface KnowledgeProposal {
  id: number
  title: string
  contentMd: string
  targetScope: EntryScope
  targetProjectId: string | null
  sourceSessionId: string | null
  status: ProposalStatus
  adoptedTo: string | null
  adoptedProjectId: string | null
  createdAt: string
  adoptedAt: string | null
}

export interface KnowledgeProposalInput {
  title: string
  contentMd: string
  targetScope: EntryScope
  targetProjectId?: string
  sourceSessionId?: string
}

export interface PreviewResult {
  content: string
  entriesUsed: KnowledgeEntry[]
}

// ---------------- CAP-45 飞书导入 ----------------

/** 可用飞书集成（GET /knowledge/feishu/integrations） */
export interface FeishuIntegration {
  id: number
  name: string
}

/** 飞书导入/重同步逐条结果：created 新建 | updated 内容变更已更新 | unchanged 无变更 | failed 失败 */
export interface FeishuImportResult {
  url: string
  status: 'created' | 'updated' | 'unchanged' | 'failed'
  entryId: number | null
  error: string | null
}
