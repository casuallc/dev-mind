// 会话对话交互面板：授权请求条 + ChatStream 消息流 + 底部输入区。
// 内部自管 WS 实时流（活跃态）与 REST 历史回退（终态），供 SessionsBoard 右侧与 SessionDetail 复用。
// effect 依赖只用 session.id/session.state 标量——session 对象可能来自轮询，引用每次变化。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, Card, Input, message, Space, Typography } from 'antd'
import { SendOutlined } from '@ant-design/icons'
import { authorize, sessionEvents } from '../api'
import type { SessionEvent, SessionSummary } from '../types'
import { useSessionStream } from '../hooks/useSessionStream'
import { ACTIVE_STATES } from '../stateMeta'
import ChatStream from './ChatStream'

export interface StreamMeta {
  connected: boolean
  fatal: boolean
}

export default function SessionChatPanel({
  session,
  maxHeight = '56vh',
  onChanged,
  onStreamMeta,
}: {
  session: SessionSummary
  maxHeight?: number | string
  /** 授权/发送后通知外部刷新会话摘要 */
  onChanged?: () => void
  /** 实时流连接状态回传（外层做徽标） */
  onStreamMeta?: (meta: StreamMeta) => void
}) {
  const [pendingReq, setPendingReq] = useState<SessionEvent | null>(null)
  const [inputText, setInputText] = useState('')
  const [baseEvents, setBaseEvents] = useState<SessionEvent[]>([])

  const isLive = ACTIVE_STATES.includes(session.state)
  const { events, connected, fatal, input, authorize: wsAuthorize } = useSessionStream(session.id, isLive)

  // 连接状态回传外层（徽标）
  useEffect(() => {
    onStreamMeta?.({ connected, fatal })
  }, [connected, fatal, onStreamMeta])

  // 终态会话（进程已结束/重启恢复）无 WS 运行时，退化为 REST 拉取事件历史
  useEffect(() => {
    if (isLive) return
    let cancelled = false
    sessionEvents(session.id)
      .then((evs) => {
        if (!cancelled) setBaseEvents(evs)
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [session.id, isLive])

  // 捕获最近的授权请求
  useEffect(() => {
    if (session.state !== 'WAITING_AUTH') {
      setPendingReq(null)
      return
    }
    const req = [...events].reverse().find((e) => e.type === 'permission_request')
    setPendingReq(req ?? null)
  }, [events, session.state])

  const onSend = useCallback(
    (text: string) => {
      const t = text.trim()
      if (!t) return
      input(t)
      setInputText('')
    },
    [input],
  )

  const onAuthorize = useCallback(
    (accepted: boolean, scope: string) => {
      if (!pendingReq) return
      const requestId = pendingReq.payload?.requestId as string | undefined
      authorize(session.id, accepted, scope, requestId)
        .then(() => wsAuthorize(accepted, scope, requestId))
        .then(() => {
          message.success(accepted ? `已允许（${scope}）` : '已拒绝')
          setPendingReq(null)
          onChanged?.()
        })
        .catch((e) => message.error(`授权失败：${(e as Error).message}`))
    },
    [session.id, pendingReq, wsAuthorize, onChanged],
  )

  // 合并 REST 历史 + WS 实时事件，按 seq 排序去重
  const history = useMemo(() => {
    const merged = new Map<number, SessionEvent>()
    for (const e of baseEvents) merged.set(e.seq, e)
    for (const e of events) merged.set(e.seq, e)
    return Array.from(merged.values()).sort((a, b) => a.seq - b.seq)
  }, [baseEvents, events])

  const canInput = ACTIVE_STATES.includes(session.state)

  return (
    <div>
      {/* 授权请求条 */}
      {pendingReq && (
        <Card size="small" style={{ borderColor: '#fa8c16', background: '#fff7e6', marginBottom: 8 }}>
          <Space align="start" style={{ width: '100%', justifyContent: 'space-between' }}>
            <div>
              <Typography.Text strong style={{ color: '#d46b08' }}>
                权限请求
              </Typography.Text>
              <br />
              <Typography.Text>
                工具 <Typography.Text code>{(pendingReq.payload?.toolName as string) || '?'}</Typography.Text>
              </Typography.Text>
              <Typography.Paragraph style={{ margin: '4px 0 0' }}>
                <pre style={{ whiteSpace: 'pre-wrap', margin: 0, fontSize: 12 }}>{pendingReq.content}</pre>
              </Typography.Paragraph>
            </div>
            <Space>
              <Button size="small" type="primary" onClick={() => onAuthorize(true, 'once')}>
                允许一次
              </Button>
              <Button size="small" onClick={() => onAuthorize(true, 'session')}>
                本次会话允许
              </Button>
              <Button size="small" danger onClick={() => onAuthorize(false, 'once')}>
                拒绝
              </Button>
            </Space>
          </Space>
        </Card>
      )}

      <ChatStream
        events={history}
        taskSpec={session.taskSpec}
        model={session.model}
        maxHeight={maxHeight}
        emptyText={`等待事件…（${fatal ? '会话已结束' : connected ? '连接正常' : '重连中'}）`}
      />
      <div style={{ borderTop: '1px solid #f0f0f0', marginTop: 8, paddingTop: 12 }}>
        <Space.Compact style={{ width: '100%' }}>
          <Input.TextArea
            autoSize={{ minRows: 1, maxRows: 5 }}
            disabled={!canInput}
            value={inputText}
            placeholder={
              canInput
                ? session.state === 'WAITING_INPUT'
                  ? '回复 agent 的提问，Enter 发送 / Shift+Enter 换行…'
                  : session.state === 'WAITING_AUTH'
                    ? '（正在等待授权，可在上方允许/拒绝）'
                    : '会话运行中，可注入指令，Enter 发送 / Shift+Enter 换行…'
                : '会话已结束，无法输入'
            }
            onChange={(e) => setInputText(e.target.value)}
            onPressEnter={(e) => {
              if (!e.shiftKey) {
                e.preventDefault()
                onSend(inputText)
              }
            }}
          />
          <Button
            type="primary"
            icon={<SendOutlined />}
            disabled={!canInput || !inputText.trim()}
            onClick={() => onSend(inputText)}
          >
            发送
          </Button>
        </Space.Compact>
      </div>
    </div>
  )
}
