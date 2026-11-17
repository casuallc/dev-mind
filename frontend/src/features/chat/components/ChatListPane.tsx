// 问答列表侧栏（左栏）：新问答入口 + 搜索 + 状态筛选 + 问答列表。
// REQ-23：状态/关键词过滤均已服务端化（窗口化后客户端再滤会漏数据），这里只剩排序 + 「加载更多」；
// 排序 = 活跃在前 + 创建时间倒序。
import { useMemo } from 'react'
import { Button, Empty, Input, List, Select, Tag, Typography, theme } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import type { ChatSummary } from '../types'
import { stateColor, ACTIVE_STATES, STATE_OPTIONS } from '../../../shared/chat/stateMeta'
import { fmtTime, usageText } from '../../../shared/utils/format'
import { usePermissions } from '../../auth/usePermissions'

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
  hasMore = false,
  loadingMore = false,
  onLoadMore,
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
  /** REQ-23：服务端分页——终态部分还有更早数据时显示「加载更多」 */
  hasMore?: boolean
  loadingMore?: boolean
  onLoadMore?: () => void
}) {
  const { token } = theme.useToken()
  const { canAction } = usePermissions()

  // 关键词/状态过滤都已下沉服务端；这里只排序（活跃在前 + 创建时间倒序）
  const filtered = useMemo(() => {
    return [...chats].sort((a, b) => {
      const aa = ACTIVE_STATES.includes(a.state) ? 0 : 1
      const bb = ACTIVE_STATES.includes(b.state) ? 0 : 1
      return aa - bb || +new Date(b.createdAt) - +new Date(a.createdAt)
    })
  }, [chats])

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
      {canAction('top.chats.action.create') && (
        <Button type="primary" icon={<PlusOutlined />} block onClick={onNew}>
          新问答
        </Button>
      )}
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
        style={{ flex: 1, minHeight: 0, overflow: 'auto' }}
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
              {/* CAP-49：列表里一眼看出这条是节点上的 claude 还是直连的模型端点 */}
              {c.executor === 'MODEL' ? `模型 · ${c.modelEndpointName ?? `端点#${c.modelEndpointId ?? '?'}`}` : 'Agent'}
              {' · '}
              {fmtTime(c.createdAt)}
              {usageText(c) && ` · ${usageText(c)}`}
            </Typography.Text>
          </div>
        )}
      />
      {/* REQ-23：加载更多（loading 防重复点击；仅终态还有更早数据时出现） */}
      {hasMore && onLoadMore && (
        <Button block size="small" loading={loadingMore} onClick={onLoadMore}>
          加载更多
        </Button>
      )}
    </div>
  )
}
