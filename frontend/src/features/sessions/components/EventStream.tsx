// 共享终端事件流渲染：详情页（完整）与工作台面板（compact）共用。
// 颜色约定贴近终端：assistant=蓝、user=绿、tool=紫、result=结果块、error=红。
import { useEffect, useMemo, useRef } from 'react'
import type { SessionEvent } from '../types'

function toolInputText(payload?: Record<string, unknown>): string {
  const raw = payload?.toolInput
  if (typeof raw !== 'string' || !raw) return ''
  try {
    const obj = JSON.parse(raw)
    return typeof obj === 'object' ? JSON.stringify(obj) : String(obj)
  } catch {
    return raw
  }
}

export default function EventStream({
  events,
  compact = false,
  maxHeight = 420,
  emptyText = '等待事件…',
}: {
  events: SessionEvent[]
  compact?: boolean
  maxHeight?: number
  emptyText?: string
}) {
  const ref = useRef<HTMLDivElement>(null)
  const list = useMemo(
    () => [...events].sort((a, b) => a.seq - b.seq).slice(-(compact ? 120 : 2000)),
    [events, compact],
  )

  useEffect(() => {
    const el = ref.current
    if (el) el.scrollTop = el.scrollHeight
  }, [list.length])

  return (
    <div
      ref={ref}
      style={{
        height: compact ? '100%' : maxHeight,
        overflow: 'auto',
        background: '#0d1117',
        borderRadius: 6,
        padding: compact ? 8 : 12,
        fontFamily: "'SFMono-Regular', Consolas, 'Courier New', monospace",
        fontSize: compact ? 12 : 13,
        lineHeight: compact ? 1.45 : 1.5,
      }}
    >
      {list.length === 0 && (
        <div style={{ color: '#484f58' }}>{emptyText}</div>
      )}
      {list.map((ev) => (
        <EventLine key={ev.seq} ev={ev} compact={compact} />
      ))}
    </div>
  )
}

function EventLine({ ev, compact }: { ev: SessionEvent; compact: boolean }) {
  switch (ev.type) {
    case 'assistant':
      return (
        <Line prefix="assistant" color="#58a6ff" compact={compact}>
          {ev.content}
        </Line>
      )
    case 'user':
      return (
        <Line prefix="you" color="#3fb950" compact={compact}>
          {ev.content}
        </Line>
      )
    case 'tool_use':
      return (
        <Line prefix={`⚙ ${ev.content || 'tool'}`} color="#d2a8ff" compact={compact}>
          {compact ? '' : <span style={{ color: '#8b949e', fontSize: 11 }}>{toolInputText(ev.payload)}</span>}
        </Line>
      )
    case 'tool_result':
      return (
        <Line prefix="→" color={ev.payload?.isError ? '#f85149' : '#8b949e'} compact={compact}>
          {ev.content}
        </Line>
      )
    case 'permission_result':
      return <Line prefix="auth" color="#e3b341" compact={compact}>{ev.content}</Line>
    case 'result':
      return (
        <div
          style={{
            margin: '6px 0',
            padding: compact ? '4px 8px' : '8px 10px',
            borderLeft: `3px solid ${ev.payload?.isError ? '#f85149' : '#3fb950'}`,
            background: '#161b22',
            borderRadius: 4,
          }}
        >
          <div style={{ color: ev.payload?.isError ? '#f85149' : '#3fb950', fontSize: compact ? 10 : 12 }}>
            {ev.payload?.isError ? '✗ 回合失败' : '✓ 回合完成'}
            {ev.payload?.cost ? ` · $${ev.payload.cost}` : ''}
          </div>
          <div style={{ color: '#e6edf3', whiteSpace: 'pre-wrap', marginTop: 2, fontSize: compact ? 11 : 13 }}>
            {ev.content}
          </div>
        </div>
      )
    case 'state':
      return (
        <div style={{ margin: '3px 0', color: '#484f58', fontSize: compact ? 10 : 12 }}>
          — {ev.content}
        </div>
      )
    case 'system':
      return <Line prefix="sys" color="#484f58" compact={compact}>{ev.content}</Line>
    case 'error':
      return <Line prefix="!!" color="#f85149" compact={compact}>{ev.content}</Line>
    case 'log':
      return <Line prefix="·" color="#6e7681" compact={compact}>{ev.content}</Line>
    default:
      return null
  }
}

function Line({
  prefix,
  color,
  compact,
  children,
}: {
  prefix: string
  color: string
  compact: boolean
  children: React.ReactNode
}) {
  return (
    <div style={{ margin: '2px 0' }}>
      <span style={{ color, marginRight: compact ? 4 : 6 }}>{prefix}</span>
      <span style={{ color: '#e6edf3', whiteSpace: 'pre-wrap' }}>{children}</span>
    </div>
  )
}
