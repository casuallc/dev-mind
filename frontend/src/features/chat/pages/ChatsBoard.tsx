// AI 问答工作台（CAP-30）：默认对话视图（左侧问答列表 + 右侧对话交互，shared ChatPanel, apiBase=/chats），可切换表格列表视图。
// 与项目会话完全分开：无项目/仓库/Diff/详情页，问答在干净沙箱运行，按创建人隔离。
// CAP-46：?kbId=<id> 跳入（知识库详情「发起会话」）→ 新问答草稿预选该库；绑库问答标题区显示库名标签。
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Alert, Badge, Button, Card, Input, Modal, Segmented, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  CaretRightOutlined,
  DeleteOutlined,
  FileAddOutlined,
  PauseOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons'
import { deleteChat, finishChat, killChat, listChatEvents, listChats, resumeChat, suspendChat } from '../api'
import type { ChatSummary } from '../types'
import { stateColor, ACTIVE_STATES, STATE_OPTIONS } from '../../../shared/chat/stateMeta'
import ChatPanel from '../../../shared/chat/ChatPanel'
import type { StreamMeta, WorkspaceSnapshot } from '../../../shared/chat/types'
import WorkspacePanel from '../../../shared/chat/workspace/WorkspacePanel'
import ChatListPane from '../components/ChatListPane'
import NewChatDraft from '../components/NewChatDraft'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import { listBases, saveSolution } from '../../knowledge/api'
import type { KnowledgeBase } from '../../knowledge/types'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import { isAdmin } from '../../auth/authStore'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

// 活跃在前 + 创建时间倒序（与 ChatListPane 一致，用于自动选中第一个）
function sortForBoard(list: ChatSummary[]): ChatSummary[] {
  return [...list].sort((a, b) => {
    const aa = ACTIVE_STATES.includes(a.state) ? 0 : 1
    const bb = ACTIVE_STATES.includes(b.state) ? 0 : 1
    return aa - bb || +new Date(b.createdAt) - +new Date(a.createdAt)
  })
}

export default function ChatsBoard() {
  const [chats, setChats] = useState<ChatSummary[]>([])
  const [agentNodes, setAgentNodes] = useState<AgentNode[]>([])
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])
  const [projects, setProjects] = useState<Project[]>([])
  const [loading, setLoading] = useState(false)
  const [view, setView] = useState<string>('chat') // chat | list
  const [status, setStatus] = useState('ALL')
  const [keyword, setKeyword] = useState('')
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined)
  const [draft, setDraft] = useState(false)
  const [draftKbId, setDraftKbId] = useState<number | undefined>(undefined)
  const [streamMeta, setStreamMeta] = useState<StreamMeta>({ connected: false, fatal: false })
  // CAP-54：WS 旁路工作区快照（ChatPanel 上抛），右侧窄条入口的徽标/抽屉数据源
  const [workspace, setWorkspace] = useState<WorkspaceSnapshot | undefined>(undefined)
  // CAP-84 FR-24：按轮次沉淀方案——选择轮次 + 同一轮前端禁用重复保存
  const [solutionOpen, setSolutionOpen] = useState(false)
  const [solutionLoading, setSolutionLoading] = useState(false)
  const [solutionTurns, setSolutionTurns] = useState<{ turnSeq: number; text: string }[]>([])
  const [solutionTurnSeq, setSolutionTurnSeq] = useState<number | undefined>(undefined)
  const [solutionTitle, setSolutionTitle] = useState('')
  const [solutionProjectId, setSolutionProjectId] = useState<string | undefined>(undefined)
  const [savedTurns, setSavedTurns] = useState<Record<string, number[]>>({})
  const [savingSolution, setSavingSolution] = useState(false)
  const autoPickedRef = useRef(false)
  const [searchParams, setSearchParams] = useSearchParams()

  const load = useCallback(async () => {
    try {
      setChats(await listChats())
    } catch (e) {
      showError(e, '加载问答失败')
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
    listBases()
      .then(setKnowledgeBases)
      .catch(() => undefined)
    listProjects()
      .then(setProjects)
      .catch(() => undefined)
  }, [])

  // CAP-46 FR-04：?kbId=<id> 跳入（知识库详情「发起会话」）→ 进入草稿态并预选该库，参数消费后清掉
  useEffect(() => {
    const kb = Number(searchParams.get('kbId'))
    if (!Number.isFinite(kb) || kb <= 0) return
    setView('chat')
    setDraftKbId(kb)
    setDraft(true)
    setSearchParams({}, { replace: true })
  }, [searchParams, setSearchParams])

  // 深链 ?cid=<id>（用量请求日志等入口，对齐 /sessions?sid= 先例）：
  // 列表加载后选中目标问答并清参数（防粘性选中；目标不在列表时由消失兜底清选中态）
  useEffect(() => {
    const cid = searchParams.get('cid')
    if (!cid || loading) return
    setDraft(false)
    setSelectedId(cid)
    searchParams.delete('cid')
    setSearchParams(searchParams, { replace: true })
  }, [searchParams, loading, setSearchParams])

  // 首次加载后自动选中：有问答选排序第一个，否则直接进入新问答草稿态
  // （?cid= 深链由上方专用 effect 选中，这里须让路，否则会回退覆盖成第一个问答）
  useEffect(() => {
    if (autoPickedRef.current || loading || searchParams.get('cid')) return
    autoPickedRef.current = true
    if (chats.length > 0) setSelectedId(sortForBoard(chats)[0].id)
    else setDraft(true)
  }, [chats, loading, searchParams])

  // 草稿创建成功后：先等列表刷新拿到新问答行，再选中——否则「消失兜底」effect 会误清新 id
  const onDraftCreated = useCallback(
    async (c: ChatSummary) => {
      await load()
      setDraft(false)
      setDraftKbId(undefined)
      setSelectedId(c.id)
    },
    [load],
  )

  // 选中的问答被删除/消失时兜底退出选中态
  const current = useMemo(() => chats.find((c) => c.id === selectedId), [chats, selectedId])
  useEffect(() => {
    if (selectedId && !loading && !current) setSelectedId(undefined)
  }, [selectedId, current, loading])

  // 切换问答：清工作区快照，避免旧问答变更数徽标带到新问答
  useEffect(() => {
    setWorkspace(undefined)
    setSolutionOpen(false)
  }, [selectedId])

  // ---- 生命周期操作（动作后靠轮询刷新状态标签）----
  const act = useCallback(
    async (fn: (id: string) => Promise<unknown>, okText: string) => {
      if (!selectedId) return
      try {
        await fn(selectedId)
        message.success(okText)
        load()
      } catch (e) {
        showError(e, '操作失败')
      }
    },
    [selectedId, load],
  )
  const onFinish = () => act(finishChat, '已请求结束（agent 收尾后自动完成）')
  const onSuspend = () => act(suspendChat, '已挂起')
  const onResume = () => act(resumeChat, '已恢复')
  const onKill = () =>
    Modal.confirm({
      centered: true,
      title: '终止该问答？',
      content: isModelChat
        ? '将中断正在进行的生成（保留问答记录与历史）。'
        : '将强杀 agent 进程并清理沙箱（保留问答记录与历史）。',
      okText: '终止',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => act(killChat, '已终止'),
    })

  const confirmDelete = (c: ChatSummary) => {
    Modal.confirm({
      centered: true,
      title: '删除该问答？',
      content: '将杀掉进程（如运行中）并清理沙箱，不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteChat(c.id)
          message.success('已删除')
          if (c.id === selectedId) setSelectedId(undefined)
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

  // CAP-49：模型执行体没有进程可挂（后端 409），「挂起」只给 Agent 执行体
  const isModelChat = current?.executor === 'MODEL'
  const canSuspend = !!current && !isModelChat && ACTIVE_STATES.includes(current.state)
  // SUSPENDED=恢复（同进程语义）；DONE/FAILED/TERMINATED=继续对话（claude --resume 带历史重拉起，
  // 模型执行体重建运行时即可——历史在事件表里）
  const canResume = !!current && ['SUSPENDED', 'DONE', 'FAILED', 'TERMINATED'].includes(current.state)

  // CAP-84：多库标签（历史 CAP-46 单库回填）；降级原因展示；SOLUTION 一键沉淀
  const currentKbIds = current
    ? current.knowledgeBaseIds?.length
      ? current.knowledgeBaseIds
      : current.knowledgeBaseId != null
        ? [current.knowledgeBaseId]
        : []
    : []
  const degradedReason =
    current?.knowledgeDegradedReason && current.knowledgeDegradedReason !== 'NONE'
      ? current.knowledgeDegradedReason
      : null
  const savedTurnSeqs = current ? (savedTurns[current.id] ?? []) : []

  // 轮次选择来自事件流：assistant 事件的 payload.turnSeq 即该轮用户 seq
  const openSolutionModal = async () => {
    if (!current) return
    setSolutionOpen(true)
    setSolutionLoading(true)
    setSolutionTitle('')
    setSolutionProjectId(undefined)
    setSolutionTurnSeq(undefined)
    try {
      const events = await listChatEvents(current.id)
      const byTurn = new Map<number, string>()
      for (const ev of events) {
        if (ev.type !== 'assistant' || !ev.content?.trim()) continue
        const t = typeof ev.payload?.turnSeq === 'number' ? (ev.payload.turnSeq as number) : undefined
        if (t) byTurn.set(t, ev.content)
      }
      const turns = [...byTurn.entries()]
        .sort((a, b) => a[0] - b[0])
        .map(([turnSeq, text]) => ({ turnSeq, text }))
      setSolutionTurns(turns)
      const available = turns.filter((t) => !savedTurnSeqs.includes(t.turnSeq))
      setSolutionTurnSeq(available.length > 0 ? available[available.length - 1].turnSeq : undefined)
      if (turns.length === 0) message.info('还没有可沉淀的方案回答')
    } catch (e) {
      showError(e, '加载回答轮次失败')
    } finally {
      setSolutionLoading(false)
    }
  }

  const onSubmitSolution = async () => {
    if (!current) return
    if (!solutionTurnSeq) {
      message.warning('请选择要沉淀的回答轮次')
      return
    }
    setSavingSolution(true)
    try {
      const r = await saveSolution(current.id, {
        turnSeq: solutionTurnSeq,
        title: solutionTitle.trim() || undefined,
        kind: 'design',
        projectId: solutionProjectId,
      })
      setSavedTurns((prev) => ({
        ...prev,
        [current.id]: [...(prev[current.id] ?? []), solutionTurnSeq],
      }))
      setSolutionOpen(false)
      Modal.success({
        centered: true,
        title: '已沉淀为文档',
        content: (
          <Space direction="vertical" size={4}>
            <span>
              《{r.title}》已创建（v{r.versionNo}，含证据附录）
            </span>
            {/* 静态 Modal 在独立 React root 里渲染，没有 Router 上下文，只能放普通锚点 */}
            {isAdmin() && <a href={`/admin/docs/${r.documentId}`}>打开文档继续编辑</a>}
          </Space>
        ),
      })
    } catch (e) {
      showError(e, '沉淀失败')
    } finally {
      setSavingSolution(false)
    }
  }

  const columns: ColumnsType<ChatSummary> = [
    {
      title: 'ID',
      dataIndex: 'id',
      width: 140,
      render: (id: string) => <Typography.Text code>{id}</Typography.Text>,
    },
    {
      title: '标题',
      dataIndex: 'title',
      ellipsis: true,
      render: (t: string) => t || '-',
    },
    {
      title: '状态',
      dataIndex: 'state',
      width: 130,
      render: (s: string) => <Tag color={stateColor[s] ?? 'default'}>{s}</Tag>,
    },
    {
      title: '执行体',
      dataIndex: 'executor',
      width: 160,
      // CAP-49：模型执行体不需要节点，agent_node_id 恒空——不能照旧渲染成「本机（历史）」
      render: (_: unknown, r: ChatSummary) =>
        r.executor === 'MODEL' ? (
          <Tag color="cyan">{r.modelEndpointName ?? `模型端点#${r.modelEndpointId ?? '?'}`}</Tag>
        ) : r.agentNodeId ? (
          <Tag color="purple">
            Agent · {agentNodes.find((n) => String(n.id) === r.agentNodeId)?.name ?? `节点${r.agentNodeId}`}
          </Tag>
        ) : (
          'Agent · 本机（历史）'
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
    const list = status === 'ALL' ? chats : chats.filter((c) => c.state === status)
    if (!kw) return list
    return list.filter(
      (c) =>
        c.id.toLowerCase().includes(kw) ||
        c.title.toLowerCase().includes(kw) ||
        (c.summary ?? '').toLowerCase().includes(kw),
    )
  }, [chats, status, keyword])

  return (
    <Card
      title={
        <Space size={12}>
          <span>AI 问答</span>
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
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12, flexShrink: 0 }}>
        纯问答不关联项目/仓库；执行体默认是 runner 节点上的 claude（Agent），也可在「新问答 → 高级选项」里
        改选已接入的对话模型（模型，免节点、流式输出）。左侧选问答、右侧直接对话；「新问答」输入问题即创建。
        列表视图可按状态筛选、搜索全部问答。
      </Typography.Paragraph>

      {view === 'chat' ? (
      <div style={{ display: 'flex', alignItems: 'stretch', flex: 1, minHeight: 0 }}>
        <ChatListPane
          chats={chats}
          loading={loading}
          selectedId={draft ? undefined : selectedId}
          onSelect={onSelect}
          onNew={() => setDraft(true)}
          status={status}
          onStatusChange={setStatus}
          keyword={keyword}
          onKeywordChange={setKeyword}
        />
        <div style={{ flex: 1, minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          {draft ? (
            <NewChatDraft
              presetKbId={draftKbId}
              onCreated={onDraftCreated}
              onCancel={
                selectedId || chats.length > 0
                  ? () => {
                      setDraft(false)
                      setDraftKbId(undefined)
                      if (!selectedId && chats.length > 0) setSelectedId(sortForBoard(chats)[0].id)
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
                  <Typography.Text strong ellipsis style={{ maxWidth: 320 }}>
                    {current.title}
                  </Typography.Text>
                  <Typography.Text code>{current.id}</Typography.Text>
                  <Tag color={stateColor[current.state] ?? 'default'}>{current.state}</Tag>
                  {isModelChat ? (
                    <Tag color="cyan">
                      模型：{current.modelEndpointName ?? `端点#${current.modelEndpointId ?? '?'}`}
                      {current.modelEndpointModel ? ` · ${current.modelEndpointModel}` : ''}
                    </Tag>
                  ) : (
                    <Tag color="purple">
                      Agent ·{' '}
                      {current.agentNodeId
                        ? (agentNodes.find((n) => String(n.id) === current.agentNodeId)?.name ??
                          `节点${current.agentNodeId}`)
                        : '本机（历史）'}
                    </Tag>
                  )}
                  {currentKbIds.map((id) => (
                    <Tag key={id} color="purple">
                      知识库：{knowledgeBases.find((b) => b.id === id)?.name ?? `#${id}`}
                    </Tag>
                  ))}
                  {current.knowledgeMode && (
                    <Tag color={current.knowledgeMode === 'SOLUTION' ? 'magenta' : 'blue'}>
                      {current.knowledgeMode === 'SOLUTION' ? '方案模式' : '知识问答'}
                    </Tag>
                  )}
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
                  {current.knowledgeMode === 'SOLUTION' && (
                    <Button
                      size="small"
                      icon={<FileAddOutlined />}
                      loading={savingSolution}
                      disabled={current.state === 'RUNNING'}
                      onClick={openSolutionModal}
                    >
                      沉淀为文档
                    </Button>
                  )}
                  {canSuspend && (
                    <Button size="small" danger icon={<StopOutlined />} onClick={onKill}>
                      终止
                    </Button>
                  )}
                  <Button size="small" danger icon={<DeleteOutlined />} onClick={() => confirmDelete(current)}>
                    删除
                  </Button>
                </Space>
              </div>
              {degradedReason && (
                <Alert
                  style={{ marginBottom: 8 }}
                  type="warning"
                  showIcon
                  message={`知识检索通道降级：${degradedReason}`}
                  description={
                    degradedReason === 'DIMENSION_MISMATCH'
                      ? '向量维度与当前端点不一致，本轮及后续检索走 BM25 关键词召回；到「后台 → 知识库」重建索引后可恢复向量召回。'
                      : '当前没有可用的向量端点，检索走 BM25 关键词召回；语义相近但字面不同的文档可能漏召。'
                  }
                />
              )}
              <ChatPanel
                key={current.id}
                summary={current}
                apiBase="/chats"
                maxHeight={null}
                allowImages
                onChanged={load}
                onStreamMeta={setStreamMeta}
                onWorkspaceSnapshot={setWorkspace}
                workspaceSlot={
                  // 模型执行体无 runner 工作区，整条隐藏；其余保留右侧窄条入口
                  isModelChat ? undefined : (
                    <WorkspacePanel apiBase="/chats" sessionId={current.id} snapshot={workspace} canDiff={false} />
                  )
                }
              />
            </>
          ) : (
            <NewChatDraft onCreated={onDraftCreated} />
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
              placeholder="搜索标题 / 摘要"
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
            locale={{ emptyText: '暂无问答。切到「对话」视图点「新问答」发起第一个。' }}
          />
        </div>
      )}
      <Modal
        open={solutionOpen}
        title="沉淀方案为文档"
        okText="沉淀"
        cancelText="取消"
        confirmLoading={savingSolution}
        onOk={onSubmitSolution}
        onCancel={() => setSolutionOpen(false)}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div>
            <Typography.Text strong>回答轮次</Typography.Text>
            <Select
              style={{ width: '100%', marginTop: 6 }}
              loading={solutionLoading}
              value={solutionTurnSeq}
              placeholder={solutionLoading ? '加载回答…' : '选择要沉淀的方案回答'}
              onChange={setSolutionTurnSeq}
              options={solutionTurns.map((t, i) => ({
                value: t.turnSeq,
                label: `第 ${i + 1} 轮 · ${t.text.replace(/\s+/g, ' ').slice(0, 60)}`,
                disabled: savedTurnSeqs.includes(t.turnSeq),
              }))}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              已沉淀的轮次不可重复选择（服务端允许人工多版本，前端按轮次禁用）。
            </Typography.Text>
          </div>
          <div>
            <Typography.Text strong>文档标题（可选）</Typography.Text>
            <Input
              style={{ marginTop: 6 }}
              value={solutionTitle}
              maxLength={120}
              placeholder="默认「{问题摘要} 方案」"
              onChange={(e) => setSolutionTitle(e.target.value)}
            />
          </div>
          <div>
            <Typography.Text strong>关联项目（可选）</Typography.Text>
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              style={{ width: '100%', marginTop: 6 }}
              value={solutionProjectId}
              placeholder="选择项目"
              onChange={setSolutionProjectId}
              options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.id})` }))}
            />
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            正文前会附生成时间、模型、知识库与引用数，末尾附完整证据附录。
          </Typography.Text>
        </Space>
      </Modal>
    </Card>
  )
}
