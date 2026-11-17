// CAP-109 全局活跃会话页：跨项目聚合活跃会话（左列表 + 右侧对话/操作条），就地对话与生命周期操作。
// 与项目会话页的差异：不进项目上下文（路由在 ProjectContextGate 外）、数据走 statuses 多状态过滤、
// 行内多一个所属项目标签；不做跨项目需求/工作单元引用解析（useSessionRefs 是单项目的），
// 引用标签退化为原始 id，完整上下文由「在项目工作台打开」跳转收口。
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Badge, Button, Card, Empty, List, Modal, Segmented, Space, Tag, Tooltip, Typography, message, theme } from 'antd'
import {
  CaretRightOutlined,
  ExportOutlined,
  PauseOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { deleteSession, getSession, listSessionsConditional } from '../api'
import type { SessionSummary } from '../types'
import { stateColor, ACTIVE_STATES } from '../stateMeta'
import { useSessionActions } from '../hooks/useSessionActions'
import type { SessionRefs } from '../hooks/useSessionRefs'
import SessionRefsTags from '../components/SessionRefsTags'
import ChatPanel from '../../../shared/chat/ChatPanel'
import type { StreamMeta } from '../../../shared/chat/types'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import { fmtTime, usageText } from '../../../shared/utils/format'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'
import { setCurrentProject } from '../../../app/currentProjectStore'
import { usePermissions } from '../../auth/usePermissions'

type Scope = 'active' | 'with-suspended' | 'all'
const SCOPE_STATUSES: Record<Scope, string[] | undefined> = {
  active: [...ACTIVE_STATES],
  'with-suspended': [...ACTIVE_STATES, 'SUSPENDED'],
  all: undefined, // 不传 statuses = 全部
}
const SCOPE_LABELS: Record<Scope, string> = { active: '活跃', 'with-suspended': '含挂起', all: '全部' }

// 跨项目页不解析引用（见文件头注释），空映射让 SessionRefsTags 退化为原始 id
const EMPTY_REFS: SessionRefs = { requirementOf: () => undefined, workItemOf: () => undefined }

export default function ActiveSessionsPage() {
  const { token } = theme.useToken()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [projects, setProjects] = useState<Project[]>([])
  const [agentNodes, setAgentNodes] = useState<AgentNode[]>([])
  // 初始 true：首趟加载完成前不做「?sid= 深链/自动选中」判断（同 SessionsBoard 的时序坑）
  const [loading, setLoading] = useState(true)
  const [scope, setScope] = useState<Scope>('active')
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined)
  // 列表行 taskSpec 是预览；对话气泡任务说明要全文，选中后按 id 补拉单条
  const [selectedFull, setSelectedFull] = useState<SessionSummary | undefined>(undefined)
  const [streamMeta, setStreamMeta] = useState<StreamMeta>({ connected: false, fatal: false })
  const etagRef = useRef<string | undefined>(undefined)
  const { canAction } = usePermissions()
  const canManage = canAction('top.sessions.action.manage')

  const projectName = useMemo(() => {
    const m = new Map(projects.map((p) => [p.id, p.name]))
    return (id: string) => m.get(id) ?? id
  }, [projects])
  const nodeName = useCallback(
    (id?: string) =>
      id ? agentNodes.find((n) => String(n.id) === id)?.name ?? `节点${id}` : undefined,
    [agentNodes],
  )

  // 分段切换后旧 ETag 对应另一份数据，作废重新全量拉
  useEffect(() => {
    etagRef.current = undefined
  }, [scope])

  const load = useCallback(async () => {
    try {
      const fresh = await listSessionsConditional(undefined, undefined, etagRef, SCOPE_STATUSES[scope])
      if (fresh) setSessions(fresh) // null = 304 未变化
    } catch (e) {
      showError(e, '加载会话失败')
    } finally {
      setLoading(false)
    }
  }, [scope])

  // 轮询（同 SessionsBoard）：有活跃会话 3s、全终态 15s；页签隐藏暂停、回前台补拉
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

  // 项目名映射：失败静默退化为原始 projectId（全局页不挡主流程）
  useEffect(() => {
    listProjects().then(setProjects).catch(() => undefined)
    listAgentNodes().then(setAgentNodes).catch(() => undefined)
  }, [])

  // 深链 ?sid=（首页「进行中的会话」/ 通知跳转）：加载后选中并清参数，防粘性
  useEffect(() => {
    const sid = searchParams.get('sid')
    if (!sid || loading) return
    setSelectedId(sid)
    searchParams.delete('sid')
    setSearchParams(searchParams, { replace: true })
  }, [searchParams, loading, setSearchParams])

  // 选中态兜底：会话被删除/切分段后不在列表时退出选中
  const current = useMemo(() => sessions.find((s) => s.id === selectedId), [sessions, selectedId])
  useEffect(() => {
    if (selectedId && !loading && !current) setSelectedId(undefined)
  }, [selectedId, current, loading])

  // 全文 taskSpec 补拉（同 SessionsBoard）
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
  const { onFinish, onSuspend, onResume, onKill } = useSessionActions(selectedId, onUpdated)

  const confirmDelete = (r: SessionSummary) => {
    Modal.confirm({
      centered: true,
      title: '删除该会话？',
      // CAP-42：固定工作区会话删除会一并释放节点工作区，文案必须写明（同 SessionsBoard）
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

  // 「在项目工作台打开」：切当前项目并深链项目会话页（完整上下文/产出/收口在项目内操作）
  const openInProject = (s: SessionSummary) => {
    setCurrentProject(s.projectId)
    navigate(`/sessions?sid=${s.id}`)
  }

  const canSuspend = !!current && ACTIVE_STATES.includes(current.state)
  const canResume = !!current && ['SUSPENDED', 'DONE', 'FAILED', 'TERMINATED'].includes(current.state)

  return (
    <Card
      title={
        <Space size={12}>
          <span>活跃会话</span>
          <Segmented
            value={scope}
            onChange={(v) => setScope(v as Scope)}
            options={(Object.keys(SCOPE_LABELS) as Scope[]).map((k) => ({ value: k, label: SCOPE_LABELS[k] }))}
          />
        </Space>
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
        跨项目聚合活跃会话，选中后可就地对话与操作；新建会话请在项目工作台发起。
      </Typography.Paragraph>

      <div style={{ display: 'flex', alignItems: 'stretch', flex: 1, minHeight: 0 }}>
        {/* 左栏列表：交互仿 SessionListPane（选中高亮/hover），行内多项目名标签 */}
        <div
          style={{
            width: 320,
            flexShrink: 0,
            borderRight: '1px solid #f0f0f0',
            marginRight: 12,
            paddingRight: 12,
            display: 'flex',
            flexDirection: 'column',
            gap: 8,
          }}
        >
          <List
            loading={loading}
            dataSource={sessions}
            style={{ flex: 1, minHeight: 0, overflow: 'auto' }}
            locale={{ emptyText: <Empty description={scope === 'active' ? '暂无活跃会话' : '暂无会话'} /> }}
            renderItem={(s) => (
              <div
                onClick={() => setSelectedId(s.id)}
                style={{
                  padding: '8px 10px',
                  marginBottom: 4,
                  borderRadius: 6,
                  cursor: 'pointer',
                  background: s.id === selectedId ? token.colorPrimaryBg : undefined,
                }}
                onMouseEnter={(e) => {
                  if (s.id !== selectedId) e.currentTarget.style.background = token.colorFillQuaternary
                }}
                onMouseLeave={(e) => {
                  if (s.id !== selectedId) e.currentTarget.style.background = ''
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                  <Typography.Text strong ellipsis style={{ flex: 1, fontSize: 13 }}>
                    {s.taskSpec.slice(0, 40) || s.id}
                  </Typography.Text>
                  <Tag color={stateColor[s.state] ?? 'default'} style={{ marginInlineEnd: 0, flexShrink: 0 }}>
                    {s.state}
                  </Tag>
                </div>
                <div style={{ marginTop: 2, display: 'flex', flexWrap: 'wrap', gap: 2, alignItems: 'center' }}>
                  {/* 所属项目（跨项目视图的核心信息） */}
                  <Tag color="geekblue" style={{ marginInlineEnd: 0, fontSize: 11, lineHeight: '16px', padding: '0 4px' }}>
                    {projectName(s.projectId)}
                  </Tag>
                  {s.state.startsWith('WAITING_') && (
                    <Tag color="orange" style={{ marginInlineEnd: 0, fontSize: 11, lineHeight: '16px', padding: '0 4px' }}>
                      在等人
                    </Tag>
                  )}
                  {nodeName(s.agentNodeId) && (
                    <Tag color="purple" style={{ marginInlineEnd: 0, fontSize: 11, lineHeight: '16px', padding: '0 4px' }}>
                      {nodeName(s.agentNodeId)}
                    </Tag>
                  )}
                </div>
                {(s.requirementId || s.workItemId) && (
                  <div style={{ marginTop: 2 }}>
                    <SessionRefsTags session={s} refs={EMPTY_REFS} maxWidth={180} />
                  </div>
                )}
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {fmtTime(s.createdAt)}
                  {usageText(s) && ` · ${usageText(s)}`}
                </Typography.Text>
              </div>
            )}
          />
        </div>

        {/* 右侧：操作条 + 对话（矮视口兜底滚动同 SessionsBoard） */}
        <div style={{ flex: 1, minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'auto' }}>
          {current ? (
            <>
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
                  <Tooltip title="所属项目">
                    <Tag color="geekblue" style={{ marginInlineEnd: 0 }}>{projectName(current.projectId)}</Tag>
                  </Tooltip>
                  <SessionRefsTags session={current} refs={EMPTY_REFS} />
                  <Badge
                    status={streamMeta.connected ? 'success' : streamMeta.fatal ? 'default' : 'processing'}
                    text={streamMeta.connected ? '实时' : streamMeta.fatal ? '历史(终态)' : '连接中…'}
                  />
                </Space>
                <Space size={4} wrap>
                  <Button size="small" icon={<ExportOutlined />} onClick={() => openInProject(current)}>
                    在项目工作台打开
                  </Button>
                  {canManage && canSuspend && (
                    <Button size="small" icon={<StopOutlined />} onClick={onFinish}>
                      结束
                    </Button>
                  )}
                  {canManage && canSuspend && (
                    <Button size="small" icon={<PauseOutlined />} onClick={onSuspend}>
                      挂起
                    </Button>
                  )}
                  {canManage && canResume && (
                    <Button size="small" icon={<CaretRightOutlined />} onClick={onResume}>
                      {current.state === 'SUSPENDED' ? '恢复' : '继续对话'}
                    </Button>
                  )}
                  {canManage && canSuspend && (
                    <Button size="small" danger icon={<StopOutlined />} onClick={onKill}>
                      终止
                    </Button>
                  )}
                  {canManage && (
                    <Button size="small" danger onClick={() => confirmDelete(current)}>
                      删除
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
              />
            </>
          ) : (
            <Empty
              description={sessions.length > 0 ? '在左侧选择一个会话查看对话' : '暂无会话'}
              style={{ marginTop: 80 }}
            />
          )}
        </div>
      </div>
    </Card>
  )
}
