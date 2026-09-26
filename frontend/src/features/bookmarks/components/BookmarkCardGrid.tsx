import { Button, Dropdown, Empty, Spin, Tag, Tooltip, Typography } from 'antd'
import { MoreOutlined, PlusOutlined } from '@ant-design/icons'
import type { CSSProperties } from 'react'
import { pagePaneScrollStyle } from '../../../shared/utils/pageLayout'
import type { Bookmark } from '../types'
import StatusDot from './StatusDot'

interface Props {
  rows: Bookmark[]
  loading: boolean
  groupName: (b: Bookmark) => string
  onOpen: (b: Bookmark) => void
  onProbe: (b: Bookmark) => void
  onEdit: (b: Bookmark) => void
  onAccounts: (b: Bookmark) => void
  onShare: (b: Bookmark) => void
  onMove: (b: Bookmark) => void
  onDelete: (b: Bookmark) => void
  onCreate: () => void
}

const cardStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  padding: '10px 12px',
  border: '1px solid #f0f0f0',
  borderRadius: 8,
  cursor: 'pointer',
  background: '#fff',
  transition: 'box-shadow .15s, border-color .15s',
  minWidth: 0,
}

const titleStyle: CSSProperties = {
  flex: 1,
  minWidth: 0,
  fontWeight: 600,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const urlStyle: CSSProperties = {
  fontSize: 12,
  color: '#8c8c8c',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

/**
 * 卡片网格视图（与表格共用一份 rows）：点卡片 = 打开，「⋯」收探测/编辑/账号/分享/转移/删除。
 * 批量操作（批量探测/转移分组）只在表格视图做，卡片不承载选择态。
 */
export default function BookmarkCardGrid({
  rows,
  loading,
  groupName,
  onOpen,
  onProbe,
  onEdit,
  onAccounts,
  onShare,
  onMove,
  onDelete,
  onCreate,
}: Props) {
  if (!loading && rows.length === 0) {
    return (
      <div style={pagePaneScrollStyle}>
        <Empty description="这里还没有收藏" style={{ marginTop: 48 }}>
          <Button type="primary" onClick={onCreate}>
            新建收藏
          </Button>
        </Empty>
      </div>
    )
  }
  return (
    <div style={pagePaneScrollStyle}>
      <Spin spinning={loading}>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))',
            gap: 12,
          }}
        >
          {rows.map((b) => (
            <div
              key={b.id}
              className="bm-card"
              style={cardStyle}
              onClick={() => onOpen(b)}
              title={b.description || undefined}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <StatusDot bookmark={b} />
                <span style={titleStyle}>{b.title}</span>
                <Dropdown
                  trigger={['click']}
                  menu={{
                    items: [
                      { key: 'probe', label: '探测' },
                      { key: 'edit', label: '编辑' },
                      { key: 'accounts', label: `账号（${b.accounts.length}）` },
                      { key: 'share', label: '分享给同事' },
                      { key: 'move', label: '转移分组' },
                      { type: 'divider' },
                      { key: 'delete', label: '删除', danger: true },
                    ],
                    onClick: ({ key, domEvent }) => {
                      domEvent.stopPropagation()
                      if (key === 'probe') onProbe(b)
                      else if (key === 'edit') onEdit(b)
                      else if (key === 'accounts') onAccounts(b)
                      else if (key === 'share') onShare(b)
                      else if (key === 'move') onMove(b)
                      else if (key === 'delete') onDelete(b)
                    },
                  }}
                >
                  <Button
                    type="text"
                    size="small"
                    icon={<MoreOutlined />}
                    onClick={(e) => e.stopPropagation()}
                  />
                </Dropdown>
              </div>
              <div style={urlStyle}>{b.url}</div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 4, minHeight: 22 }}>
                {b.tags.slice(0, 2).map((t) => (
                  <Tag key={t.id} style={{ marginInlineEnd: 0 }}>
                    {t.name}
                  </Tag>
                ))}
                {b.tags.length > 2 && (
                  <Tooltip title={b.tags.slice(2).map((t) => t.name).join('、')}>
                    <Tag style={{ marginInlineEnd: 0 }}>+{b.tags.length - 2}</Tag>
                  </Tooltip>
                )}
                <Typography.Text type="secondary" style={{ fontSize: 12, marginLeft: 'auto' }}>
                  {groupName(b)}
                </Typography.Text>
              </div>
            </div>
          ))}
          <div
            style={{
              ...cardStyle,
              border: '1px dashed #d9d9d9',
              alignItems: 'center',
              justifyContent: 'center',
              color: '#8c8c8c',
              minHeight: 84,
            }}
            onClick={onCreate}
          >
            <PlusOutlined /> 新建收藏
          </div>
        </div>
      </Spin>
    </div>
  )
}
