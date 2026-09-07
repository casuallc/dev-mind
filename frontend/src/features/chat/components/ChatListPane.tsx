// 问答列表侧栏（左栏）：新问答入口 + 搜索 + 状态筛选 + 问答列表。
// 过滤逻辑内聚在此，外部只传原始 chats；排序 = 活跃在前 + 创建时间倒序。
import { useMemo } from 'react'
import { Button, Empty, Input, List, Select, Tag, Typography, theme } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import type { ChatSummary } from '../types'
import { stateColor, ACTIVE_STATES, STATE_OPTIONS } from '../../../shared/chat/stateMeta'
import { fmtTime } from '../../../shared/utils/format'

export default function ChatListPane({
  chats,
  loading,
  selectedId,
  onSelect,
  onNew,
  status,
  onStatusChange,
  keyword,
  onKeywordChange,
}: {
  chats: ChatSummary[]
  loading: boolean
  selectedId?: string
  onSelect: (id: string) => void
  onNew: () => void
  status: string
  onStatusChange: (s: string) => void
  keyword: string
  onKeywordChange: (k: string) => void
}) {
  const { token } = theme.useToken()

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase()
    const list = status === 'ALL' ? chats : chats.filter((c) => c.state === status)
    const hit = kw
      ? list.filter(
          (c) =>
            c.id.toLowerCase().includes(kw) ||
            c.title.toLowerCase().includes(kw) ||
            (c.summary ?? '').toLowerCase().includes(kw),
        )
      : list
    return [...hit].sort((a, b) => {
      const aa = ACTIVE_STATES.includes(a.state) ? 0 : 1
      const bb = ACTIVE_STATES.includes(b.state) ? 0 : 1
      return aa - bb || +new Date(b.createdAt) - +new Date(a.createdAt)
    })
  }, [chats, status, keyword])

  return (
    <div
      style={{
        width: 300,
        flexShrink: 0,
        borderRight: '1px solid #f0f0f0',
        marginRight: 12,
        paddingRight: 12,
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
      }}
    >
      <Button type="primary" icon={<PlusOutlined />} block onClick={onNew}>
        新问答
      </Button>
      <Input.Search
        placeholder="搜索标题 / 摘要"
        allowClear
        value={keyword}
        onChange={(e) => onKeywordChange(e.target.value)}
      />
      <Select
        value={status}
        onChange={onStatusChange}
        options={STATE_OPTIONS.map((s) => ({ value: s, label: s }))}
        style={{ width: '100%' }}
      />
      <List
        loading={loading}
        dataSource={filtered}
        style={{ flex: 1, overflow: 'auto', maxHeight: 'calc(100vh - 320px)' }}
        locale={{ emptyText: <Empty description="暂无问答，点上方「新问答」发起" /> }}
        renderItem={(c) => (
          <div
            onClick={() => onSelect(c.id)}
            style={{
              padding: '8px 10px',
              marginBottom: 4,
              borderRadius: 6,
              cursor: 'pointer',
              background: c.id === selectedId ? token.colorPrimaryBg : undefined,
            }}
            onMouseEnter={(e) => {
              if (c.id !== selectedId) e.currentTarget.style.background = token.colorFillQuaternary
            }}
            onMouseLeave={(e) => {
              if (c.id !== selectedId) e.currentTarget.style.background = ''
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
              <Typography.Text strong ellipsis style={{ flex: 1, fontSize: 13 }}>
                {c.title || c.id}
              </Typography.Text>
              <Tag color={stateColor[c.state] ?? 'default'} style={{ marginInlineEnd: 0, flexShrink: 0 }}>
                {c.state}
              </Tag>
            </div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {fmtTime(c.createdAt)}
            </Typography.Text>
          </div>
        )}
      />
    </div>
  )
}
