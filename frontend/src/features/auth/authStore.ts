// CAP-01 认证状态：token 对 + 当前用户，localStorage 持久化；模块级轻量 store（useSyncExternalStore 订阅）。
import type { AuthUser, LoginResponse } from './types'

const ACCESS_KEY = 'devmind.accessToken'
const REFRESH_KEY = 'devmind.refreshToken'
const USER_KEY = 'devmind.user'

let user: AuthUser | null = loadUser()
const listeners = new Set<() => void>()

function loadUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as AuthUser) : null
  } catch {
    return null
  }
}

function notify() {
  listeners.forEach((fn) => fn())
}

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_KEY)
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_KEY)
}

export function isAuthed(): boolean {
  return !!getAccessToken()
}

export function setAuth(resp: LoginResponse) {
  localStorage.setItem(ACCESS_KEY, resp.accessToken)
  localStorage.setItem(REFRESH_KEY, resp.refreshToken)
  localStorage.setItem(USER_KEY, JSON.stringify(resp.user))
  user = resp.user
  notify()
}

export function setUser(u: AuthUser) {
  localStorage.setItem(USER_KEY, JSON.stringify(u))
  user = u
  notify()
}

export function clearAuth() {
  localStorage.removeItem(ACCESS_KEY)
  localStorage.removeItem(REFRESH_KEY)
  localStorage.removeItem(USER_KEY)
  user = null
  notify()
}

export function subscribeAuth(fn: () => void): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

export function getUserSnapshot(): AuthUser | null {
  return user
}

export function isAdmin(): boolean {
  return user?.role === 'ADMIN'
}
