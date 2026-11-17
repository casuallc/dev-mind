// 会话工作台：默认对话视图（左侧会话列表 + 右侧对话交互，类聊天应用），可切换表格列表视图。
// CAP-39：会话详情页已裁撤——产出推送/更多操作（上下文/沉淀/清理 worktree）均在操作条，深链 /sessions?sid=<id>。
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Badge, Button, Card, Empty, Input, Modal, Segmented, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd'
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
import { collectSessionOutputs, deleteSession, getSession, listSessions, listSessionsConditional } from '../api'
import type { SessionSummary } from '../types'
import { stateColor, ACTIVE_STATES, STATE_OPTIONS } from '../stateMeta'
import { useSessionActions } from '../hooks/useSessionActions'
import SessionListPane from '../components/SessionListPane'
import SessionRefsTags from '../components/SessionRefsTags'
import { useSessionRefs } from '../hooks/useSessionRefs'
import ChatPanel from '../../../shared/chat/ChatPanel'
import type { StreamMeta, WorkspaceSnapshot } from '../../../shared/chat/types'
import WorkspacePanel from '../../../shared/chat/workspace/WorkspacePanel'
import NewSessionDraft from '../components/NewSessionDraft'
import SessionDiffDrawer from '../components/SessionDiffDrawer'
import SessionOutputsModal from '../components/SessionOutputsModal'
import SessionMoreActions from '../components/SessionMoreActions'
import WorkspaceAdminPane from '../components/WorkspaceAdminPane'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import { syncSessionReports } from '../../worklog/api'
import { fmtTime, fmtTokens, usageText } from '../../../shared/utils/format'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'
import { useCurrentProjectId } from '../../../app/useCurrentProject'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { usePermissions, usePermittedView } from '../../auth/usePermissions'

const SESSION_BOARD_VIEWS = ['chat', 'list', 'workspace'] as const
type SessionBoardView = (typeof SESSION_BOARD_VIEWS)[number]
const SESSION_BOARD_VIEW_PERMISSIONS: Record<SessionBoardView, string> = {
  chat: 'project.sessions.tab.chat',
  list: 'project.sessions.tab.list',
  workspace: 'project.sessions.tab.workspace',
}
const SESSION_BOARD_VIEW_LABELS: Record<SessionBoardView, string> = { chat: '对话', list: '列表', workspace: '工作区' }

// REQ-23：每页条数（服务端默认 10，这里显式传，「加载更多」按同尺寸翻页）
const PAGE_SIZE = 10

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
  // 需求/工作单元 id→对象映射（会话关联展示 + 跳需求页）
  const refs = useSessionRefs(projectId, sessions)
  // 初始 true：首帧 loading 必须为 true，否则下方「自动选中」effect 在首趟加载完成前
  // 就以空列表误判「无会话」→ setDraft(true)，把 ?sid= 深链选中的会话盖成草稿态
  const [loading, setLoading] = useState(true)
  const [innerView, setInnerView] = useState<string>('chat') // chat | list（受控时以 controlledView 为准）
  const view = controlledView ?? innerView
  const setView = onViewChange ?? setInnerView
  const { canAction } = usePermissions()
  const canCreate = canAction(worklog ? 'top.worklog.action.create-session' : 'project.sessions.action.create')
  const canManageSession = canAction(worklog ? 'top.worklog.action.manage-session' : 'project.sessions.action.manage')
  const canOutputs = canAction('project.sessions.action.outputs')
  const canSyncWorklog = canAction('top.worklog.action.manage-entry')
  const permittedViews = usePermittedView(
    SESSION_BOARD_VIEWS,
    (v) => SESSION_BOARD_VIEW_PERMISSIONS[v],
    (SESSION_BOARD_VIEWS as readonly string[]).includes(view) ? (view as SessionBoardView) : 'chat',
    setInnerView,
    !controlledView,
  )
  const [status, setStatus] = useState('ALL')
  const [keyword, setKeyword] = useState('')
  // 防抖后的搜索词：实际下发服务端（列表 taskSpec/summary 已是截断预览，客户端过滤会漏全文命中）
  const [kw, setKw] = useState('')
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined)
  // 列表行的 taskSpec 是 200 字预览；对话气泡的任务说明要全文，选中后按 id 补拉单条
  const [selectedFull, setSelectedFull] = useState<SessionSummary | undefined>(undefined)
  const [draft, setDraft] = useState(false)
  const [outputsOpen, setOutputsOpen] = useState(false)
  const [streamMeta, setStreamMeta] = useState<StreamMeta>({ connected: false, fatal: false })
  // CAP-54：WS 旁路工作区快照（ChatPanel 上抛），操作条「工作区」入口按钮的徽标/抽屉数据源
  const [workspace, setWorkspace] = useState<WorkspaceSnapshot | undefined>(undefined)
  const autoPickedRef = useRef(false)
  // 条件拉取的 ETag：数据没变服务端回 304，省掉每轮几百 KB 的传输与 JSON 解析
  const etagRef = useRef<string | undefined>(undefined)
  // REQ-23 分页：hasMore/loadingMore 驱动「加载更多」；offsetRef 记录下一页的 offset（每次翻页 +PAGE_SIZE）；
  // filterKeyRef 识别过滤条件变化——变化后第一页整列替换，否则轮询只合并首页、保留已加载的历史页（R4）
  const [hasMore, setHasMore] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const offsetRef = useRef(0)
  const filterKeyRef = useRef('')

  // 输入停 300ms 才发服务端搜索，逐键打请求会把轮询通道挤满
  useEffect(() => {
    const t = window.setTimeout(() => setKw(keyword.trim()), 300)
    return () => window.clearTimeout(t)
  }, [keyword])

  // projectId/状态/搜索词变了，旧 ETag 与分页游标对应另一份数据，必须作废重新拉第一页
  useEffect(() => {
    etagRef.current = undefined
    offsetRef.current = 0
    setHasMore(false)
  }, [projectId, kw, status])

  const load = useCallback(async () => {
    const filterKey = `${projectId}|${status}|${kw}`
    try {
      const fresh = await listSessionsConditional(projectId ?? undefined, kw || undefined, status, etagRef, PAGE_SIZE)
      if (!fresh) return // null = 304 未变化，沿用现有列表
      if (filterKeyRef.current !== filterKey) {
        // 过滤条件变化后的第一页：整列替换并重置分页游标
        filterKeyRef.current = filterKey
        offsetRef.current = PAGE_SIZE
        setSessions(fresh.items)
        setHasMore(fresh.hasMore)
      } else {
        // 轮询只刷新第一页（R4）：替换首页行、保留已「加载更多」的历史行；已从首页消失的旧活跃行按终态移除
        setSessions((prev) => {
          const freshIds = new Set(fresh.items.map((s) => s.id))
          return [...fresh.items, ...prev.filter((s) => !freshIds.has(s.id) && !ACTIVE_STATES.includes(s.state))]
        })
        if (offsetRef.current <= PAGE_SIZE) setHasMore(fresh.hasMore)
      }
    } catch (e) {
      showError(e, '加载会话失败')
    } finally {
      setLoading(false)
    }
  }, [projectId, kw, status])

  // 「加载更多」：非条件请求取下一页（offset 累加），按 id 去重追加（R6：翻页间有新会话插入时可能重复一行）
  const loadMore = useCallback(async () => {
    setLoadingMore(true)
    try {
      const page = await listSessions(
        projectId ?? undefined,
        status,
        undefined,
        undefined,
        kw || undefined,
        PAGE_SIZE,
        offsetRef.current,
      )
      offsetRef.current += PAGE_SIZE
      setSessions((prev) => {
        const seen = new Set(prev.map((s) => s.id))
        return [...prev, ...page.items.filter((s) => !seen.has(s.id))]
      })
      setHasMore(page.hasMore)
    } catch (e) {
      showError(e, '加载更多失败')
    } finally {
      setLoadingMore(false)
    }
  }, [projectId, kw, status])

  // 切换当前项目：清选中态与自动选中标记，避免选中到上一个项目的会话
  useEffect(() => {
    autoPickedRef.current = false
    setSelectedId(undefined)
    setDraft(false)
  }, [projectId])

  // 智能轮询刷新状态；对话内容由 ChatPanel 的 WS 实时流负责，这里只刷状态标签/摘要。
  // 有活跃会话 3s、全终态 15s；标签页隐藏时暂停（后台标签页每秒打列表是纯浪费），回到前台立即补一次
  const sessionsRef = useRef<SessionSummary[]>([])
  sessionsRef.current = sessions
  useEffect(() => {
    setLoading(true)
    let timer: number | undefined
    let stopped = false
    const tick = async () => {
      if (!document.hidden) await load()
      if (stopped) return
      const hasActive = sessionsRef.current.some((s) => ACTIVE_STATES.includes(s.state))
      timer = window.setTimeout(tick, hasActive ? 3000 : 15000)
    }
    tick()
    const onVisible = () => {
      if (!document.hidden) load()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      stopped = true
      window.clearTimeout(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [load])

  useEffect(() => {
    listAgentNodes()
      .then(setAgentNodes)
      .catch(() => undefined)
  }, [])

  // 深链 ?sid=：列表加载后选中目标会话并清参数（防粘性选中；目标不在已加载窗口时由下方注入兜底处理）
  useEffect(() => {
    const sid = searchParams.get('sid')
    if (!sid || loading) return
    setDraft(false)
    setSelectedId(sid)
    searchParams.delete('sid')
    setSearchParams(searchParams, { replace: true })
  }, [searchParams, loading, setSearchParams])

  // 首次加载后默认进入新对话草稿态（空白会话），不自动选中最新会话；无创建权限时退回选中第一个
  // （?sid= 深链由上方专用 effect 选中，这里须让路，否则会被覆盖成草稿/第一个会话）
  useEffect(() => {
    if (autoPickedRef.current || loading || searchParams.get('sid')) return
    autoPickedRef.current = true
    if (canCreate) setDraft(true)
    else if (sessions.length > 0) setSelectedId(sortForBoard(sessions)[0].id)
  }, [sessions, loading, searchParams, canCreate])

  // 草稿创建成功后：先等列表刷新拿到新会话行，再选中——否则「消失兜底」effect 会误清新 id
  const onDraftCreated = useCallback(
    async (s: SessionSummary) => {
      await load()
      setDraft(false)
      setSelectedId(s.id)
    },
    [load],
  )

  // REQ-23（R2）：选中项不在已加载窗口时先按 id 单取注入（分页后老会话/?sid= 深链目标可能不在第一页），
  // 取不到（已删除）才清选中态——原先「消失即清选中」会让指向窗口外会话的深链打开一片空白
  const current = useMemo(() => sessions.find((s) => s.id === selectedId), [sessions, selectedId])
  useEffect(() => {
    if (!selectedId || loading || current) return
    let cancelled = false
    getSession(selectedId)
      .then((s) => {
        if (cancelled) return
        setSessions((prev) => (prev.some((x) => x.id === s.id) ? prev : [s, ...prev]))
      })
      .catch(() => {
        if (!cancelled) setSelectedId(undefined)
      })
    return () => {
      cancelled = true
    }
  }, [selectedId, current, loading])

  // 列表行 taskSpec/summary 是 200 字预览（列表瘦身），对话气泡的任务说明要全文
  useEffect(() => {
    setSelectedFull(undefined)
    if (!selectedId) return
    let cancelled = false
    getSession(selectedId)
      .then((s) => {
        if (!cancelled) setSelectedFull(s)
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [selectedId])

  const onUpdated = useCallback(() => load(), [load])
  const { onFinish, onSuspend, onResume, onKill, diff } = useSessionActions(selectedId, onUpdated)

  // CAP-41 worklog 模式：「推送工作日志」——先让 runner 即时回传产出（进行中的会话也可），
  // 再把 daily-/weekly- 成稿写入服务端仓（2026-10-19 起：内容一致不写不提示；仓内被手动
  // 编辑过的文件跳过不覆盖，进 skipped 警示——仓是事实源，手改优先）
  const [syncingWorklog, setSyncingWorklog] = useState(false)
  const onSyncWorklog = async () => {
    if (!current) return
    setSyncingWorklog(true)
    try {
      const collect = await collectSessionOutputs(current.id)
      if (collect.message) message.warning(collect.message)
      const r = await syncSessionReports(current.id)
      if (r.mirrored.length > 0) {
        message.success(`已写入工作日志仓：${r.mirrored.join('、')}`)
      }
      // 手改保护拦下的成稿必须恒可见（即使同时有 mirrored）——用户要知道哪天的手改没被覆盖
      if (r.skipped.length > 0) {
        message.warning(`未覆盖：${r.skipped.join('、')}`)
      }
      if (r.mirrored.length === 0 && r.skipped.length === 0) {
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
      // CAP-42：固定工作区会话删除会一并释放节点工作区（丢弃未提交改动与未合并提交），
      // 文案必须写明——旧文案的"清理 worktree"只管本机时代的字段，用户会以为节点目录不受影响
      content: r.workspaceState === 'OPEN'
        ? '将杀掉进程（如运行中），并释放节点上的固定工作区（丢弃其中未提交改动与未合并提交），不可恢复。'
        : '将杀掉进程（如运行中）并清理 worktree，不可恢复。',
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

  // 切换会话：清工作区快照，避免旧会话变更数徽标带到新会话（新快照由 WS 推送/抽屉打开时 REST 兜底）
  useEffect(() => {
    setWorkspace(undefined)
  }, [selectedId])

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
      ellipsis: { showTitle: false },
      render: (t: string) =>
        t ? (
          <Tooltip placement="topLeft" title={t}>
            {t}
          </Tooltip>
        ) : (
          '-'
        ),
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
      title: '关联',
      key: 'refs',
      width: 200,
      render: (_, r) =>
        // maxWidth 留给工作单元标签的余位（列宽 200 - 单元格 padding 32）
        r.requirementId || r.workItemId ? <SessionRefsTags session={r} refs={refs} maxWidth={110} /> : '-',
    },
    {
      title: '摘要',
      dataIndex: 'summary',
      ellipsis: { showTitle: false },
      render: (s?: string) =>
        s ? (
          <Tooltip placement="topLeft" title={s}>
            {s}
          </Tooltip>
        ) : (
          '-'
        ),
    },
    {
      title: '用量',
      key: 'usage',
      width: 150,
      render: (_, r) => {
        const text = usageText(r)
        if (!text) return '-'
        const detail = [
          `输入 ${fmtTokens(r.inputTokens)} / 输出 ${fmtTokens(r.outputTokens)}`,
          `缓存读 ${fmtTokens(r.cacheReadTokens)} / 缓存写 ${fmtTokens(r.cacheCreationTokens)}`,
        ].join('\n')
        return (
          <Tooltip title={<span style={{ whiteSpace: 'pre-line' }}>{detail}</span>}>{text}</Tooltip>
        )
      },
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
          {canManageSession && (
            <Button size="small" danger onClick={() => confirmDelete(r)}>
              删除
            </Button>
          )}
        </Space>
      ),
    },
  ]

  // REQ-23：关键词/状态过滤都已服务端化（窗口化后客户端再滤会漏数据），表格直接用已加载数据
  const listFiltered = sessions

  return (
    <Card
      title={
        title ?? (
          <Space size={12}>
            <span>会话工作台</span>
            <Segmented
              value={view}
              onChange={(v) => setView(v as string)}
              options={permittedViews.map((v) => ({
                value: v,
                label: SESSION_BOARD_VIEW_LABELS[v],
              }))}
            />
          </Space>
        )
      }
      extra={
        // 工作区视图数据由 WorkspaceAdminPane 自取自刷（面板内有独立刷新按钮）
        view !== 'workspace' ? (
          <Button icon={<ReloadOutlined />} onClick={() => load()}>
            刷新
          </Button>
        ) : undefined
      }
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12, flexShrink: 0 }}>
        默认进入新对话（空白会话），输入任务说明即创建 agent；左侧列表点选可切回历史会话。列表视图可按状态筛选、搜索全部会话。
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
            refs={refs}
            canCreate={canCreate}
            hasMore={hasMore}
            loadingMore={loadingMore}
            onLoadMore={loadMore}
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
                    <SessionRefsTags session={current} refs={refs} />
                    <Badge
                      status={streamMeta.connected ? 'success' : streamMeta.fatal ? 'default' : 'processing'}
                      text={streamMeta.connected ? '实时' : streamMeta.fatal ? '历史(终态)' : '连接中…'}
                    />
                  </Space>
                  <Space size={4} wrap>
                    {canManageSession && canSuspend && (
                      <Button size="small" icon={<StopOutlined />} onClick={onFinish}>
                        结束
                      </Button>
                    )}
                    {canManageSession && canSuspend && (
                      <Button size="small" icon={<PauseOutlined />} onClick={onSuspend}>
                        挂起
                      </Button>
                    )}
                    {canManageSession && canResume && (
                      <Button size="small" icon={<CaretRightOutlined />} onClick={onResume}>
                        {current.state === 'SUSPENDED' ? '恢复' : '继续对话'}
                      </Button>
                    )}
                    {worklog && (
                      <>
                        {canSyncWorklog && (
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
                        {/* CAP-54：worklog 会话也有 runner 持久工作区，入口按钮同样放操作条 */}
                        <WorkspacePanel
                          entry="button"
                          apiBase="/sessions"
                          sessionId={current.id}
                          snapshot={workspace}
                          canDiff
                        />
                      </>
                    )}
                    {!worklog && (
                      <>
                        <Button size="small" icon={<DiffOutlined />} loading={diff.loading} onClick={diff.show}>
                          Diff
                        </Button>
                        {canOutputs && (
                          <Button size="small" icon={<UploadOutlined />} onClick={() => setOutputsOpen(true)}>
                            推送产出
                          </Button>
                        )}
                        {/* CAP-54：工作区入口提到操作条，置于「更多」前 */}
                        <WorkspacePanel
                          entry="button"
                          apiBase="/sessions"
                          sessionId={current.id}
                          snapshot={workspace}
                          canDiff
                        />
                        <SessionMoreActions session={current} canSuspend={canSuspend} onChanged={load} />
                      </>
                    )}
                    {canManageSession && canSuspend && (
                      <Button size="small" danger icon={<StopOutlined />} onClick={onKill}>
                        终止
                      </Button>
                    )}
                  </Space>
                </div>
                <ChatPanel
                  key={current.id}
                  summary={{
                    ...current,
                    topic: selectedFull?.id === current.id ? selectedFull.taskSpec : current.taskSpec,
                  }}
                  apiBase="/sessions"
                  maxHeight={null}
                  allowImages
                  allowFiles
                  onChanged={load}
                  onStreamMeta={setStreamMeta}
                  onWorkspaceSnapshot={setWorkspace}
                />
              </>
            ) : canCreate ? (
              <NewSessionDraft projectId={projectId ?? undefined} onCreated={onDraftCreated} />
            ) : (
              <Empty description="当前账号无新建会话权限" style={{ marginTop: 80 }} />
            )}
          </div>
        </div>
      ) : view === 'workspace' ? (
        // 工作区管理（节点 worktree + 远端 feature 分支）；数据由 pane 自取，不走上方会话列表
        projectId ? (
          <WorkspaceAdminPane projectId={projectId} />
        ) : (
          <Empty description="请先选择项目" style={{ marginTop: 80 }} />
        )
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
            // fixed：声明的列宽才生效，ellipsis 列按剩余宽度均分，长文本不挤破相邻列
            tableLayout="fixed"
            locale={{ emptyText: '暂无会话。切到「对话」视图点「新对话」创建第一个。' }}
          />
        </div>
      )}

      <SessionDiffDrawer open={diff.open} diff={diff.data} onClose={diff.close} />
      <SessionOutputsModal open={outputsOpen} onClose={() => setOutputsOpen(false)} session={current ?? null} />
    </Card>
  )
}
