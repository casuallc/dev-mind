// 会话工作台：默认对话视图（左侧会话列表 + 右侧对话交互，类聊天应用），可切换表格列表视图。
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Badge, Button, Card, Input, Modal, Segmented, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  CaretRightOutlined,
  DiffOutlined,
  PauseOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { deleteSession, listSessions } from '../api'
import type { SessionSummary } from '../types'
import { stateColor, ACTIVE_STATES, STATE_OPTIONS } from '../stateMeta'
import { useSessionActions } from '../hooks/useSessionActions'
import SessionListPane from '../components/SessionListPane'
import ChatPanel from '../../../shared/chat/ChatPanel'
import type { StreamMeta } from '../../../shared/chat/types'
import NewSessionDraft from '../components/NewSessionDraft'
import SessionDiffModal from '../components/SessionDiffModal'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import { fmtTime } from '../../../shared/utils/format'

// 活跃在前 + 创建时间倒序（与 SessionListPane 一致，用于自动选中第一个）
function sortForBoard(list: SessionSummary[]): SessionSummary[] {
  return [...list].sort((a, b) => {
    const aa = ACTIVE_STATES.includes(a.state) ? 0 : 1
    const bb = ACTIVE_STATES.includes(b.state) ? 0 : 1
    return aa - bb || +new Date(b.createdAt) - +new Date(a.createdAt)
  })
}

export default function SessionsBoard() {
  const navigate = useNavigate()
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [agentNodes, setAgentNodes] = useState<AgentNode[]>([])
  const [loading, setLoading] = useState(false)
  const [view, setView] = useState<string>('chat') // chat | list
  const [status, setStatus] = useState('ALL')
  const [keyword, setKeyword] = useState('')
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined)
  const [draft, setDraft] = useState(false)
  const [streamMeta, setStreamMeta] = useState<StreamMeta>({ connected: false, fatal: false })
  const autoPickedRef = useRef(false)

  const load = useCallback(async () => {
    try {
      setSessions(await listSessions())
    } catch (e) {
      message.error(`加载会话失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [])

  // 轮询刷新状态；对话内容由 ChatPanel 的 WS 实时流负责，这里只刷状态标签/摘要
  useEffect(() => {
    setLoading(true)
    load()
    const timer = window.setInterval(() => load(), 3000)
    return () => window.clearInterval(timer)
  }, [load])

  useEffect(() => {
    listAgentNodes()
      .then(setAgentNodes)
      .catch(() => undefined)
  }, [])

  // 首次加载后自动选中：有会话选排序第一个，否则直接进入新对话草稿态
  useEffect(() => {
    if (autoPickedRef.current || loading) return
    autoPickedRef.current = true
    if (sessions.length > 0) setSelectedId(sortForBoard(sessions)[0].id)
    else setDraft(true)
  }, [sessions, loading])

  // 草稿创建成功后：先等列表刷新拿到新会话行，再选中——否则「消失兜底」effect 会误清新 id
  const onDraftCreated = useCallback(
    async (s: SessionSummary) => {
      await load()
      setDraft(false)
      setSelectedId(s.id)
    },
    [load],
  )

  // 选中的会话被删除/消失时兜底退出选中态
  const current = useMemo(() => sessions.find((s) => s.id === selectedId), [sessions, selectedId])
  useEffect(() => {
    if (selectedId && !loading && !current) setSelectedId(undefined)
  }, [selectedId, current, loading])

  const onUpdated = useCallback(() => load(), [load])
  const { onFinish, onSuspend, onResume, onKill, diff } = useSessionActions(selectedId, onUpdated)

  const confirmDelete = (r: SessionSummary) => {
    Modal.confirm({
      centered: true,
      title: '删除该会话？',
      content: '将杀掉进程（如运行中）并清理 worktree，不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteSession(r.id)
          message.success('已删除')
          if (r.id === selectedId) setSelectedId(undefined)
          load()
        } catch (e) {
          message.error(`删除失败：${(e as Error).message}`)
        }
      },
    })
  }

  const onSelect = (id: string) => {
    setDraft(false)
    setSelectedId(id)
  }

  const canSuspend = !!current && ACTIVE_STATES.includes(current.state)
  const canResume = current?.state === 'SUSPENDED'

  const columns: ColumnsType<SessionSummary> = [
    {
      title: 'ID',
      dataIndex: 'id',
      width: 140,
      render: (id: string) => <Typography.Text code>{id}</Typography.Text>,
    },
    {
      title: '任务说明',
      dataIndex: 'taskSpec',
      ellipsis: true,
      render: (t: string) => t?.slice(0, 100) || '-',
    },
    {
      title: '状态',
      dataIndex: 'state',
      width: 130,
      render: (s: string) => <Tag color={stateColor[s] ?? 'default'}>{s}</Tag>,
    },
    {
      title: '节点',
      dataIndex: 'agentNodeId',
      width: 110,
      render: (v?: string) =>
        v ? (
          <Tag color="purple">{agentNodes.find((n) => String(n.id) === v)?.name ?? `节点${v}`}</Tag>
        ) : (
          '本机'
        ),
    },
    {
      title: '摘要',
      dataIndex: 'summary',
      ellipsis: true,
      render: (s?: string) => s?.slice(0, 80) || '-',
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 170,
      render: (t: string) => fmtTime(t),
    },
    {
      title: '操作',
      key: 'action',
      width: 150,
      render: (_, r) => (
        <Space size={4}>
          <Button
            size="small"
            onClick={() => {
              onSelect(r.id)
              setView('chat')
            }}
          >
            对话
          </Button>
          <Button size="small" danger onClick={() => confirmDelete(r)}>
            删除
          </Button>
        </Space>
      ),
    },
  ]

  const listFiltered = useMemo(() => {
    const kw = keyword.trim().toLowerCase()
    const list = status === 'ALL' ? sessions : sessions.filter((s) => s.state === status)
    if (!kw) return list
    return list.filter(
      (s) =>
        s.id.toLowerCase().includes(kw) ||
        s.taskSpec.toLowerCase().includes(kw) ||
        (s.summary ?? '').toLowerCase().includes(kw),
    )
  }, [sessions, status, keyword])

  return (
    <Card
      title={
        <Space size={12}>
          <span>会话工作台</span>
          <Segmented
            value={view}
            onChange={setView}
            options={[
              { value: 'chat', label: '对话' },
              { value: 'list', label: '列表' },
            ]}
          />
        </Space>
      }
      extra={
        <Button icon={<ReloadOutlined />} onClick={() => load()}>
          刷新
        </Button>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        左侧选会话、右侧直接对话；「新对话」输入任务说明即创建 agent。列表视图可按状态筛选、搜索全部会话。
      </Typography.Paragraph>

      {view === 'chat' ? (
        <div style={{ display: 'flex', alignItems: 'stretch' }}>
          <SessionListPane
            sessions={sessions}
            loading={loading}
            selectedId={draft ? undefined : selectedId}
            onSelect={onSelect}
            onNew={() => setDraft(true)}
            status={status}
            onStatusChange={setStatus}
            keyword={keyword}
            onKeywordChange={setKeyword}
          />
          <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
            {draft ? (
              <NewSessionDraft
                onCreated={onDraftCreated}
                onCancel={
                  selectedId || sessions.length > 0
                    ? () => {
                        setDraft(false)
                        if (!selectedId && sessions.length > 0) setSelectedId(sortForBoard(sessions)[0].id)
                      }
                    : undefined
                }
              />
            ) : current ? (
              <>
                {/* 精简操作条 */}
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    gap: 8,
                    flexWrap: 'wrap',
                    marginBottom: 8,
                  }}
                >
                  <Space size={8}>
                    <Typography.Text code>{current.id}</Typography.Text>
                    <Tag color={stateColor[current.state] ?? 'default'}>{current.state}</Tag>
                    <Badge
                      status={streamMeta.connected ? 'success' : streamMeta.fatal ? 'default' : 'processing'}
                      text={streamMeta.connected ? '实时' : streamMeta.fatal ? '历史(终态)' : '连接中…'}
                    />
                  </Space>
                  <Space size={4} wrap>
                    {canSuspend && (
                      <Button size="small" icon={<StopOutlined />} onClick={onFinish}>
                        结束
                      </Button>
                    )}
                    {canSuspend && (
                      <Button size="small" icon={<PauseOutlined />} onClick={onSuspend}>
                        挂起
                      </Button>
                    )}
                    {canResume && (
                      <Button size="small" icon={<CaretRightOutlined />} onClick={onResume}>
                        恢复
                      </Button>
                    )}
                    <Button size="small" icon={<DiffOutlined />} loading={diff.loading} onClick={diff.show}>
                      Diff
                    </Button>
                    {canSuspend && (
                      <Button size="small" danger icon={<StopOutlined />} onClick={onKill}>
                        终止
                      </Button>
                    )}
                    <Button size="small" type="link" onClick={() => navigate(`/sessions/${current.id}`)}>
                      详情 →
                    </Button>
                  </Space>
                </div>
                <ChatPanel
                  key={current.id}
                  summary={{ ...current, topic: current.taskSpec }}
                  apiBase="/sessions"
                  maxHeight="calc(100vh - 400px)"
                  onChanged={load}
                  onStreamMeta={setStreamMeta}
                />
              </>
            ) : (
              <NewSessionDraft onCreated={onDraftCreated} />
            )}
          </div>
        </div>
      ) : (
        <>
          <Space style={{ marginBottom: 12 }} wrap>
            <Select
              value={status}
              onChange={setStatus}
              options={STATE_OPTIONS.map((s) => ({ value: s, label: s }))}
              style={{ width: 140 }}
            />
            <Input.Search
              placeholder="搜索 ID / 任务 / 摘要"
              allowClear
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              style={{ width: 220 }}
            />
          </Space>
          <Table
            rowKey="id"
            loading={loading}
            columns={columns}
            dataSource={listFiltered}
            pagination={false}
            locale={{ emptyText: '暂无会话。切到「对话」视图点「新对话」创建第一个。' }}
          />
        </>
      )}

      <SessionDiffModal open={diff.open} diff={diff.data} onClose={diff.close} />
    </Card>
  )
}
