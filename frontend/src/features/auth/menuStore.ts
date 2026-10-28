// CAP-61 可见菜单缓存：登录后拉一次，路由变化不重复拉；登录/退出/换账号自动失效。
import { fetchMenus } from './api'
import { getUserSnapshot, subscribeAuth } from './authStore'
import type { MenuItem } from './types'

export interface MenuSnapshot {
  items: MenuItem[]
  knownPaths: string[]
  loaded: boolean
  loading: boolean
  error: string | null
}

const EMPTY: MenuSnapshot = { items: [], knownPaths: [], loaded: false, loading: false, error: null }

let snapshot: MenuSnapshot = EMPTY
let loadedFor: string | null = null
let inFlight: Promise<void> | null = null
let seq = 0
const listeners = new Set<() => void>()

function update(patch: Partial<MenuSnapshot>) {
  snapshot = { ...snapshot, ...patch }
  listeners.forEach((fn) => fn())
}

export function subscribeMenus(fn: () => void): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

export function getMenuSnapshot(): MenuSnapshot {
  return snapshot
}

/** 拉取可见菜单（已加载且未 force 时直接返回；并发调用共享同一请求）。 */
export function loadMenus(force = false): Promise<void> {
  const username = getUserSnapshot()?.username ?? null
  if (!username) {
    if (loadedFor !== null || snapshot.items.length > 0) {
      loadedFor = null
      seq++
      update(EMPTY)
    }
    return Promise.resolve()
  }
  if (!force && loadedFor === username && snapshot.loaded) return Promise.resolve()
  if (inFlight && loadedFor === username && !force) return inFlight

  loadedFor = username
  const requestSeq = ++seq
  update({ loading: true, error: null })
  inFlight = fetchMenus()
    .then((res) => {
      if (requestSeq !== seq) return
      update({ items: res.items, knownPaths: res.knownPaths, loaded: true, loading: false })
    })
    .catch((e) => {
      if (requestSeq !== seq) return
      update({ loading: false, error: e instanceof Error ? e.message : '菜单加载失败' })
    })
    .finally(() => {
      if (requestSeq === seq) inFlight = null
    })
  return inFlight
}

// 换账号时清空旧清单（同账号刷新 token 不触发重拉）
subscribeAuth(() => {
  const username = getUserSnapshot()?.username ?? null
  if (username === loadedFor) return
  loadedFor = username
  seq++
  update(EMPTY)
  if (username) void loadMenus()
})
