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

// ---------------- CAP-84 知识管理 v2 ----------------

/** 文档类型：技术文档 / 维护运维 / 常见问题 / 项目开发产物 */
export type DocType = 'TECH' | 'MAINT' | 'FAQ' | 'ARTIFACT'

export const DOC_TYPE_LABEL: Record<DocType, string> = {
  TECH: '技术文档',
  MAINT: '维护文档',
  FAQ: '常见问题',
  ARTIFACT: '开发产物',
}

export const DOC_TYPE_OPTIONS = (Object.keys(DOC_TYPE_LABEL) as DocType[]).map((value) => ({
  value,
  label: `${DOC_TYPE_LABEL[value]}（${value}）`,
}))

/** 全局检索请求（POST /knowledge/search/v2）；kbIds 空 = 全部启用中的库 */
export interface KnowledgeSearchV2Request {
  query: string
  kbIds?: number[]
  docTypes?: DocType[]
  projectId?: string
  tags?: string[]
  topK?: number
}

/** 高亮片段（FR-12：一篇文档最多 3 段，命中词前后 120 字） */
export interface KnowledgeSnippet {
  text: string
  chunkId: number
  headingPath: string | null
  startOffset: number
  endOffset: number
}

/** 证据块明细（FR-12：随文档命中返回，供「展开证据」定位原文） */
export interface KnowledgeChunkRef {
  chunkId: number
  chunkIndex: number
  headingPath: string | null
  startOffset: number
  endOffset: number
  snippet: string
  score: number
}

/** 文档级命中：snippet 已转义并带 <mark> 高亮标记（前端按文本切分渲染，不注入 HTML） */
export interface KnowledgeSearchV2Hit {
  entryId: number
  kbId: number
  kbName: string
  title: string
  docType: DocType | null
  path: string | null
  sourceUri: string | null
  summary: string | null
  tags: string[]
  snippet: string
  chunkId: number
  headingPath: string | null
  startOffset: number
  endOffset: number
  score: number
  chunkCount: number
  updatedAt: string
  snippets: KnowledgeSnippet[]
  chunks: KnowledgeChunkRef[]
}

export interface KnowledgeSearchV2Result {
  hits: KnowledgeSearchV2Hit[]
  total: number
  vector: boolean
  /** NONE | NO_EMBEDDING | DIMENSION_MISMATCH | INDEX_EMPTY */
  degradedReason: string
}

/** 上传批次（GET /knowledge/imports） */
export type ImportBatchStatus = 'RUNNING' | 'DONE' | 'PARTIAL' | 'FAILED' | 'CANCELLED'
export type ImportFileStatus = 'pending' | 'parsing' | 'indexed' | 'skipped' | 'failed'

export interface ImportBatchFile {
  id: number
  relativePath: string
  fileName: string
  mimeType: string | null
  sizeBytes: number
  status: ImportFileStatus
  error: string | null
  entryId: number | null
}

export interface ImportBatch {
  id: string
  kbId: number
  taskId: string | null
  name: string
  defaultDocType: DocType
  tags: string | null
  status: ImportBatchStatus
  totalFiles: number
  successCount: number
  failedCount: number
  skippedCount: number
  error: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
  files: ImportBatchFile[]
}

/** 上传受理结果：立即返回，进度靠轮询批次 */
export interface ImportSubmitResult {
  batchId: string
  taskId: string | null
  totalFiles: number
}

/** 索引统计（GET /knowledge/admin/index/stats） */
export interface AdminIndexStats {
  entries: number
  chunks: number
  indexedDocs: number
  indexSizeBytes: number
  schemaVersion: string | null
  vectorDimensions: number[]
}

export interface IndexHealth {
  dbEntries: number
  indexedDocs: number
  vectorDimensions: number[]
}

export interface AdminRebuildSubmit {
  taskId: string
  entries: number
}

/** CAP-84 FR-24 一键沉淀方案为文档 */
export interface SolutionSaveInput {
  /** 指定轮次（不传 = 最近一轮回答） */
  turnSeq?: number
  title?: string
  kind?: 'design' | 'report'
  projectId?: string
  requirementId?: string
  tags?: string[]
}

export interface SolutionSaveResult {
  documentId: number
  versionNo: number
  title: string
  kind: string
}

/** CAP-84 FR-15 文档当前索引分块 */
export interface KnowledgeChunk {
  id: number
  chunkIndex: number
  headingPath: string | null
  startOffset: number
  endOffset: number
  tokenCount: number
  content: string
}

/** CAP-84 FR-06 文档版本（列表不含正文） */
export interface KnowledgeVersion {
  id: number
  entryId: number
  contentVersion: number
  contentHash: string | null
  sourceUri: string | null
  changeNote: string | null
  createdBy: string | null
  createdAt: string
}

export interface KnowledgeVersionDetail extends KnowledgeVersion {
  contentMd: string
}

/** CAP-84 FR-15 上传前分类预览 */
export interface ClassifyPreviewItem {
  relativePath: string
  docType: DocType
  inferred: boolean
  tags: string[]
}

/** CAP-84 FR-25 消息级答案反馈 */
export interface KnowledgeFeedback {
  id: number
  chatId: string
  turnSeq: number
  useful: boolean
  reason: string | null
  mode: string | null
  refs: KnowledgeFeedbackRef[]
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface KnowledgeFeedbackRef {
  entryId: number | null
  kbId: number | null
  chunkId: number | null
  citationNo: number | null
  entryName: string | null
}

/** CAP-84 FR-25 负面反馈 Top 文档 */
export interface NegativeFeedbackReport {
  since: string
  feedbackTotal: number
  negativeTotal: number
  docs: NegativeFeedbackDoc[]
}

export interface NegativeFeedbackDoc {
  entryId: number
  entryName: string | null
  kbId: number | null
  negativeCount: number
  totalCount: number
  negativeRate: number
  reasons: { reason: string; count: number }[]
  lastNegativeAt: string | null
}

// ---------------- CAP-85 知识治理（ADMIN） ----------------

export type SyncSourceKind = 'GIT' | 'FEISHU'
export type SyncSourceStatus = 'RUNNING' | 'OK' | 'FAILED'

export interface SyncStats {
  created: number
  updated: number
  unchanged: number
  deprecated: number
  failed: number
}

/** 外部同步源（GET /knowledge/admin/sync-sources）；config 只含非敏感配置 */
export interface KnowledgeSyncSource {
  id: number
  kbId: number
  kind: SyncSourceKind
  name: string
  config: Record<string, unknown>
  enabled: boolean
  watermark: string | null
  lastSyncAt: string | null
  lastStatus: SyncSourceStatus | null
  lastError: string | null
  lastStats: SyncStats
  createdBy: string | null
  createdAt: string
  updatedAt: string
}

export interface KnowledgeSyncSourceInput {
  kbId: number
  kind: SyncSourceKind
  name?: string
  /** GIT: repoPath/ref/docsPath/docType/tags；FEISHU: integrationId/target/docType/tags */
  config: Record<string, unknown>
  enabled?: boolean
}

export interface SyncSubmitResult {
  taskId: string
  sourceId: number
}

export interface EnrichConfig {
  enabled: boolean
  endpointId: number | null
  maxInputChars: number
  batchLimit: number
  timeoutSeconds: number
}

export interface EnrichSubmitResult {
  taskId: string
  kbId: number | null
  entryId: number | null
  limit: number
}

/** 治理页增强状态行（GET /knowledge/admin/enrich/entries，不含正文） */
export interface EnrichEntry {
  id: number
  kbId: number | null
  name: string
  docType: DocType | null
  autoEnrich: boolean
  enrichedAt: string | null
  enrichError: string | null
  updatedAt: string
}

export interface LifecycleItem {
  id: number
  kbId: number | null
  name: string
  docType: DocType | null
  owner: string | null
  validTo: string | null
  lastReviewedAt: string | null
  updatedAt: string
}

/** 生命周期四组互相独立，list 已按 limit 截断，count 为全量计数 */
export interface LifecycleReport {
  generatedAt: string
  expiringDays: number
  staleDays: number
  limit: number
  expiredCount: number
  expiringCount: number
  staleCount: number
  missingOwnerCount: number
  expired: LifecycleItem[]
  expiring: LifecycleItem[]
  stale: LifecycleItem[]
  missingOwner: LifecycleItem[]
}

export interface DuplicateItem {
  id: number
  kbId: number | null
  name: string
  docType: DocType | null
  updatedAt: string
  simHash: string
}

export interface DuplicateGroup {
  entries: DuplicateItem[]
  maxDistance: number
}

export interface DuplicateReport {
  generatedAt: string
  threshold: number
  scanned: number
  remainingWithoutHash: number
  groups: DuplicateGroup[]
}

export interface EvalHitRef {
  rank: number
  entryId: number
  title: string
  docType: DocType | null
  relevant: boolean
}

export interface EvalCaseResult {
  id: string
  question: string
  answerable: boolean
  hit: boolean
  firstRelevantRank: number
  reciprocalRank: number
  citationPrecision: number
  refused: boolean
  hits: EvalHitRef[]
  expectedEntryNames: string[]
  expectedChunkContains: string[]
}

export interface EvalReport {
  dataset: string
  datasetPath: string
  generatedAt: string
  topK: number
  kbId: number | null
  caseCount: number
  answerableCount: number
  unanswerableCount: number
  recallAtK: number
  mrr: number
  citationPrecision: number
  refusalRate: number
  degradedReason: string
  cases: EvalCaseResult[]
}

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
  source: 'manual' | 'feishu' | 'file' | 'git' | 'api'
  /** 飞书判重键 {integrationId}:{docToken}；manual 为 null */
  externalId: string | null
  indexStatus: IndexStatus
  indexError: string | null
  /** CAP-84：文档类型 / 原始路径 / MIME / 摘要 / 负责人 / 内容版本 / 有效期与复核（历史行可能为空） */
  docType?: DocType | null
  sourceUri?: string | null
  mimeType?: string | null
  summary?: string | null
  owner?: string | null
  contentVersion?: number
  validFrom?: string | null
  validTo?: string | null
  lastReviewedAt?: string | null
  /** CAP-85：模型自动补全开关（人工改过分类/摘要/标签后置 false）与最近补全结果 */
  autoEnrich: boolean
  enrichedAt: string | null
  enrichError: string | null
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
  /** 手工设置分类/摘要/标签会关闭该条目的自动补全（人工修正优先） */
  docType?: DocType
  summary?: string
  owner?: string
  sourceUri?: string
  validFrom?: string
  validTo?: string
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
  /** CAP-55 FR-04 AI 分诊建议（null = 从未分诊，不渲染徽标区块） */
  triage: TriageView | null
}

// ---------------- CAP-55 提案分诊（FR-04，inbox 徽标 + 「查看依据」抽屉） ----------------

/**
 * 分诊建议：后端已是**展示形状**（中文短标签、重复布尔判定都算好了），
 * 前端不解析 laya 的 choice/score/noul，只在抽屉里把 answers 原文摊出来给人看。
 */
export interface TriageView {
  /** 最近一次分诊时间 */
  at: string
  /** true = 没拿到建议（边车没配/连不上/应答不可解析），三块建议均为 null */
  degraded: boolean
  degradedReason: string
  /** 边车实际选中的 checkpoint */
  model: string | null
  routingReason: string
  latencyMs: number
  adoptLayer: TriageChoice | null
  duplicate: TriageDuplicate | null
  quality: TriageScore | null
  /** laya 应答原文（题 id → 答案），「查看依据」直接展示 */
  answers: Record<string, unknown> | null
}

export interface TriageChoice {
  /** 机器值 global|project|discard（与采纳 API 的 target 同域） */
  value: string
  label: string
  confidence: number | null
  probabilities: Record<string, number> | null
}

export interface TriageDuplicate {
  duplicate: boolean
  probability: number | null
  confidence: number | null
  similar: TriageSimilarEntry[]
  /** 召回降级/失败的说明（有值时这个结论要打问号） */
  note: string
}

export interface TriageSimilarEntry {
  entryId: number | null
  entryName: string
  score: number
}

export interface TriageScore {
  /** 等级下标（0 起） */
  level: number | null
  label: string
  confidence: number | null
  probabilities: Record<string, number> | null
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
