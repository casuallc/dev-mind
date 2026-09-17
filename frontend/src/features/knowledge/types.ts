// CAP-04 知识库类型定义，与后端 devmind-knowledge 模块对齐（CAP-44 库容器化重构）

export type EntryScope = 'global' | 'project'

/** 注入模式：FULL=全量注入 CLAUDE.md（经验库）；RAG=仅向量检索 */
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
  embeddingModel: string | null
  status: BaseStatus
  entryCount: number
  chunkCount: number
  createdAt: string
  updatedAt: string
}

export interface KnowledgeBaseInput {
  name: string
  description?: string
  scope?: EntryScope
  projectId?: string
  injectMode?: InjectMode
  embeddingModel?: string
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

export interface KnowledgeSearchResult {
  /** false = embedding 未配置，本次走关键词 LIKE 降级 */
  vector: boolean
  chunks: RetrievedChunk[]
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
