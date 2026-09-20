// 会话工作台：默认对话视图（左侧会话列表 + 右侧对话交互，类聊天应用），可切换表格列表视图。
// CAP-39：会话详情页已裁撤——产出推送/更多操作（上下文/沉淀/清理 worktree）均在操作条，深链 /sessions?sid=<id>。
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Badge, Button, Card, Input, Modal, Segmented, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  CaretRightOutlined,
  CloudSyncOutlined,
  DiffOutlined,
  PauseOutlined,
  ReloadOutlined,
  StopOutlined,
  UploadOutlined,
} from '@ant-design/icons'
import { useSearchParams } from 'react-router-dom'
import { collectSessionOutputs, deleteSession, listSessions } from '../api'
import type { SessionSummary } from '../types'
import { stateColor, ACTIVE_STATES, STATE_OPTIONS } from '../stateMeta'
import { useSessionActions } from '../hooks/useSessionActions'
import SessionListPane from '../components/SessionListPane'
import ChatPanel from '../../../shared/chat/ChatPanel'
import type { StreamMeta } from '../../../shared/chat/types'
import NewSessionDraft from '../components/NewSessionDraft'
import SessionDiffModal from '../components/SessionDiffModal'
import SessionOutputsModal from '../components/SessionOutputsModal'
import SessionMoreActions from '../components/SessionMoreActions'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import { syncSessionReports } from '../../worklog/api'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'
import { useCurrentProjectId } from '../../../app/useCurrentProject'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

// 活跃在前 + 创建时间倒序（与 SessionListPane 一致，用于自动选中第一个）
function sortForBoard(list: SessionSummary[]): SessionSummary[] {
  return [...list].sort((a, b) => {
    const aa = ACTIVE_STATES.includes(a.state) ? 0 : 1
    const bb = ACTIVE_STATES.includes(b.state) ? 0 : 1
    return aa - bb || +new Date(b.createdAt) - +new Date(a.createdAt)
  })
}

export default function SessionsBoard({
  projectId: fixedProjectId,
  title,
  view: controlledView,
  onViewChange,
  worklog = false,
}: {
  /** 锁定项目（如工作日志页「对话/对话列表」视图锁定 WORKLOG 空间）；不传则跟随当前项目切换器 */
  projectId?: string
  /** 自定义 Card 标题（内嵌场景传入外层视图切换器，此时须配合受控 view 使用——内层 Segmented 不再渲染） */
  title?: ReactNode
  /** 受控视图（chat/list）：传入后内层「对话/列表」Segmented 隐藏，由外层切换器驱动 */
  view?: string
  onViewChange?: (v: string) => void
  /** 工作日志空间模式：隐藏 Diff/推送产出/更多（无 worktree/需求文档语义），操作条改出「推送工作日志」 */
  worklog?: boolean
}) {
  // CAP-39：深链 /sessions?sid=<id>（通知/需求关联记录等原详情页入口统一落这里）
  const [searchParams, setSearchParams] = useSearchParams()
  // CAP-31：会话归属当前项目（/sessions 在 ProjectContextGate 内，必有当前项目；锁定项目时以 fixedProjectId 为准）
  const storeProjectId = useCurrentProjectId()
  const projectId = fixedProjectId ?? storeProjectId
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [agentNodes, setAgentNodes] = useState<AgentNode[]>([])
  const [loading, setLoading] = useState(false)
  const [innerView, setInnerView] = useState<string>('chat') // chat | list（受控时以 controlledView 为准）
  const view = controlledView ?? innerView
  const setView = onViewChange ?? setInnerView
  const [status, setStatus] = useState('ALL')
  const [keyword, setKeyword] = useState('')
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined)
  const [draft, setDraft] = useState(false)
  const [outputsOpen, setOutputsOpen] = useState(false)
  const [streamMeta, setStreamMeta] = useState<StreamMeta>({ connected: false, fatal: false })
  const autoPickedRef = useRef(false)

  const load = useCallback(async () => {
    try {
      setSessions(await listSessions(projectId ?? undefined))
    } catch (e) {
      showError(e, '加载会话失败')
    } finally {
      setLoading(false)
    }
  }, [projectId])

  // 切换当前项目：清选中态与自动选中标记，避免选中到上一个项目的会话
  useEffect(() => {
    autoPickedRef.current = false
    setSelectedId(undefined)
    setDraft(false)
  }, [projectId])

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

  // 深链 ?sid=：列表加载后选中目标会话并清参数（防粘性选中；跨项目会话不在列表时由消失兜底清选中态）
  useEffect(() => {
    const sid = searchParams.get('sid')
    if (!sid || loading) return
    setDraft(false)
    setSelectedId(sid)
    searchParams.delete('sid')
    setSearchParams(searchParams, { replace: true })
  }, [searchParams, loading, setSearchParams])

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

  // CAP-41 worklog 模式：「推送工作日志」——先让 runner 即时回传产出（进行中的会话也可），
  // 再把 daily-/weekly- 成稿同步为日报/周报镜像（幂等；已确认不覆盖）
  const [syncingWorklog, setSyncingWorklog] = useState(false)
  const onSyncWorklog = async () => {
    if (!current) return
    setSyncingWorklog(true)
    try {
      const collect = await collectSessionOutputs(current.id)
      if (collect.message) message.warning(collect.message)
      const r = await syncSessionReports(current.id)
      if (r.mirrored.length > 0) {
        message.success(
          `已同步：${r.mirrored.join('、')}${r.skipped.length ? `；未覆盖：${r.skipped.join('、')}` : ''}`,
        )
      } else if (r.skipped.length > 0) {
        message.info(`未覆盖：${r.skipped.join('、')}`)
      } else {
        message.info('该会话暂无日志成稿（让 claude 把日报/周报写入 .devmind/output/ 后再推送）')
      }
    } catch (e) {
      showError(e, '推送工作日志失败')
    } finally {
      setSyncingWorklog(false)
    }
  }

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
          showError(e, '删除失败')
        }
      },
    })
  }

  const onSelect = (id: string) => {
    setDraft(false)
    setSelectedId(id)
  }

  const canSuspend = !!current && ACTIVE_STATES.includes(current.state)
  // SUSPENDED=恢复；DONE/FAILED/TERMINATED=继续对话（claude --resume 带历史重拉起，worktree 挂回原分支）
  const canResume = !!current && ['SUSPENDED', 'DONE', 'FAILED', 'TERMINATED'].includes(current.state)

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
          '本机（历史）'
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
        title ?? (
          <Space size={12}>
            <span>会话工作台</span>
            <Segmented
              value={view}
              onChange={(v) => setView(v as string)}
              options={[
                { value: 'chat', label: '对话' },
                { value: 'list', label: '列表' },
              ]}
            />
          </Space>
        )
      }
      extra={
        <Button icon={<ReloadOutlined />} onClick={() => load()}>
          刷新
        </Button>
      }
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12, flexShrink: 0 }}>
        左侧选会话、右侧直接对话；「新对话」输入任务说明即创建 agent。列表视图可按状态筛选、搜索全部会话。
      </Typography.Paragraph>

      {view === 'chat' ? (
        <div style={{ display: 'flex', alignItems: 'stretch', flex: 1, minHeight: 0 }}>
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
          {/* overflow:auto 是兜底：右侧「操作条 + 摘要卡 + 输入区」的固定高度在矮视口（≤1366x768）会超过卡片高度，
              不兜底就顶破内容区（实测 1280x720 溢 50px）；空间够时不会出现滚动条，行为与原先一致 */}
          <div style={{ flex: 1, minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'auto' }}>
            {draft ? (
              <NewSessionDraft
                projectId={projectId ?? undefined}
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
                        {current.state === 'SUSPENDED' ? '恢复' : '继续对话'}
                      </Button>
                    )}
                    {worklog && (
                      <Button
                        size="small"
                        type="primary"
                        icon={<CloudSyncOutlined />}
                        loading={syncingWorklog}
                        onClick={onSyncWorklog}
                      >
                        推送工作日志
                      </Button>
                    )}
                    {!worklog && (
                      <>
                        <Button size="small" icon={<DiffOutlined />} loading={diff.loading} onClick={diff.show}>
                          Diff
                        </Button>
                        <Button size="small" icon={<UploadOutlined />} onClick={() => setOutputsOpen(true)}>
                          推送产出
                        </Button>
                        <SessionMoreActions session={current} canSuspend={canSuspend} onChanged={load} />
                      </>
                    )}
                    {canSuspend && (
                      <Button size="small" danger icon={<StopOutlined />} onClick={onKill}>
                        终止
                      </Button>
                    )}
                  </Space>
                </div>
                <ChatPanel
                  key={current.id}
                  summary={{ ...current, topic: current.taskSpec }}
                  apiBase="/sessions"
                  maxHeight={null}
                  onChanged={load}
                  onStreamMeta={setStreamMeta}
                />
              </>
            ) : (
              <NewSessionDraft projectId={projectId ?? undefined} onCreated={onDraftCreated} />
            )}
          </div>
        </div>
      ) : (
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
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
            pagination={LIST_PAGINATION}
            locale={{ emptyText: '暂无会话。切到「对话」视图点「新对话」创建第一个。' }}
          />
        </div>
      )}

      <SessionDiffModal open={diff.open} diff={diff.data} onClose={diff.close} />
      <SessionOutputsModal open={outputsOpen} onClose={() => setOutputsOpen(false)} session={current ?? null} />
    </Card>
  )
}
