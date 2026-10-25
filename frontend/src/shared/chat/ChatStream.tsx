// 对话式事件流渲染（由 ChatPanel 承载；CAP-30 由 sessions 上移共享）：把 ChatEvent 流转成问答气泡 + 工具调用卡片 + 回合分隔。
// system/log 等底层事件收进底部折叠「过程日志」。
import { memo, useDeferredValue, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, Button, Collapse, Image, Input, Modal, Tag, Typography, message } from 'antd'
import {
  CheckCircleFilled,
  CloseCircleFilled,
  DislikeOutlined,
  DownOutlined,
  LikeOutlined,
  Loading3QuartersOutlined,
  LockOutlined,
  RobotOutlined,
  ToolOutlined,
} from '@ant-design/icons'
import ReactMarkdown from 'react-markdown'
import type { ChatEvent, ChatImageAttachment } from './types'
import { api } from '../api/client'
import { isImageAttachment } from '../attachments/api'
import { attachmentRawUrl } from '../attachments/url'
import { fmtTime } from '../utils/format'
import { showError } from '../utils/showError'
import { isAdmin } from '../../features/auth/authStore'

// ---------------- 事件 → 渲染项 ----------------

type ChatItem =
  | { kind: 'user'; key: string; text: string; isTask?: boolean; attachments?: ChatImageAttachment[]; ts?: number }
  // streaming=true：模型执行体正在逐段吐正文（CAP-49），气泡带光标、不定稿
  | {
      kind: 'assistant'
      key: string
      text: string
      model?: string
      ts: number
      streaming?: boolean
      /** CAP-84：本轮用户事件 seq（引用校验按它归位） */
      turnSeq?: number
      /** CAP-84 FR-19/23：该轮引用快照与校验结果 */
      citations?: CitationCheck
    }
  | ToolItem
  | { kind: 'result'; key: string; isError: boolean; cost?: string; durationMs?: number }
  | { kind: 'divider'; key: string; text: string }
  | { kind: 'notice'; key: string; text: string; ts: number }
  | { kind: 'error'; key: string; text: string; ts: number }

/** CAP-84 FR-19：本轮引用快照（后端 chat_knowledge_refs 的展示形状） */
interface CitationSource {
  citationNo: number
  entryId: number
  kbId?: number
  chunkId?: number
  entryName: string
  sourceUri?: string
  snippet?: string
  score?: number
}

/** CAP-84 FR-23：citation_check 事件 payload */
interface CitationCheck {
  turnSeq: number
  citedCount: number
  invalidCount: number
  invalidRate: number
  warning: boolean
  sources: CitationSource[]
}

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

// CAP-84：用户事件里可能带检索注入块（首轮库概览 + 每轮证据），原文给模型看，气泡只显示真实提问
const INJECTED_BLOCK_RE = /^\s*<knowledge-(?:base|context)>[\s\S]*?<\/knowledge-(?:base|context)>\s*/

function cleanUserText(text: string): string {
  let s = text || ''
  while (INJECTED_BLOCK_RE.test(s)) s = s.replace(INJECTED_BLOCK_RE, '')
  return s.trim()
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

/**
 * 增量「事件 → 渲染项」构建器。长会话几千条事件，旧实现每次流式增量都全量重扫全数组、
 * 且渲染项对象全部重建（下游无从 memo，每条回答的 Markdown 每个增量帧都重解析）——
 * 首屏与流式卡顿的主因。这里尾部追加时只处理新增事件；被修改的渲染项 copy-on-write，
 * 未动的项保持对象身份，下游 memo(ChatItemView) 才能跳过重渲染。
 * 前缀变化（加载更早）或长度回退（WS 重连快照替换）时全量重建。
 * 输入约定：events 已按 seq 升序（ChatPanel 合并排序保证）。
 */
class ChatBuilder {
  private items: ChatItem[] = []
  private logs: ChatEvent[] = []
  private toolsById = new Map<string, number>() // toolUseId → items 下标
  private pendingTools: number[] = [] // 未配对的工具调用下标，旧数据无 toolUseId 时按序兜底
  // CAP-49 流式正文气泡下标：本轮 text_delta 原地累积的那一条（非流式执行体恒为 null）
  private streamIdx: number | null = null
  private seenLen = 0
  private seenLastSeq = -1
  private snapshot: { items: ChatItem[]; logs: ChatEvent[] } | null = null

  constructor(readonly taskSpec?: string) {
    if (taskSpec && taskSpec.trim()) {
      this.items.push({ kind: 'user', key: 'task', text: taskSpec, isTask: true })
    }
  }

  apply(events: ChatEvent[]): { items: ChatItem[]; logs: ChatEvent[] } {
    const appendOnly =
      this.seenLen === 0 ||
      (events.length >= this.seenLen && events[this.seenLen - 1]?.seq === this.seenLastSeq)
    if (!appendOnly) {
      this.items =
        this.taskSpec && this.taskSpec.trim()
          ? [{ kind: 'user', key: 'task', text: this.taskSpec, isTask: true }]
          : []
      this.logs = []
      this.toolsById.clear()
      this.pendingTools = []
      this.streamIdx = null
      this.seenLen = 0
      this.seenLastSeq = -1
      this.snapshot = null
    }
    if (events.length === this.seenLen && this.snapshot) return this.snapshot
    if (events.length > this.seenLen) {
      // 本批次有改动先拷壳：push/replace 都落在新数组上，不污染已下发的 snapshot 引用
      this.items = [...this.items]
      this.logs = [...this.logs]
      for (let i = this.seenLen; i < events.length; i++) this.process(events[i])
      this.seenLen = events.length
      this.seenLastSeq = events[events.length - 1].seq
    }
    this.snapshot = { items: this.items, logs: this.logs }
    return this.snapshot
  }

  /** 流式气泡定稿（新提问/回合收口/报错/非 RUNNING 状态转移都会触发，气泡不能一直转） */
  private endStream() {
    if (this.streamIdx === null) return
    const cur = this.items[this.streamIdx]
    if (cur?.kind === 'assistant' && cur.streaming) {
      this.items[this.streamIdx] = { ...cur, streaming: false }
    }
    this.streamIdx = null
  }

  private process(ev: ChatEvent) {
    const items = this.items
    switch (ev.type) {
      case 'text_delta': {
        // 模型执行体逐段吐正文；CLI 未开 partial messages ⇒ 恒无此事件（AGENT 零影响）
        const delta = ev.content ?? ''
        if (!delta) break // 空增量不动：CLI 的 stream_event 会产空 content，别拿它开一个空气泡
        const cur = this.streamIdx !== null ? items[this.streamIdx] : undefined
        if (cur?.kind === 'assistant') {
          items[this.streamIdx!] = { ...cur, text: cur.text + delta, ts: ev.timestamp }
        } else {
          this.streamIdx = items.length
          items.push({ kind: 'assistant', key: `e${ev.seq}`, text: delta, streaming: true, ts: ev.timestamp })
        }
        break
      }
      case 'user': {
        this.endStream()
        // CAP-32：附件引用在 payload.attachments（只放 id/name/contentType）
        const attachments = (ev.payload?.attachments as ChatImageAttachment[] | undefined) ?? []
        const text = cleanUserText(ev.content ?? '')
        if (text || attachments.length > 0) {
          items.push({
            kind: 'user', key: `e${ev.seq}`, text,
            attachments: attachments.length > 0 ? attachments : undefined, ts: ev.timestamp,
          })
        }
        break
      }
      case 'assistant': {
        const text = cleanAssistantText(ev.content ?? '')
        if (!text) break
        const model = (ev.payload?.model as string) || undefined
        const turnSeq = typeof ev.payload?.turnSeq === 'number' ? ev.payload.turnSeq : undefined
        const cur = this.streamIdx !== null ? items[this.streamIdx] : undefined
        if (cur?.kind === 'assistant') {
          // 全量为准：直接覆盖流式气泡的正文（增量与全量相等 / 前缀 / 网关重发不一致，三种情况
          // 都收敛到这一条路径——否则会留下两个气泡或重复尾巴）
          items[this.streamIdx!] = {
            ...cur, text, streaming: false,
            model: model ?? cur.model, ts: ev.timestamp, turnSeq: turnSeq ?? cur.turnSeq,
          }
          this.streamIdx = null
          break
        }
        const lastIdx = items.length - 1
        const last = items[lastIdx]
        if (last?.kind === 'assistant') {
          items[lastIdx] = {
            ...last, text: `${last.text}\n\n${text}`,
            model: last.model ?? model, ts: ev.timestamp, turnSeq: last.turnSeq ?? turnSeq,
          }
        } else {
          items.push({ kind: 'assistant', key: `e${ev.seq}`, text, model, ts: ev.timestamp, turnSeq })
        }
        break
      }
      case 'tool_use': {
        const idx = items.length
        items.push({
          kind: 'tool',
          key: `e${ev.seq}`,
          name: (ev.payload?.name as string) || ev.content || 'tool',
          input: (ev.payload?.toolInput as string) || undefined,
          done: false,
          ts: ev.timestamp,
        })
        const id = ev.payload?.toolUseId as string | undefined
        if (id) this.toolsById.set(id, idx)
        else this.pendingTools.push(idx)
        break
      }
      case 'tool_result': {
        const id = ev.payload?.toolUseId as string | undefined
        let idx = id !== undefined ? this.toolsById.get(id) : undefined
        if (idx === undefined && this.pendingTools.length > 0) idx = this.pendingTools.shift()
        const target = idx !== undefined ? items[idx] : undefined
        if (target?.kind === 'tool' && !target.done) {
          items[idx!] = { ...target, output: ev.content ?? '', isError: Boolean(ev.payload?.isError), done: true }
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
        this.endStream() // 回合收口：气泡定稿
        items.push({
          kind: 'result',
          key: `e${ev.seq}`,
          isError: Boolean(ev.payload?.isError),
          cost: (ev.payload?.cost as string) || undefined,
          durationMs: typeof ev.payload?.durationMs === 'number' ? (ev.payload.durationMs as number) : undefined,
        })
        break
      case 'state':
        // 非 RUNNING 的状态转移（终止/结束/回合完成）意味着这一轮不再产增量：
        // 被 kill 的会话只有 state、没有 result，不收口气泡会一直转圈
        if ((ev.payload?.state as string) !== 'RUNNING') this.endStream()
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
        this.endStream() // 报错即这一轮完了（随后一般有 result{isError}，但不指望它）
        items.push({ kind: 'error', key: `e${ev.seq}`, text: ev.content ?? 'unknown', ts: ev.timestamp })
        break
      case 'citation_check': {
        // CAP-84 FR-23：回合收口后的引用校验；正文不动，只把来源与异常提示挂到对应回答上
        const p = ev.payload ?? {}
        const rawSources = Array.isArray(p.sources) ? (p.sources as Record<string, unknown>[]) : []
        const sources: CitationSource[] = rawSources
          .filter((s) => typeof s.entryId === 'number' && typeof s.citationNo === 'number')
          .map((s) => ({
            citationNo: Number(s.citationNo),
            entryId: Number(s.entryId),
            kbId: typeof s.kbId === 'number' ? s.kbId : undefined,
            chunkId: typeof s.chunkId === 'number' ? s.chunkId : undefined,
            entryName: typeof s.entryName === 'string' ? s.entryName : `文档 #${s.entryId}`,
            sourceUri: typeof s.sourceUri === 'string' ? s.sourceUri : undefined,
            snippet: typeof s.snippet === 'string' ? s.snippet : undefined,
            score: typeof s.score === 'number' ? s.score : undefined,
          }))
        const check: CitationCheck = {
          turnSeq: typeof p.turnSeq === 'number' ? p.turnSeq : 0,
          citedCount: typeof p.citedCount === 'number' ? p.citedCount : 0,
          invalidCount: typeof p.invalidCount === 'number' ? p.invalidCount : 0,
          invalidRate: typeof p.invalidRate === 'number' ? p.invalidRate : 0,
          warning: Boolean(p.warning),
          sources,
        }
        const findAssistant = (pred: (it: Extract<ChatItem, { kind: 'assistant' }>) => boolean) => {
          for (let i = items.length - 1; i >= 0; i--) {
            const it = items[i]
            if (it.kind === 'assistant' && pred(it)) return i
          }
          return -1
        }
        let targetIdx = check.turnSeq > 0 ? findAssistant((it) => it.turnSeq === check.turnSeq) : -1
        if (targetIdx < 0) targetIdx = findAssistant(() => true)
        if (targetIdx >= 0) {
          const t = items[targetIdx]
          if (t.kind === 'assistant') items[targetIdx] = { ...t, citations: check }
        }
        break
      }
      case 'system':
      case 'log':
        this.logs.push(ev)
        break
      default:
        break
    }
  }
}

// ---------------- 组件 ----------------

export default function ChatStream({
  events,
  taskSpec,
  model,
  speaker = 'Claude',
  maxHeight = 560,
  emptyText = '等待事件…',
  feedback,
  hasOlder = false,
  loadingOlder = false,
  onLoadOlder,
}: {
  events: ChatEvent[]
  taskSpec?: string
  model?: string
  /** 回答方名称：Agent 执行体是 Claude，模型执行体是端点名（CAP-49） */
  speaker?: string
  maxHeight?: number | string | null
  emptyText?: string
  /** CAP-84 FR-25：知识会话的答案反馈入口 */
  feedback?: { apiBase: string; chatId: string }
  /** 顶部「加载更早消息」：还有更早历史（终态会话 REST 分页翻页） */
  hasOlder?: boolean
  loadingOlder?: boolean
  onLoadOlder?: () => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  const stickRef = useRef(true)
  // 增量构建器驻留 ref：taskSpec 变了（换了会话）才重建；apply 幂等，重复渲染不重复消费
  const builderRef = useRef<ChatBuilder | null>(null)
  if (!builderRef.current || builderRef.current.taskSpec !== taskSpec) {
    builderRef.current = new ChatBuilder(taskSpec)
  }
  const { items, logs } = builderRef.current.apply(events)

  // 「加载更早」发出时的 scrollHeight——prepend 后按高度差守住视口，不把用户拽动
  const anchorRef = useRef<number | null>(null)
  useEffect(() => {
    if (loadingOlder && ref.current) anchorRef.current = ref.current.scrollHeight
  }, [loadingOlder])

  // 用户贴近底部时跟随滚动；上翻阅读时不强拉
  useEffect(() => {
    const el = ref.current
    if (!el) return
    if (stickRef.current) {
      el.scrollTop = el.scrollHeight
    } else if (!loadingOlder && anchorRef.current != null) {
      el.scrollTop += el.scrollHeight - anchorRef.current
    }
    if (!loadingOlder) anchorRef.current = null
  }, [items, logs.length, loadingOlder])

  return (
    <div
      ref={ref}
      onScroll={(e) => {
        const el = e.currentTarget
        stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60
      }}
      // maxHeight=null 表示由外层 flex 容器撑满（flex:1），否则按给定最大高度滚动
      style={maxHeight === null ? { flex: 1, minHeight: 0, overflow: 'auto', padding: '4px 8px' } : { maxHeight, overflow: 'auto', padding: '4px 8px' }}
    >
      {items.length === 0 && logs.length === 0 && (
        <Typography.Text type="secondary">{emptyText}</Typography.Text>
      )}
      {hasOlder && onLoadOlder && (
        <div style={{ textAlign: 'center', padding: '2px 0 8px' }}>
          <Button size="small" loading={loadingOlder} onClick={onLoadOlder}>
            加载更早消息
          </Button>
        </div>
      )}
      {items.map((item) => (
        <ChatItemView key={item.key} item={item} sessionModel={model} speaker={speaker} feedback={feedback} />
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

// memo 生效的前提：ChatBuilder 对未修改的渲染项保持对象身份（copy-on-write），
// feedback 由 ChatPanel useMemo 钉住——流式增量帧只重渲染正文在变的那一条气泡，
// 其余几百条（含各自的 Markdown）整批跳过
const ChatItemView = memo(function ChatItemView({ item, sessionModel, speaker, feedback }: {
  item: ChatItem
  sessionModel?: string
  speaker: string
  feedback?: { apiBase: string; chatId: string }
}) {
  switch (item.kind) {
    case 'user':
      return (
        <div className="chat-row chat-row-user">
          <div className="chat-bubble-user" title={item.ts ? fmtTime(new Date(item.ts).toISOString()) : undefined}>
            {item.isTask && <Tag color="geekblue" style={{ marginBottom: 4 }}>任务</Tag>}
            {item.text && <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{item.text}</div>}
            {item.attachments && item.attachments.length > 0 && (
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: item.text ? 8 : 0 }}>
                {item.attachments.map((a) =>
                  isImageAttachment(a.contentType) ? (
                    <Image
                      key={a.attachmentId}
                      src={attachmentRawUrl(a.attachmentId)}
                      alt={a.name ?? '图片附件'}
                      width={120}
                      style={{ objectFit: 'cover', borderRadius: 6 }}
                    />
                  ) : (
                    <a key={a.attachmentId} href={attachmentRawUrl(a.attachmentId)} download={a.name ?? true}>
                      📎 {a.name ?? a.attachmentId}
                    </a>
                  ),
                )}
              </div>
            )}
          </div>
        </div>
      )
    case 'assistant': {
      const m = item.model || sessionModel
      return (
        <div className="chat-row">
          <div className="chat-msg-head">
            <RobotOutlined style={{ color: '#1677ff' }} />
            <span style={{ fontWeight: 500 }}>{speaker}</span>
            {m && <Tag style={{ marginInlineEnd: 0 }}>{m}</Tag>}
            {item.streaming && <Loading3QuartersOutlined spin style={{ color: '#1677ff', fontSize: 12 }} />}
            <span className="chat-time">{fmtTime(new Date(item.ts).toISOString())}</span>
          </div>
          <AssistantBody text={item.text} streaming={item.streaming} />
          {item.citations && <CitationBlock check={item.citations} />}
          {feedback && item.turnSeq && !item.streaming && (
            <FeedbackBar apiBase={feedback.apiBase} chatId={feedback.chatId} turnSeq={item.turnSeq} />
          )}
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
})

/**
 * 回答正文。独立组件是为了能放 hook：ChatItemView 是个大 switch，在 case 里调 hook 会违反
 * hook 规则。流式期间正文每 ~120ms 变一次，useDeferredValue 让 React 跳过中间帧——长回答
 * 反复重解析 Markdown 才是真的卡（气泡内容本身是原地累积的，不是每次重建）。
 */
function AssistantBody({ text, streaming }: { text: string; streaming?: boolean }) {
  const deferred = useDeferredValue(text)
  return (
    <div className="doc-md chat-md">
      <ReactMarkdown>{deferred}</ReactMarkdown>
      {streaming && <span className="chat-cursor" />}
    </div>
  )
}

/**
 * CAP-84 FR-25：知识会话答案反馈。同一轮重复提交覆盖评价（后端按 chat+turn+user 去重），
 * 「无用」必须填原因，管理页据此聚合负面反馈 Top 文档。
 */
function FeedbackBar({ apiBase, chatId, turnSeq }: { apiBase: string; chatId: string; turnSeq: number }) {
  const [value, setValue] = useState<boolean | null>(null)
  const [open, setOpen] = useState(false)
  const [useful, setUseful] = useState(true)
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const openWith = (u: boolean) => {
    setUseful(u)
    setReason('')
    setOpen(true)
  }

  const submit = async () => {
    if (!useful && !reason.trim()) {
      message.warning('请填写「无用」的原因')
      return
    }
    setSubmitting(true)
    try {
      await api.post(`${apiBase}/${chatId}/feedback`, {
        turnSeq,
        useful,
        reason: reason.trim() || undefined,
      })
      setValue(useful)
      setOpen(false)
      message.success('感谢反馈，已记录用于改进知识库')
    } catch (e) {
      showError(e, '反馈提交失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8 }}>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        这条回答有帮助吗？
      </Typography.Text>
      <Button
        size="small"
        type={value === true ? 'primary' : 'default'}
        icon={<LikeOutlined />}
        onClick={() => openWith(true)}
      >
        有用
      </Button>
      <Button
        size="small"
        danger={value === false}
        icon={<DislikeOutlined />}
        onClick={() => openWith(false)}
      >
        无用
      </Button>
      <Modal
        open={open}
        title={useful ? '这条回答有帮助' : '这条回答没帮助'}
        okText="提交"
        cancelText="取消"
        confirmLoading={submitting}
        onOk={submit}
        onCancel={() => setOpen(false)}
      >
        <Input.TextArea
          rows={3}
          value={reason}
          maxLength={1000}
          showCount
          placeholder={
            useful
              ? '可选：具体哪里有帮助？（如：步骤清晰、命令可直接执行）'
              : '请说明原因（如：文档过时、缺少命令、答非所问），便于补充文档或调整分块'
          }
          onChange={(e) => setReason(e.target.value)}
        />
      </Modal>
    </div>
  )
}

/**
 * CAP-84 FR-19/23：回答底部「参考来源」与引用校验异常提示。
 * 知识库只有后台一个入口（CAP-86），ADMIN 点击引用跳 /admin/knowledge?entryId=#&chunkId=# 由工作区打开抽屉并定位分块；
 * 非 ADMIN 无可达的详情页，只展示来源标记与原文路径提示，避免点进 403。
 */
function CitationBlock({ check }: { check: CitationCheck }) {
  const canOpenEntry = isAdmin()
  return (
    <div style={{ marginTop: 8 }}>
      {check.warning && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 6 }}
          message={`引用校验异常：${check.invalidCount}/${check.citedCount} 条引用不在本轮检索结果中`}
          description="回答已完整保留；引用编号不可靠时请按下方来源核对原文后再采用。"
        />
      )}
      {check.sources.length > 0 && (
        <div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            参考来源（{check.sources.length}）
          </Typography.Text>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 4 }}>
            {check.sources.map((s) => {
              const key = `${s.citationNo}-${s.entryId}-${s.chunkId ?? ''}`
              const tag = (
                <Tag
                  color="purple"
                  style={{ marginInlineEnd: 0, maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis' }}
                >
                  [S{s.citationNo}] {s.entryName}
                </Tag>
              )
              return canOpenEntry ? (
                <Link
                  key={key}
                  to={`/admin/knowledge?entryId=${s.entryId}${s.chunkId ? `&chunkId=${s.chunkId}` : ''}`}
                  title={[s.sourceUri, s.snippet].filter(Boolean).join('\n')}
                >
                  {tag}
                </Link>
              ) : (
                <span
                  key={key}
                  title={[s.sourceUri, s.snippet].filter(Boolean).join('\n')}
                  style={{ maxWidth: 320 }}
                >
                  {tag}
                </span>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
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
