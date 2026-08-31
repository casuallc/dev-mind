// 会话详情：头部信息 + 授权请求条 + 共享终端流 + 输入框 + 居中确认弹窗。
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Badge,
  Button,
  Card,
  Descriptions,
  Empty,
  Input,
  message,
  Modal,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd'
import {
  BulbOutlined,
  CaretRightOutlined,
  DeleteOutlined,
  DiffOutlined,
  PauseOutlined,
  PoweroffOutlined,
  SendOutlined,
  StopOutlined,
} from '@ant-design/icons'
import { useParams } from 'react-router-dom'
import {
  authorize,
  finishSession,
  getSession,
  killSession,
  removeWorktree,
  resumeSession,
  sessionDiff,
  sessionEvents,
  suspendSession,
} from '../api'
import type { DiffView, SessionEvent, SessionSummary } from '../types'
import { useSessionStream } from '../hooks/useSessionStream'
import EventStream from '../components/EventStream'
import SedimentExperienceModal from '../../knowledge/components/SedimentExperienceModal'

const stateColor: Record<string, string> = {
  RUNNING: 'processing',
  WAITING_INPUT: 'gold',
  WAITING_AUTH: 'orange',
  DONE: 'success',
  FAILED: 'error',
  SUSPENDED: 'default',
  TERMINATED: 'default',
}

const ACTIVE_STATES = ['RUNNING', 'WAITING_INPUT', 'WAITING_AUTH']

export default function SessionDetail() {
  const { id } = useParams<{ id: string }>()
  const [session, setSession] = useState<SessionSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [pendingReq, setPendingReq] = useState<SessionEvent | null>(null)
  const [inputText, setInputText] = useState('')
  const [diff, setDiff] = useState<DiffView | null>(null)
  const [diffLoading, setDiffLoading] = useState(false)
  const [diffOpen, setDiffOpen] = useState(false)
  const [baseEvents, setBaseEvents] = useState<SessionEvent[]>([])
  const [sedimentOpen, setSedimentOpen] = useState(false)

  const isLive = !!session && ACTIVE_STATES.includes(session.state)
  const { events, connected, fatal, input, authorize: wsAuthorize } = useSessionStream(id, isLive)

  const loadSession = useCallback(async () => {
    if (!id) return
    try {
      setSession(await getSession(id))
    } catch (e) {
      message.error(`加载会话失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => {
    setLoading(true)
    loadSession()
  }, [loadSession])

  // 终态会话（进程已结束/重启恢复）无 WS 运行时，退化为 REST 拉取事件历史
  useEffect(() => {
    if (!id || isLive) return
    let cancelled = false
    sessionEvents(id)
      .then((evs) => {
        if (!cancelled) setBaseEvents(evs)
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [id, isLive])

  // 捕获最近的授权请求
  useEffect(() => {
    if (session?.state !== 'WAITING_AUTH') {
      setPendingReq(null)
      return
    }
    const req = [...events].reverse().find((e) => e.type === 'permission_request')
    setPendingReq(req ?? null)
  }, [events, session?.state])

  const onSend = useCallback(
    (text: string) => {
      const t = text.trim()
      if (!t || !id) return
      input(t)
      setInputText('')
    },
    [id, input],
  )

  const onAuthorize = useCallback(
    (accepted: boolean, scope: string) => {
      if (!id || !pendingReq) return
      const requestId = pendingReq.payload?.requestId as string | undefined
      authorize(id, accepted, scope, requestId)
        .then(() => wsAuthorize(accepted, scope, requestId))
        .then(() => {
          message.success(accepted ? `已允许（${scope}）` : '已拒绝')
          setPendingReq(null)
        })
        .catch((e) => message.error(`授权失败：${(e as Error).message}`))
    },
    [id, pendingReq, wsAuthorize],
  )

  const doAction = useCallback(
    async (fn: () => Promise<SessionSummary>, successMsg: string) => {
      if (!id) return
      try {
        setSession(await fn())
        message.success(successMsg)
      } catch (e) {
        message.error(`操作失败：${(e as Error).message}`)
      }
    },
    [id],
  )

  // 结束会话：关 stdin，claude 自然退出 → DONE/FAILED
  const onFinish = useCallback(async () => {
    if (!id) return
    try {
      await finishSession(id)
      message.success('已结束会话，等待 claude 退出…')
      window.setTimeout(() => loadSession(), 800)
    } catch (e) {
      message.error(`结束失败：${(e as Error).message}`)
    }
  }, [id, loadSession])

  const onRemoveWorktree = useCallback(() => {
    if (!id) return
    Modal.confirm({
      centered: true,
      title: '删除 worktree？',
      content: '删除会话对应的 git worktree（不影响会话记录与事件历史）。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await removeWorktree(id)
          message.success('已删除 worktree')
          setSession(await getSession(id))
        } catch (e) {
          message.error(`删除失败：${(e as Error).message}`)
        }
      },
    })
  }, [id])

  const onKill = useCallback(() => {
    if (!id) return
    Modal.confirm({
      centered: true,
      title: '终止该会话？',
      content: '将强制杀掉 claude 进程并标记为 TERMINATED。',
      okText: '终止',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => doAction(() => killSession(id), '已终止'),
    })
  }, [id, doAction])

  const openDiff = useCallback(async () => {
    if (!id) return
    setDiffLoading(true)
    try {
      setDiff(await sessionDiff(id))
      setDiffOpen(true)
    } catch (e) {
      message.error(`获取 diff 失败：${(e as Error).message}`)
    } finally {
      setDiffLoading(false)
    }
  }, [id])

  // 合并 REST 历史 + WS 实时事件，按 seq 排序去重
  const history = useMemo(() => {
    const merged = new Map<number, SessionEvent>()
    for (const e of baseEvents) merged.set(e.seq, e)
    for (const e of events) merged.set(e.seq, e)
    return Array.from(merged.values()).sort((a, b) => a.seq - b.seq)
  }, [baseEvents, events])

  const canInput = session ? ['RUNNING', 'WAITING_INPUT', 'WAITING_AUTH'].includes(session.state) : false
  const canSuspend = !!session && ACTIVE_STATES.includes(session.state)
  const canResume = session?.state === 'SUSPENDED'

  if (loading) {
    return (
      <Card>
        <Spin />
      </Card>
    )
  }
  if (!session) {
    return (
      <Card>
        <Empty description="会话不存在" />
      </Card>
    )
  }

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {/* 头部信息 */}
      <Card
        size="small"
        title={
          <Space>
            <Typography.Text code>{session.id}</Typography.Text>
            <Tag color={stateColor[session.state] ?? 'default'}>{session.state}</Tag>
            <Badge
              status={connected ? 'success' : fatal ? 'default' : 'processing'}
              text={connected ? '实时' : fatal ? '历史(终态)' : '连接中…'}
            />
          </Space>
        }
        extra={
          <Space>
            {canSuspend && (
              <Button size="small" icon={<StopOutlined />} onClick={onFinish}>
                结束
              </Button>
            )}
            {canSuspend && (
              <Button size="small" icon={<PauseOutlined />} onClick={() => doAction(() => suspendSession(id!), '已挂起')}>
                挂起
              </Button>
            )}
            {canResume && (
              <Button size="small" icon={<CaretRightOutlined />} onClick={() => doAction(() => resumeSession(id!), '已恢复')}>
                恢复
              </Button>
            )}
            <Button size="small" icon={<DiffOutlined />} loading={diffLoading} onClick={openDiff}>
              Diff
            </Button>
            <Button size="small" icon={<BulbOutlined />} onClick={() => setSedimentOpen(true)}>
              沉淀经验
            </Button>
            {session.worktreePath && !canSuspend && (
              <Button size="small" icon={<DeleteOutlined />} onClick={onRemoveWorktree}>
                清理 worktree
              </Button>
            )}
            {canSuspend && (
              <Button size="small" danger icon={<StopOutlined />} onClick={onKill}>
                终止
              </Button>
            )}
          </Space>
        }
      >
        <Descriptions size="small" column={{ xs: 1, sm: 2, md: 4 }}>
          <Descriptions.Item label="项目">{session.projectId}</Descriptions.Item>
          <Descriptions.Item label="PID">{session.pid ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="模型">{session.model || '默认'}</Descriptions.Item>
          <Descriptions.Item label="创建">{new Date(session.createdAt).toLocaleString()}</Descriptions.Item>
          <Descriptions.Item label="完成">{session.finishedAt ? new Date(session.finishedAt).toLocaleString() : '-'}</Descriptions.Item>
          <Descriptions.Item label="Worktree" span={3}>
            <Typography.Text code copyable style={{ fontSize: 12 }}>
              {session.worktreePath || '-'}
            </Typography.Text>
          </Descriptions.Item>
        </Descriptions>
        {session.summary && (
          <Typography.Paragraph style={{ marginBottom: 0 }}>
            <Typography.Text strong>摘要：</Typography.Text>
            <Typography.Text>{session.summary}</Typography.Text>
          </Typography.Paragraph>
        )}
      </Card>

      {/* 授权请求条 */}
      {pendingReq && (
        <Card size="small" style={{ borderColor: '#fa8c16', background: '#fff7e6' }}>
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
                <pre style={{ whiteSpace: 'pre-wrap', margin: 0, fontSize: 12 }}>
                  {pendingReq.content}
                </pre>
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

      {/* 实时输出（共享终端流组件） */}
      <Card
        size="small"
        title="实时输出"
        extra={
          <Button size="small" icon={<PoweroffOutlined />} onClick={loadSession}>
            刷新
          </Button>
        }
      >
        <EventStream
          events={history}
          emptyText={`等待事件…（${fatal ? '会话已结束' : connected ? '连接正常' : '重连中'}）`}
        />
      </Card>

      {/* 输入框 */}
      <Card size="small" style={{ background: '#fafafa' }}>
        <Space.Compact style={{ width: '100%' }}>
          <Input
            disabled={!canInput}
            value={inputText}
            placeholder={
              canInput
                ? session.state === 'WAITING_INPUT'
                  ? '回复 agent 的提问，回车发送…'
                  : session.state === 'WAITING_AUTH'
                    ? '（正在等待授权，可在上方允许/拒绝）'
                    : '会话运行中，可注入指令…'
                : '会话已结束，无法输入'
            }
            onChange={(e) => setInputText(e.target.value)}
            onPressEnter={() => onSend(inputText)}
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
      </Card>

      {/* Diff 弹窗 */}
      <Modal
        title="Worktree Diff"
        open={diffOpen}
        onCancel={() => setDiffOpen(false)}
        footer={null}
        width={720}
      >
        {diff && (
          <>
            {!diff.hasChanges && <Empty description="无变更" />}
            {diff.files.length > 0 && (
              <>
                <Typography.Title level={5} style={{ marginTop: 0 }}>
                  变更文件
                </Typography.Title>
                <pre style={{ whiteSpace: 'pre-wrap', background: '#f6f6f6', padding: 8, borderRadius: 4, fontSize: 12 }}>
                  {diff.stat || diff.files.join('\n')}
                </pre>
              </>
            )}
          </>
        )}
      </Modal>

      {/* 沉淀经验（CAP-04） */}
      <SedimentExperienceModal
        open={sedimentOpen}
        onClose={() => setSedimentOpen(false)}
        sessionId={session.id}
        projectId={session.projectId}
        defaultTitle={session.summary ? `会话 ${session.id.slice(0, 6)} 经验沉淀` : undefined}
      />
    </Space>
  )
}
