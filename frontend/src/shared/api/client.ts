// 轻量 API 客户端：统一请求 /api 前缀，便于替换为 axios。
// CAP-01：注入 Authorization 头；401 时用 refresh token 静默换新并重放一次原请求，
// refresh 也失败则清空登录态跳 /login。
import {
  clearAuth,
  getAccessToken,
  getRefreshToken,
  setAuth,
} from '../../features/auth/authStore'
import type { LoginResponse } from '../../features/auth/types'

const BASE = '/api'

// 认证端点自身的 401（登录失败/refresh 失效）不触发静默刷新，直接抛给调用方
function isAuthEndpoint(path: string): boolean {
  return path.startsWith('/auth/login') || path.startsWith('/auth/refresh') || path.startsWith('/auth/logout')
}

// 并发 401 共享同一次刷新，避免 refresh token 轮换后互相作废
let refreshing: Promise<boolean> | null = null

async function tryRefresh(): Promise<boolean> {
  if (!refreshing) {
    refreshing = (async () => {
      const rt = getRefreshToken()
      if (!rt) return false
      try {
        const res = await fetch(`${BASE}/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: rt }),
        })
        if (!res.ok) return false
        setAuth((await res.json()) as LoginResponse)
        return true
      } catch {
        return false
      }
    })()
    refreshing.finally(() => {
      refreshing = null
    })
  }
  return refreshing
}

function forceReLogin() {
  clearAuth()
  if (!location.pathname.startsWith('/login')) {
    location.href = '/login'
  }
}

async function rawRequest(path: string, init?: RequestInit): Promise<Response> {
  const token = getAccessToken()
  const headers: Record<string, string> = {}
  // FormData 由浏览器自带 boundary 的 Content-Type，不能手设
  if (!(init?.body instanceof FormData)) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`
  return fetch(`${BASE}${path}`, { ...init, headers: { ...headers, ...(init?.headers as object) } })
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res = await rawRequest(path, init)
  if (res.status === 401 && !isAuthEndpoint(path)) {
    if (await tryRefresh()) {
      res = await rawRequest(path, init)
    } else {
      forceReLogin()
    }
  }
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(`${res.status} ${text || res.statusText}`)
  }
  // void 端点返回空 body，res.json() 会抛 "Unexpected end of JSON input"
  if (res.status === 204) return undefined as T
  const text = await res.text()
  if (!text) return undefined as T
  return JSON.parse(text) as T
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: JSON.stringify(body ?? {}) }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PUT', body: JSON.stringify(body ?? {}) }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  /** multipart 上传（FormData，Content-Type 由浏览器带 boundary） */
  upload: <T>(path: string, form: FormData) =>
    request<T>(path, { method: 'POST', body: form }),
  /** multipart 上传（XHR，支持上传进度回调；401 同样走静默刷新重放一次） */
  uploadWithProgress: <T>(path: string, form: FormData, onProgress?: (percent: number) => void) =>
    xhrUpload<T>(path, form, onProgress, true),
}

// fetch 不支持上传进度，进度场景走 XHR；鉴权头与 401 刷新语义与 rawRequest 对齐
function xhrUpload<T>(
  path: string,
  form: FormData,
  onProgress: ((percent: number) => void) | undefined,
  allowRetry: boolean,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${BASE}${path}`)
    const token = getAccessToken()
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress?.(Math.round((e.loaded / e.total) * 100))
    }
    xhr.onload = () => {
      if (xhr.status === 401 && allowRetry && !isAuthEndpoint(path)) {
        tryRefresh()
          .then((ok) => {
            if (ok) {
              xhrUpload<T>(path, form, onProgress, false).then(resolve, reject)
            } else {
              forceReLogin()
              reject(new Error('401 登录已过期'))
            }
          })
          .catch(reject)
        return
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(xhr.responseText ? (JSON.parse(xhr.responseText) as T) : (undefined as T))
        } catch (e) {
          reject(e)
        }
      } else {
        reject(new Error(`${xhr.status} ${xhr.responseText || xhr.statusText}`))
      }
    }
    xhr.onerror = () => reject(new Error('网络错误，上传失败'))
    xhr.send(form)
  })
}
