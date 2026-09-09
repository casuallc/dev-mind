import { api } from '../../shared/api/client'
import type { AuthUser, LoginResponse } from './types'
import type { PlatformAccount } from './types'

export function login(username: string, password: string) {
  return api.post<LoginResponse>('/auth/login', { username, password })
}

export function logout(refreshToken: string | null) {
  return api.post<void>('/auth/logout', { refreshToken })
}

export function fetchMe() {
  return api.get<AuthUser>('/auth/me')
}

export function changePassword(oldPassword: string, newPassword: string) {
  return api.post<void>('/auth/change-password', { oldPassword, newPassword })
}

// ---- 用户管理（ADMIN） ----

export function listUsers() {
  return api.get<AuthUser[]>('/auth/users')
}

export function createUser(req: { username: string; displayName?: string; password: string; role: string }) {
  return api.post<AuthUser>('/auth/users', req)
}

export function updateUser(id: string, req: { displayName?: string; role?: string; status?: string }) {
  return api.put<AuthUser>(`/auth/users/${id}`, req)
}

export function resetPassword(id: string, password: string) {
  return api.post<void>(`/auth/users/${id}/reset-password`, { password })
}

// ---- 我的第三方账号（CAP-35） ----

export interface PlatformAccountUpsertRequest {
  /** PAT / BASIC（仅 Jira 可选 BASIC；git 平台固定 PAT） */
  authType?: string
  /** 仅 Jira BASIC */
  username?: string
  /** 更新时留空 = 不修改 */
  secret?: string
  /** git 平台必填 */
  gitAuthorName?: string
  gitAuthorEmail?: string
}

export interface PlatformAccountTestResult {
  ok: boolean
  message: string
  detail?: string | null
}

export function listPlatformAccounts() {
  return api.get<PlatformAccount[]>('/me/platform-accounts')
}

export function upsertPlatformAccount(integrationId: number, req: PlatformAccountUpsertRequest) {
  return api.put<PlatformAccount>(`/me/platform-accounts/${integrationId}`, req)
}

export function unbindPlatformAccount(integrationId: number) {
  return api.del<void>(`/me/platform-accounts/${integrationId}`)
}

export function testPlatformAccount(integrationId: number) {
  return api.post<PlatformAccountTestResult>(`/me/platform-accounts/${integrationId}/test`, {})
}
