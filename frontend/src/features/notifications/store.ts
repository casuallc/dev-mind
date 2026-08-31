// 全局通知流 store：单例 WS /ws/notifications/stream，断线指数退避重连。
// 用 useSyncExternalStore 暴露给 React（铃铛/通知中心共享同一条连接）。
import { useSyncExternalStore } from 'react'
import type { AppNotification } from './types'
import { showBrowserNotification, requestNotificationPermission } from './browserNotify'

interface StoreState {
  notifications: AppNotification[]
  connected: boolean
}

let state: StoreState = { notifications: [], connected: false }
const subscribers = new Set<() => void>()

function setState(patch: Partial<StoreState>) {
  state = { ...state, ...patch }
  subscribers.forEach((fn) => fn())
}

let ws: WebSocket | null = null
let started = false
let retry = 0
let timer: ReturnType<typeof setTimeout> | undefined

function wsUrl(): string {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${location.host}/ws/notifications/stream`
}

function connect() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
  ws = new WebSocket(wsUrl())
  ws.onopen = () => {
    retry = 0
    setState({ connected: true })
  }
  ws.onmessage = (msg) => {
    try {
      const frame = JSON.parse(msg.data)
      if (frame.type === 'snapshot') {
        setState({ notifications: frame.notifications ?? [] })
      } else if (frame.type === 'notification') {
        const n: AppNotification = frame.notification
        setState({
          notifications: [n, ...state.notifications.filter((x) => x.id !== n.id)].slice(0, 200),
        })
        showBrowserNotification(n)
      }
    } catch {
      /* 忽略坏帧 */
    }
  }
  ws.onclose = () => {
    setState({ connected: false })
    if (!started) return
    retry += 1
    timer = setTimeout(connect, Math.min(1000 * 2 ** retry, 10000))
  }
  ws.onerror = () => ws?.close()
}

export function startNotificationStream() {
  requestNotificationPermission()
  if (!started) {
    started = true
    connect()
  }
}

export function stopNotificationStream() {
  started = false
  if (timer) clearTimeout(timer)
  ws?.close()
  ws = null
}

function subscribe(fn: () => void) {
  subscribers.add(fn)
  return () => {
    subscribers.delete(fn)
  }
}

function getSnapshot(): StoreState {
  return state
}

export function useNotifications() {
  return useSyncExternalStore(subscribe, getSnapshot)
}

// ---- 本地状态操作（配合 REST 已读/动作后同步） ----

export function markReadLocal(id: number) {
  setState({
    notifications: state.notifications.map((n) =>
      n.id === id ? { ...n, readAt: new Date().toISOString() } : n,
    ),
  })
}

export function markAllReadLocal() {
  const now = new Date().toISOString()
  setState({
    notifications: state.notifications.map((n) => (n.readAt ? n : { ...n, readAt: now })),
  })
}
