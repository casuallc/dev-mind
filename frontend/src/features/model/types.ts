// CAP-48 模型接入（向量化 / 通用对话 / CAP-55 决策端点）类型

/** 端点类型：EMBEDDING（向量化）| CHAT（通用对话）| DECISION（决策引擎，laya 边车）；RERANK 服务端预留，传值 400 */
export type ModelEndpointKind = 'EMBEDDING' | 'CHAT' | 'DECISION' | 'RERANK'

/** 提供方：openai-compatible（/embeddings 或 /chat/completions）| laya（DECISION，laya 决策边车）| mock（假应答，测试用） */
export type ModelEndpointProvider = 'openai-compatible' | 'laya' | 'mock'

/** 端点视图：永不含凭据（仅 hasApiKey）；dimensions 是连接测试实测探测的产物，不是人工输入 */
export interface ModelEndpoint {
  id: number
  kind: ModelEndpointKind
  name: string
  provider: ModelEndpointProvider
  baseUrl?: string | null
  /** 向量/对话端点是模型名；DECISION 端点是 laya 的 checkpoint 别名（可空 = 由边车自动路由） */
  model?: string | null
  hasApiKey: boolean
  /** 向量维度（连接测试探测写入，null = 尚未探测；CHAT/DECISION 恒为 null——没有维度这回事） */
  dimensions?: number | null
  timeoutSeconds: number
  /** 单批条数（仅 EMBEDDING 使用；其余类型保留默认值但不使用） */
  batchSize: number
  /** 检索条数覆盖（null = 用平台默认；仅 EMBEDDING 使用） */
  topK?: number | null
  /** 余弦阈值覆盖（null = 用平台默认；仅 EMBEDDING 使用） */
  threshold?: number | null
  status: 'active' | 'disabled'
  /** 平台默认：同类型内唯一（向量/对话/决策三种默认互不影响） */
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

/** 连接测试结果：向量端点给 dimensions（本次实测维度），对话/决策端点的往返摘要在 message
 *  （决策端点 = 常驻 checkpoint 清单 + 样例题答案 + routing.reason）；
 *  dimensionChanged 仅在维度真的变了时出现，提示已有索引需重建 */
export interface EndpointTestResult {
  ok: boolean
  latencyMs: number
  model?: string | null
  dimensions?: number | null
  message: string
  dimensionChanged?: { from?: number | null; to?: number | null } | null
}
