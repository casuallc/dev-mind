// 工作台中的单个 Agent 面板：各自连 WS，迷你终端 + 快速输入 + 操作（类 PowerShell 子窗口）。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Badge, Button, Card, Input, message, Space, Tag, Tooltip, Typography } from 'antd'
import {
  CaretRightOutlined,
  ExportOutlined,
  PauseOutlined,
  SendOutlined,
  StopOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { authorize, finishSession, suspendSession, resumeSession } from '../api'
import type { SessionEvent, SessionSummary } from '../types'
import { useSessionStream } from '../hooks/useSessionStream'
import EventStream from './EventStream'

const stateColor: Record<string, string> = {
  RUNNING: 'processing',
  WAITING_INPUT: 'gold',
  WAITING_AUTH: 'orange',
  DONE: 'success',
  FAILED: 'error',
  SUSPENDED: 'default',
  TERMINATED: 'default',
}
const ACTIVE = ['RUNNING', 'WAITING_INPUT', 'WAITING_AUTH']

export default function AgentPanel({
  session,
  onStateChange,
}: {
  session: SessionSummary
  onStateChange?: (id: string, state: string) => void
}) {
  const navigate = useNavigate()
  const isLive = ACTIVE.includes(session.state)
  const { events, connected, input, authorize: wsAuthorize } = useSessionStream(session.id, isLive)
  const [inputText, setInputText] = useState('')
  const [pendingReq, setPendingReq] = useState<SessionEvent | null>(null)

  // 捕获最近的授权请求（WAITING_AUTH 时显示条）
  useEffect(() => {
    if (session.state !== 'WAITING_AUTH') {
      setPendingReq(null)
      return
    }
    const req = [...events].reverse().find((e) => e.type === 'permission_request')
    setPendingReq(req ?? null)
  }, [events, session.state])

  // 摘要取最新 result 事件（WS 实时优于父组件轮询）
  const latestResult = useMemo(
    () => [...events].reverse().find((e) => e.type === 'result')?.content ?? session.summary,
    [events, session.summary],
  )

  const onSend = useCallback(() => {
    const t = inputText.trim()
    if (!t) return
    input(t)
    setInputText('')
  }, [inputText, input])

  const onAuthorize = useCallback(
    (accepted: boolean, scope: string) => {
      const requestId = pendingReq?.payload?.requestId as string | undefined
      authorize(session.id, accepted, scope, requestId)
        .then(() => wsAuthorize(accepted, scope, requestId))
        .then(() => {
          message.success(accepted ? `已允许（${scope}）` : '已拒绝')
          setPendingReq(null)
        })
        .catch((e) => message.error(`授权失败：${(e as Error).message}`))
    },
    [session.id, pendingReq, wsAuthorize],
  )

  const doAction = useCallback(
    async (fn: () => Promise<unknown>, ok: string) => {
      try {
        await fn()
        message.success(ok)
        onStateChange?.(session.id, 'reload')
      } catch (e) {
        message.error(`操作失败：${(e as Error).message}`)
      }
    },
    [session.id, onStateChange],
  )

  const canInput = isLive && session.state !== 'WAITING_AUTH'

  return (
    <Card
      size="small"
      style={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        borderColor: session.state === 'WAITING_AUTH' ? '#fa8c16' : undefined,
      }}
      styles={{
        body: {
          padding: 10,
          display: 'flex',
          flexDirection: 'column',
          flex: 1,
          minHeight: 0,
          overflow: 'hidden',
        },
      }}
      title={
        <Space size={6} wrap>
          <Typography.Text code style={{ fontSize: 12 }}>{session.id}</Typography.Text>
          <Tag color={stateColor[session.state] ?? 'default'} style={{ marginInlineEnd: 0 }}>
            {session.state}
          </Tag>
          <Badge status={connected ? 'success' : session.state === 'DONE' ? 'default' : 'processing'} />
        </Space>
      }
      extra={
        <Button
          type="text"
          size="small"
          icon={<ExportOutlined />}
          onClick={() => navigate(`/sessions/${session.id}`)}
        >
          详情
        </Button>
      }
    >
      {/* 授权请求条 */}
      {pendingReq && (
        <div
          style={{
            background: '#fff7e6',
            border: '1px solid #fa8c16',
            borderRadius: 6,
            padding: '6px 8px',
            marginBottom: 8,
          }}
        >
          <Typography.Text strong style={{ color: '#d46b08', fontSize: 12 }}>
            权限请求
          </Typography.Text>
          <Typography.Paragraph style={{ margin: '4px 0', fontSize: 12, whiteSpace: 'pre-wrap' }}>
            {(pendingReq.payload?.toolName as string) || ''}: {pendingReq.content}
          </Typography.Paragraph>
          <Space size={4}>
            <Button size="small" type="primary" onClick={() => onAuthorize(true, 'once')}>允许</Button>
            <Button size="small" onClick={() => onAuthorize(true, 'session')}>本次会话</Button>
            <Button size="small" danger onClick={() => onAuthorize(false, 'once')}>拒绝</Button>
          </Space>
        </div>
      )}

      {/* 迷你终端 */}
      <div style={{ flex: 1, minHeight: 240, marginBottom: 8 }}>
        <EventStream
          events={events}
          compact
          emptyText={connected ? '等待事件…' : isLive ? '连接中…' : '会话已结束'}
        />
      </div>

      {/* 摘要 */}
      {latestResult && (
        <Typography.Text
          type="secondary"
          style={{ fontSize: 11, marginBottom: 8, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}
        >
          {latestResult}
        </Typography.Text>
      )}

      {/* 输入 + 操作 */}
      <div style={{ display: 'flex', gap: 4 }}>
        <Input
          size="small"
          value={inputText}
          disabled={!canInput}
          placeholder={
            !isLive
              ? '已结束'
              : session.state === 'WAITING_INPUT'
                ? '回复…(回车)'
                : '注入指令…'
          }
          onChange={(e) => setInputText(e.target.value)}
          onPressEnter={onSend}
        />
        <Tooltip title="发送">
          <Button size="small" type="primary" icon={<SendOutlined />} disabled={!canInput || !inputText.trim()} onClick={onSend} />
        </Tooltip>
        {session.state === 'SUSPENDED' ? (
          <Tooltip title="恢复">
            <Button size="small" icon={<CaretRightOutlined />} onClick={() => doAction(() => resumeSession(session.id), '已恢复')} />
          </Tooltip>
        ) : isLive ? (
          <>
            <Tooltip title="优雅结束（关 stdin，claude 自然退出）">
              <Button size="small" icon={<StopOutlined />} onClick={() => doAction(() => finishSession(session.id), '已结束')} />
            </Tooltip>
            <Tooltip title="挂起（杀进程，可恢复）">
              <Button size="small" icon={<PauseOutlined />} onClick={() => doAction(() => suspendSession(session.id), '已挂起')} />
            </Tooltip>
          </>
        ) : null}
      </div>
    </Card>
  )
}
