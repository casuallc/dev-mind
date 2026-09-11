// AI 问答工作台（CAP-30）：默认对话视图（左侧问答列表 + 右侧对话交互，shared ChatPanel, apiBase=/chats），可切换表格列表视图。
// 与项目会话完全分开：无项目/仓库/Diff/详情页，问答在干净沙箱运行，按创建人隔离。
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Badge, Button, Card, Input, Modal, Segmented, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  CaretRightOutlined,
  DeleteOutlined,
  PauseOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons'
import { deleteChat, finishChat, killChat, listChats, resumeChat, suspendChat } from '../api'
import type { ChatSummary } from '../types'
import { stateColor, ACTIVE_STATES, STATE_OPTIONS } from '../../../shared/chat/stateMeta'
import ChatPanel from '../../../shared/chat/ChatPanel'
import type { StreamMeta } from '../../../shared/chat/types'
import ChatListPane from '../components/ChatListPane'
import NewChatDraft from '../components/NewChatDraft'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
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
  }, [])

  // 首次加载后自动选中：有问答选排序第一个，否则直接进入新问答草稿态
  useEffect(() => {
    if (autoPickedRef.current || loading) return
    autoPickedRef.current = true
    if (chats.length > 0) setSelectedId(sortForBoard(chats)[0].id)
    else setDraft(true)
  }, [chats, loading])

  // 草稿创建成功后：先等列表刷新拿到新问答行，再选中——否则「消失兜底」effect 会误清新 id
  const onDraftCreated = useCallback(
    async (c: ChatSummary) => {
      await load()
      setDraft(false)
      setSelectedId(c.id)
    },
    [load],
  )

  // 选中的问答被删除/消失时兜底退出选中态
  const current = useMemo(() => chats.find((c) => c.id === selectedId), [chats, selectedId])
  useEffect(() => {
    if (selectedId && !loading && !current) setSelectedId(undefined)
  }, [selectedId, current, loading])

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
      content: '将强杀 agent 进程并清理沙箱（保留问答记录与历史）。',
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

  const canSuspend = !!current && ACTIVE_STATES.includes(current.state)
  // SUSPENDED=恢复（同进程语义）；DONE/FAILED/TERMINATED=继续对话（claude --resume 带历史重拉起）
  const canResume = !!current && ['SUSPENDED', 'DONE', 'FAILED', 'TERMINATED'].includes(current.state)

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
        纯问答不关联项目/仓库，agent 在干净沙箱中运行。左侧选问答、右侧直接对话；「新问答」输入问题即创建。列表视图可按状态筛选、搜索全部问答。
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
              onCreated={onDraftCreated}
              onCancel={
                selectedId || chats.length > 0
                  ? () => {
                      setDraft(false)
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
              <ChatPanel
                key={current.id}
                summary={current}
                apiBase="/chats"
                maxHeight={null}
                allowImages
                onChanged={load}
                onStreamMeta={setStreamMeta}
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
    </Card>
  )
}
