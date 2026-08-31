// 会话实时流 hook：WS /ws/sessions/{id}，连接后先收 snapshot（环形缓冲回放），
// 再收增量事件，按 seq 去重；断线指数退避重连。
// enabled=false 时不建立连接；收到 error 帧视为致命（如会话已无运行时）不再重连。
import { useEffect, useRef, useState } from 'react'
import type { SessionEvent, WsServerFrame } from '../types'

function wsUrl(id: string): string {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${location.host}/ws/sessions/${id}`
}

export interface StreamState {
  events: SessionEvent[]
  connected: boolean
  fatal: boolean
  maxSeq: number
}

export function useSessionStream(id: string | undefined, enabled = true) {
  const [state, setState] = useState<StreamState>({
    events: [],
    connected: false,
    fatal: false,
    maxSeq: 0,
  })
  const wsRef = useRef<WebSocket | null>(null)
  const enabledRef = useRef(enabled)
  enabledRef.current = enabled

  useEffect(() => {
    if (!id || !enabled) return
    let closed = false
    let fatal = false
    let ws: WebSocket | null = null
    let retry = 0
    let timer: ReturnType<typeof setTimeout> | undefined

    const append = (frame: WsServerFrame) => {
      if (frame.type === 'snapshot') {
        setState({ events: frame.events, connected: true, fatal: false, maxSeq: frame.seq })
      } else if (frame.type === 'event') {
        setState((prev) => {
          if (frame.seq <= prev.maxSeq) return prev // 去重
          return {
            events: [...prev.events, frame.event],
            connected: true,
            fatal: false,
            maxSeq: frame.seq,
          }
        })
      } else if (frame.type === 'error') {
        fatal = true
        setState((p) => ({ ...p, connected: false, fatal: true }))
        ws?.close()
      }
    }

    const connect = () => {
      if (closed || fatal) return
      ws = new WebSocket(wsUrl(id!))
      wsRef.current = ws
      ws.onopen = () => {
        retry = 0
        setState((p) => ({ ...p, connected: true, fatal: false }))
      }
      ws.onmessage = (msg) => {
        try {
          append(JSON.parse(msg.data))
        } catch {
          /* 忽略坏帧 */
        }
      }
      ws.onclose = () => {
        setState((p) => ({ ...p, connected: false }))
        if (closed || fatal) return
        retry += 1
        timer = setTimeout(connect, Math.min(1000 * 2 ** retry, 10000))
      }
      ws.onerror = () => ws?.close()
    }

    connect()
    return () => {
      closed = true
      if (timer) clearTimeout(timer)
      wsRef.current = null
      ws?.close()
      setState({ events: [], connected: false, fatal: false, maxSeq: 0 })
    }
  }, [id, enabled])

  const send = (payload: unknown) => {
    const ws = wsRef.current
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(payload))
    }
  }

  return {
    events: state.events,
    connected: state.connected,
    fatal: state.fatal,
    maxSeq: state.maxSeq,
    send,
    input: (text: string) => send({ type: 'input', text }),
    authorize: (accepted: boolean, scope: string, requestId?: string) =>
      send({ type: 'authorize', accepted, scope, requestId }),
    refresh: () => send({ type: 'ping' }),
  }
}
