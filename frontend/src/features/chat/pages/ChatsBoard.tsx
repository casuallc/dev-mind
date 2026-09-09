// AI 问答工作台（CAP-30）：左侧问答列表 + 右侧对话交互（shared ChatPanel, apiBase=/chats）。
// 与项目会话完全分开：无项目/仓库/Diff/详情页，问答在干净沙箱运行，按创建人隔离。
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Badge, Button, Card, Modal, Space, Tag, Typography, message } from 'antd'
import {
  CaretRightOutlined,
  DeleteOutlined,
  PauseOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons'
import { deleteChat, finishChat, killChat, listChats, resumeChat, suspendChat } from '../api'
import type { ChatSummary } from '../types'
import { stateColor, ACTIVE_STATES } from '../../../shared/chat/stateMeta'
import ChatPanel from '../../../shared/chat/ChatPanel'
import type { StreamMeta } from '../../../shared/chat/types'
import ChatListPane from '../components/ChatListPane'
import NewChatDraft from '../components/NewChatDraft'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'

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
  const [loading, setLoading] = useState(false)
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

  const confirmDelete = () => {
    if (!current) return
    Modal.confirm({
      centered: true,
      title: '删除该问答？',
      content: '将杀掉进程（如运行中）并清理沙箱，不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteChat(current.id)
          message.success('已删除')
          if (current.id === selectedId) setSelectedId(undefined)
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
  const canResume = current?.state === 'SUSPENDED'

  return (
    <Card
      title="AI 问答"
      extra={
        <Button icon={<ReloadOutlined />} onClick={() => load()}>
          刷新
        </Button>
      }
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12, flexShrink: 0 }}>
        纯问答不关联项目/仓库，agent 在干净沙箱中运行。左侧选问答、右侧直接对话；「新问答」输入问题即创建。
      </Typography.Paragraph>

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
                      恢复
                    </Button>
                  )}
                  {canSuspend && (
                    <Button size="small" danger icon={<StopOutlined />} onClick={onKill}>
                      终止
                    </Button>
                  )}
                  <Button size="small" danger icon={<DeleteOutlined />} onClick={confirmDelete}>
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
    </Card>
  )
}
