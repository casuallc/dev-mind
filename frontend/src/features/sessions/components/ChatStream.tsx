// 对话式事件流渲染（会话详情页专用）：把 SessionEvent 流转成问答气泡 + 工具调用卡片 + 回合分隔。
// system/log 等底层事件收进底部折叠「过程日志」；工作台迷你终端仍用 EventStream。
import { useEffect, useMemo, useRef, useState } from 'react'
import { Collapse, Tag, Typography } from 'antd'
import {
  CheckCircleFilled,
  CloseCircleFilled,
  DownOutlined,
  Loading3QuartersOutlined,
  LockOutlined,
  RobotOutlined,
  ToolOutlined,
} from '@ant-design/icons'
import ReactMarkdown from 'react-markdown'
import type { SessionEvent } from '../types'
import { fmtTime } from '../../../shared/utils/format'

// ---------------- 事件 → 渲染项 ----------------

type ChatItem =
  | { kind: 'user'; key: string; text: string; isTask?: boolean; ts?: number }
  | { kind: 'assistant'; key: string; text: string; model?: string; ts: number }
  | ToolItem
  | { kind: 'result'; key: string; isError: boolean; cost?: string; durationMs?: number }
  | { kind: 'divider'; key: string; text: string }
  | { kind: 'notice'; key: string; text: string; ts: number }
  | { kind: 'error'; key: string; text: string; ts: number }

interface ToolItem {
  kind: 'tool'
  key: string
  name: string
  input?: string
  output?: string
  isError?: boolean
  done: boolean
  ts: number
}

// 旧格式 assistant 文本里拍平的工具标记（如 "[Bash] "），渲染前剔除
const LEGACY_TOOL_RE =
  /\[(Bash|Read|Write|Edit|MultiEdit|Glob|Grep|LS|Task|TodoWrite|WebFetch|WebSearch|NotebookEdit|BashOutput|KillBash|Agent)\]\s*/g

function cleanAssistantText(text: string): string {
  return (text || '').replace(LEGACY_TOOL_RE, '').trim()
}

function toolSummary(inputRaw?: string): string {
  if (!inputRaw) return ''
  let obj: Record<string, unknown>
  try {
    const parsed: unknown = JSON.parse(inputRaw)
    if (typeof parsed !== 'object' || parsed === null) return trunc(String(parsed))
    obj = parsed as Record<string, unknown>
  } catch {
    return trunc(inputRaw)
  }
  const pick =
    obj.command ?? obj.file_path ?? obj.pattern ?? obj.url ?? obj.query ?? obj.description ?? obj.path ?? ''
  if (typeof pick === 'string' && pick) return trunc(pick)
  return trunc(JSON.stringify(obj))
}

function trunc(s: string, max = 80): string {
  const oneLine = s.replace(/\s+/g, ' ').trim()
  return oneLine.length > max ? oneLine.slice(0, max) + '…' : oneLine
}

function fmtDur(ms?: number): string {
  if (ms == null) return ''
  if (ms < 1000) return `${ms}ms`
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  return `${Math.floor(s / 60)}m ${s % 60}s`
}

function buildChat(events: SessionEvent[], taskSpec?: string): { items: ChatItem[]; logs: SessionEvent[] } {
  const sorted = [...events].sort((a, b) => a.seq - b.seq)
  const items: ChatItem[] = []
  const logs: SessionEvent[] = []
  const toolsById = new Map<string, ToolItem>()
  const pendingTools: ToolItem[] = [] // 未配对的工具调用，旧数据无 toolUseId 时按序兜底

  if (taskSpec && taskSpec.trim()) {
    items.push({ kind: 'user', key: 'task', text: taskSpec, isTask: true })
  }

  for (const ev of sorted) {
    switch (ev.type) {
      case 'text_delta':
        break // assistant 全量消息为准
      case 'user':
        if (ev.content?.trim()) {
          items.push({ kind: 'user', key: `e${ev.seq}`, text: ev.content, ts: ev.timestamp })
        }
        break
      case 'assistant': {
        const text = cleanAssistantText(ev.content ?? '')
        if (!text) break
        const model = (ev.payload?.model as string) || undefined
        const last = items[items.length - 1]
        if (last?.kind === 'assistant') {
          last.text += '\n\n' + text
          last.model = last.model ?? model
          last.ts = ev.timestamp
        } else {
          items.push({ kind: 'assistant', key: `e${ev.seq}`, text, model, ts: ev.timestamp })
        }
        break
      }
      case 'tool_use': {
        const item: ToolItem = {
          kind: 'tool',
          key: `e${ev.seq}`,
          name: (ev.payload?.name as string) || ev.content || 'tool',
          input: (ev.payload?.toolInput as string) || undefined,
          done: false,
          ts: ev.timestamp,
        }
        items.push(item)
        const id = ev.payload?.toolUseId as string | undefined
        if (id) toolsById.set(id, item)
        else pendingTools.push(item)
        break
      }
      case 'tool_result': {
        const id = ev.payload?.toolUseId as string | undefined
        let target = id ? toolsById.get(id) : undefined
        if (!target && pendingTools.length > 0) target = pendingTools.shift()
        if (target && !target.done) {
          target.output = ev.content ?? ''
          target.isError = Boolean(ev.payload?.isError)
          target.done = true
        } else {
          // 找不到配对的调用（历史截断等），退化为独立结果块
          items.push({
            kind: 'tool', key: `e${ev.seq}`, name: '结果', output: ev.content ?? '',
            isError: Boolean(ev.payload?.isError), done: true, ts: ev.timestamp,
          })
        }
        break
      }
      case 'result':
        items.push({
          kind: 'result',
          key: `e${ev.seq}`,
          isError: Boolean(ev.payload?.isError),
          cost: (ev.payload?.cost as string) || undefined,
          durationMs: typeof ev.payload?.durationMs === 'number' ? (ev.payload.durationMs as number) : undefined,
        })
        break
      case 'state':
        if (ev.content) items.push({ kind: 'divider', key: `e${ev.seq}`, text: ev.content })
        break
      case 'permission_request':
        items.push({ kind: 'notice', key: `e${ev.seq}`, text: `请求授权：${ev.content ?? ''}`, ts: ev.timestamp })
        break
      case 'permission_result': {
        const p = ev.payload?.permission as string | undefined
        items.push({
          kind: 'notice', key: `e${ev.seq}`,
          text: p === 'allow' ? '已允许授权' : p === 'deny' ? '已拒绝授权' : (ev.content ?? ''),
          ts: ev.timestamp,
        })
        break
      }
      case 'error':
        items.push({ kind: 'error', key: `e${ev.seq}`, text: ev.content ?? 'unknown', ts: ev.timestamp })
        break
      case 'system':
      case 'log':
        logs.push(ev)
        break
      default:
        break
    }
  }
  return { items, logs }
}

// ---------------- 组件 ----------------

export default function ChatStream({
  events,
  taskSpec,
  model,
  maxHeight = 560,
  emptyText = '等待事件…',
}: {
  events: SessionEvent[]
  taskSpec?: string
  model?: string
  maxHeight?: number | string
  emptyText?: string
}) {
  const ref = useRef<HTMLDivElement>(null)
  const stickRef = useRef(true)
  const { items, logs } = useMemo(() => buildChat(events, taskSpec), [events, taskSpec])

  // 用户贴近底部时跟随滚动；上翻阅读时不强拉
  useEffect(() => {
    const el = ref.current
    if (el && stickRef.current) el.scrollTop = el.scrollHeight
  }, [items, logs.length])

  return (
    <div
      ref={ref}
      onScroll={(e) => {
        const el = e.currentTarget
        stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60
      }}
      style={{ maxHeight, overflow: 'auto', padding: '4px 8px' }}
    >
      {items.length === 0 && logs.length === 0 && (
        <Typography.Text type="secondary">{emptyText}</Typography.Text>
      )}
      {items.map((item) => (
        <ChatItemView key={item.key} item={item} sessionModel={model} />
      ))}
      {logs.length > 0 && (
        <Collapse
          ghost
          items={[
            {
              key: 'logs',
              label: <Typography.Text type="secondary">过程日志（{logs.length} 条）</Typography.Text>,
              children: (
                <div className="chat-logs">
                  {logs.map((ev) => (
                    <div key={ev.seq}>
                      <span className="chat-logs-time">{fmtTime(new Date(ev.timestamp).toISOString())}</span>{' '}
                      {ev.content}
                    </div>
                  ))}
                </div>
              ),
            },
          ]}
        />
      )}
    </div>
  )
}

function ChatItemView({ item, sessionModel }: { item: ChatItem; sessionModel?: string }) {
  switch (item.kind) {
    case 'user':
      return (
        <div className="chat-row chat-row-user">
          <div className="chat-bubble-user" title={item.ts ? fmtTime(new Date(item.ts).toISOString()) : undefined}>
            {item.isTask && <Tag color="geekblue" style={{ marginBottom: 4 }}>任务</Tag>}
            <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{item.text}</div>
          </div>
        </div>
      )
    case 'assistant': {
      const m = item.model || sessionModel
      return (
        <div className="chat-row">
          <div className="chat-msg-head">
            <RobotOutlined style={{ color: '#1677ff' }} />
            <span style={{ fontWeight: 500 }}>Claude</span>
            {m && <Tag style={{ marginInlineEnd: 0 }}>{m}</Tag>}
            <span className="chat-time">{fmtTime(new Date(item.ts).toISOString())}</span>
          </div>
          <div className="doc-md chat-md">
            <ReactMarkdown>{item.text}</ReactMarkdown>
          </div>
        </div>
      )
    }
    case 'tool':
      return <ToolCard item={item} />
    case 'result':
      return (
        <div className="chat-result" style={{ color: item.isError ? '#cf1322' : '#389e0d' }}>
          {item.isError ? <CloseCircleFilled /> : <CheckCircleFilled />}
          <span>{item.isError ? '回合失败' : '回合完成'}</span>
          {item.cost && <span>· ${item.cost}</span>}
          {item.durationMs != null && <span>· {fmtDur(item.durationMs)}</span>}
        </div>
      )
    case 'divider':
      return (
        <div className="chat-divider">
          <span>{item.text}</span>
        </div>
      )
    case 'notice':
      return (
        <div className="chat-notice">
          <LockOutlined style={{ marginRight: 6 }} />
          {item.text}
          <span className="chat-time" style={{ marginLeft: 8 }}>{fmtTime(new Date(item.ts).toISOString())}</span>
        </div>
      )
    case 'error':
      return <div className="chat-error">{item.text}</div>
    default:
      return null
  }
}

function ToolCard({ item }: { item: ToolItem }) {
  const [open, setOpen] = useState(false)
  const summary = toolSummary(item.input)
  let prettyInput = item.input ?? ''
  try {
    prettyInput = JSON.stringify(JSON.parse(prettyInput), null, 2)
  } catch {
    /* 非 JSON 原文展示 */
  }
  return (
    <div className={`chat-tool${item.isError ? ' chat-tool-err' : ''}`}>
      <div className="chat-tool-head" onClick={() => setOpen((v) => !v)}>
        <ToolOutlined style={{ color: '#722ed1' }} />
        <span style={{ fontWeight: 500 }}>{item.name}</span>
        {summary && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }} ellipsis>
            {summary}
          </Typography.Text>
        )}
        <span style={{ marginLeft: 'auto', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          {item.done ? (
            item.isError ? (
              <CloseCircleFilled style={{ color: '#cf1322' }} />
            ) : (
              <CheckCircleFilled style={{ color: '#52c41a' }} />
            )
          ) : (
            <Loading3QuartersOutlined spin style={{ color: '#1677ff' }} />
          )}
          <DownOutlined rotate={open ? 180 : 0} style={{ fontSize: 10, color: '#8c8c8c' }} />
        </span>
      </div>
      {open && (
        <div className="chat-tool-body">
          {prettyInput && (
            <>
              <div className="chat-tool-label">输入</div>
              <pre>{prettyInput}</pre>
            </>
          )}
          {item.output != null && item.output !== '' && (
            <>
              <div className="chat-tool-label">输出</div>
              <pre>{item.output}</pre>
            </>
          )}
        </div>
      )}
    </div>
  )
}
