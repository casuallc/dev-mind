import { api } from '../../shared/api/client'
import type { AuthUser, LoginResponse } from './types'

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
