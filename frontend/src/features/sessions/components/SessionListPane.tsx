// 会话列表侧栏（对话视图左栏）：新对话入口 + 搜索 + 状态筛选 + 会话列表。
// 过滤逻辑内聚在此，外部只传原始 sessions；排序 = 活跃在前 + 创建时间倒序。
import { useMemo } from 'react'
import { Button, Empty, Input, List, Select, Tag, Typography, theme } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import type { SessionSummary } from '../types'
import { stateColor, ACTIVE_STATES, STATE_OPTIONS } from '../stateMeta'
import { fmtTime } from '../../../shared/utils/format'

export default function SessionListPane({
  sessions,
  loading,
  selectedId,
  onSelect,
  onNew,
  status,
  onStatusChange,
  keyword,
  onKeywordChange,
}: {
  sessions: SessionSummary[]
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
    const list = status === 'ALL' ? sessions : sessions.filter((s) => s.state === status)
    const hit = kw
      ? list.filter(
          (s) =>
            s.id.toLowerCase().includes(kw) ||
            s.taskSpec.toLowerCase().includes(kw) ||
            (s.summary ?? '').toLowerCase().includes(kw),
        )
      : list
    return [...hit].sort((a, b) => {
      const aa = ACTIVE_STATES.includes(a.state) ? 0 : 1
      const bb = ACTIVE_STATES.includes(b.state) ? 0 : 1
      return aa - bb || +new Date(b.createdAt) - +new Date(a.createdAt)
    })
  }, [sessions, status, keyword])

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
        新对话
      </Button>
      <Input.Search
        placeholder="搜索 ID / 任务 / 摘要"
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
        locale={{ emptyText: <Empty description="暂无会话，点上方「新对话」发起" /> }}
        renderItem={(s) => (
          <div
            onClick={() => onSelect(s.id)}
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
            {/* CAP-31：显式展示会话针对哪些仓库（主库蓝色在前） */}
            {(s.repoNames ?? []).length > 0 && (
              <div style={{ marginTop: 2, display: 'flex', flexWrap: 'wrap', gap: 2 }}>
                {(s.repoNames ?? []).map((n, i) => (
                  <Tag
                    key={`${n}-${i}`}
                    color={i === 0 ? 'blue' : 'default'}
                    style={{ marginInlineEnd: 0, fontSize: 11, lineHeight: '16px', padding: '0 4px' }}
                  >
                    {n}
                  </Tag>
                ))}
              </div>
            )}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {fmtTime(s.createdAt)}
            </Typography.Text>
          </div>
        )}
      />
    </div>
  )
}
