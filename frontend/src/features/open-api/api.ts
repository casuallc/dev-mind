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

/** CAP-20 AI 智能接入：描述项目情况，平台起全自动会话把配置写入。返回会话 ID（跳转实时观看）。 */
export function onboardProject(description: string): Promise<{ sessionId: string }> {
  return api.post<{ sessionId: string }>('/projects/onboard', { description })
}
