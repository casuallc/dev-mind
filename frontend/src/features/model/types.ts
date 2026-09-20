// CAP-48 模型接入（向量化 / 通用对话端点）类型

/** 端点类型：EMBEDDING（向量化）| CHAT（通用对话，本期只登记 + 连接测试）；RERANK 服务端预留，传值 400 */
export type ModelEndpointKind = 'EMBEDDING' | 'CHAT' | 'RERANK'

/** 提供方：openai-compatible（OpenAI 兼容 /embeddings 或 /chat/completions）| mock（假向量 / 假回复，测试用） */
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
  /** 向量维度（连接测试探测写入，null = 尚未探测；CHAT 恒为 null——对话端点没有维度） */
  dimensions?: number | null
  timeoutSeconds: number
  /** 单批条数（仅 EMBEDDING 使用；CHAT 保留默认值但不使用） */
  batchSize: number
  /** 检索条数覆盖（null = 用平台默认；仅 EMBEDDING 使用） */
  topK?: number | null
  /** 余弦阈值覆盖（null = 用平台默认；仅 EMBEDDING 使用） */
  threshold?: number | null
  status: 'active' | 'disabled'
  /** 平台默认：同类型内唯一（向量默认与对话默认互不影响） */
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

/** 连接测试结果：向量端点给 dimensions（本次实测维度），对话端点的模型回复摘要走 message；
 *  dimensionChanged 仅在维度真的变了时出现，提示已有索引需重建 */
export interface EndpointTestResult {
  ok: boolean
  latencyMs: number
  model?: string | null
  dimensions?: number | null
  message: string
  dimensionChanged?: { from?: number | null; to?: number | null } | null
}
