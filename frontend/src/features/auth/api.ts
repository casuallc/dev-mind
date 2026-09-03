import { api } from '../../shared/api/client'
import type { AuthUser, LoginResponse } from './types'
import type { GitCredential } from './types'

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

// ---- 我的 Git 凭证（CAP-24） ----

export interface GitCredentialRequest {
  label: string
  baseUrl: string
  /** 更新时留空 = 不修改 */
  secret?: string
  gitAuthorName: string
  gitAuthorEmail: string
}

export function listGitCredentials() {
  return api.get<GitCredential[]>('/me/git-credentials')
}

export function createGitCredential(req: GitCredentialRequest) {
  return api.post<GitCredential>('/me/git-credentials', req)
}

export function updateGitCredential(id: number, req: GitCredentialRequest) {
  return api.put<GitCredential>(`/me/git-credentials/${id}`, req)
}

export function deleteGitCredential(id: number) {
  return api.del<void>(`/me/git-credentials/${id}`)
}

export function testGitCredential(id: number, remoteUrl: string) {
  return api.post<{ ok: boolean; message: string }>(`/me/git-credentials/${id}/test`, { remoteUrl })
}
