// CAP-20 API 密钥管理接口（/api/open-keys，仅 ADMIN）
import { api } from '../../shared/api/client'
import type { ApiKey, IssueKeyInput, IssuedKey } from './types'

export function listApiKeys(): Promise<ApiKey[]> {
  return api.get<ApiKey[]>('/open-keys')
}

export function issueApiKey(input: IssueKeyInput): Promise<IssuedKey> {
  return api.post<IssuedKey>('/open-keys', input)
}

export function setApiKeyEnabled(id: number, enabled: boolean): Promise<ApiKey> {
  return api.put<ApiKey>(`/open-keys/${id}`, { enabled })
}

export function deleteApiKey(id: number): Promise<void> {
  return api.del(`/open-keys/${id}`)
}
