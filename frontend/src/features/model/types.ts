// CAP-48 模型接入（Embedding 端点）类型

/** 端点类型：本期只实现 EMBEDDING（CHAT/RERANK 服务端预留，传值 400） */
export type ModelEndpointKind = 'EMBEDDING' | 'CHAT' | 'RERANK'

/** 提供方：openai-compatible（任意 OpenAI 兼容 /embeddings 服务）| mock（确定性哈希向量，测试用） */
export type ModelEndpointProvider = 'openai-compatible' | 'mock'

/** 端点视图：永不含凭据（仅 hasApiKey）；dimensions 是连接测试实测探测的产物，不是人工输入 */
export interface ModelEndpoint {
  id: number
  kind: ModelEndpointKind
  name: string
  provider: ModelEndpointProvider
  baseUrl?: string | null
  model?: string | null
  hasApiKey: boolean
  /** 向量维度（连接测试探测写入，null = 尚未探测） */
  dimensions?: number | null
  timeoutSeconds: number
  batchSize: number
  /** 检索条数覆盖（null = 用平台默认） */
  topK?: number | null
  /** 余弦阈值覆盖（null = 用平台默认） */
  threshold?: number | null
  status: 'active' | 'disabled'
  isDefault: boolean
  lastTestAt?: string | null
  /** null = 从未测试 */
  lastTestOk?: boolean | null
  lastTestMessage?: string | null
  createdAt: string
  updatedAt: string
}

/** 创建/更新请求；更新时 apiKey 留空 = 保持不变。无 dimensions 字段——维度禁人工提交 */
export interface ModelEndpointInput {
  kind?: ModelEndpointKind
  name?: string
  provider?: ModelEndpointProvider
  baseUrl?: string
  apiKey?: string
  model?: string
  timeoutSeconds?: number
  batchSize?: number
  topK?: number | null
  threshold?: number | null
  status?: 'active' | 'disabled'
}

/** 连接测试结果：dimensions 为本次实测维度，dimensionChanged 提示已有索引需重建 */
export interface EndpointTestResult {
  ok: boolean
  latencyMs: number
  model?: string | null
  dimensions?: number | null
  message: string
  dimensionChanged?: { from?: number | null; to?: number | null } | null
}
