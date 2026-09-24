import { Button, Dropdown, Empty, Space, Tag, Tooltip, Typography } from 'antd'
import {
  DeleteOutlined,
  EditOutlined,
  FolderAddOutlined,
  MoreOutlined,
  ShareAltOutlined,
} from '@ant-design/icons'
import type { BookmarkGroup } from '../types'

/** 侧栏选中项：全部 / 未分组 / 某个分组（点分组 = 看到该子树内的收藏） */
export type GroupSelection = { kind: 'all' } | { kind: 'ungrouped' } | { kind: 'group'; id: number }

interface Props {
  groups: BookmarkGroup[]
  total: number
  ungroupedCount: number
  selected: GroupSelection
  onSelect: (sel: GroupSelection) => void
  onCreate: (parent: BookmarkGroup | null) => void
  onRename: (g: BookmarkGroup) => void
  onDelete: (g: BookmarkGroup) => void
  onShare: (g: BookmarkGroup) => void
}

const rowStyle = (active: boolean): React.CSSProperties => ({
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  padding: '4px 8px',
  borderRadius: 6,
  cursor: 'pointer',
  background: active ? '#e6f4ff' : undefined,
  color: active ? '#1677ff' : undefined,
})

const nameStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

/**
 * 分组树侧栏（FR-02）。分组按住一层层渲染（文档建议两级内使用），
 * 每行的「…」收新建子分组/分享分组/重命名/删除；收藏的批量转移在表格工具栏里做，不在此处。
 */
export default function GroupTree({
  groups,
  total,
  ungroupedCount,
  selected,
  onSelect,
  onCreate,
  onRename,
  onDelete,
  onShare,
}: Props) {
  const renderNode = (g: BookmarkGroup, depth: number) => {
    const active = selected.kind === 'group' && selected.id === g.id
    return (
      <div key={g.id}>
        <div
          style={{ ...rowStyle(active), paddingLeft: 8 + depth * 14 }}
          onClick={() => onSelect({ kind: 'group', id: g.id })}
        >
          <Tooltip title={g.name} mouseEnterDelay={0.4}>
            <span style={nameStyle}>{g.name}</span>
          </Tooltip>
          {g.bookmarkCount > 0 && <Tag style={{ marginInlineEnd: 0 }}>{g.bookmarkCount}</Tag>}
          <Dropdown
            trigger={['click']}
            menu={{
              items: [
                { key: 'child', icon: <FolderAddOutlined />, label: '新建子分组' },
                { key: 'share', icon: <ShareAltOutlined />, label: '分享分组' },
                { key: 'rename', icon: <EditOutlined />, label: '重命名' },
                { type: 'divider' },
                { key: 'delete', icon: <DeleteOutlined />, label: '删除分组', danger: true },
              ],
              onClick: ({ key, domEvent }) => {
                domEvent.stopPropagation()
                if (key === 'child') onCreate(g)
                else if (key === 'share') onShare(g)
                else if (key === 'rename') onRename(g)
                else if (key === 'delete') onDelete(g)
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
        {g.children.map((c) => renderNode(c, depth + 1))}
      </div>
    )
  }

  return (
    <div style={{ width: 220, flexShrink: 0, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <div style={{ flexShrink: 0 }}>
        <div style={rowStyle(selected.kind === 'all')} onClick={() => onSelect({ kind: 'all' })}>
          <span style={nameStyle}>全部收藏</span>
          <Tag style={{ marginInlineEnd: 0 }}>{total}</Tag>
        </div>
        <div style={rowStyle(selected.kind === 'ungrouped')} onClick={() => onSelect({ kind: 'ungrouped' })}>
          <span style={nameStyle}>未分组</span>
          {ungroupedCount > 0 && <Tag style={{ marginInlineEnd: 0 }}>{ungroupedCount}</Tag>}
        </div>
        <Space style={{ display: 'flex', justifyContent: 'space-between', margin: '8px 0 4px' }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            分组
          </Typography.Text>
          <Button type="link" size="small" icon={<FolderAddOutlined />} onClick={() => onCreate(null)}>
            新建
          </Button>
        </Space>
      </div>
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        {groups.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="用分组归类常用入口"
            style={{ marginTop: 8 }}
          >
            <Button size="small" onClick={() => onCreate(null)}>
              新建分组
            </Button>
          </Empty>
        ) : (
          groups.map((g) => renderNode(g, 0))
        )}
      </div>
    </div>
  )
}
