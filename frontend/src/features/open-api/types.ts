// CAP-20 API 密钥类型
export interface ApiKey {
  id: number
  accessKey: string
  name: string
  enabled: boolean
  expiresAt: string | null
  lastUsedAt: string | null
  createdBy: string | null
  createdAt: string
}

/** 签发响应：secret 仅此一次可见 */
export interface IssuedKey {
  key: ApiKey
  secret: string
}

export interface IssueKeyInput {
  name: string
  /** 可空 = 永不过期；ISO 字符串 */
  expiresAt?: string | null
}
