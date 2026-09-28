import { Button, Dropdown, Empty, Tooltip } from 'antd'
import {
  AppstoreOutlined,
  DeleteOutlined,
  EditOutlined,
  FolderAddOutlined,
  FolderOutlined,
  InboxOutlined,
  MoreOutlined,
  ShareAltOutlined,
} from '@ant-design/icons'
import type { CSSProperties, ReactNode } from 'react'
import type { BookmarkGroup } from '../types'

/** 侧栏选中项：全部 / 默认分组（group_id 为空的虚拟分组）/ 某个分组（点分组 = 看到该子树内的收藏） */
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

const rowStyle = (active: boolean): CSSProperties => ({
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  height: 32,
  padding: '0 8px',
  borderRadius: 6,
  cursor: 'pointer',
  background: active ? '#e6f4ff' : undefined,
  color: active ? '#1677ff' : undefined,
})

const nameStyle: CSSProperties = {
  flex: 1,
  minWidth: 0,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const iconStyle: CSSProperties = { flexShrink: 0, color: 'inherit', opacity: 0.75 }

const countStyle: CSSProperties = { flexShrink: 0, fontSize: 12, color: '#8c8c8c' }

/** 侧栏一行：图标 + 名称 + 计数 + 尾随操作（hover 才显示，见 index.css 的 bm-group-row） */
function Row({
  active,
  icon,
  name,
  count,
  actions,
  indent,
  onClick,
}: {
  active: boolean
  icon: ReactNode
  name: string
  count?: number
  actions?: ReactNode
  indent?: number
  onClick: () => void
}) {
  return (
    <div
      className="bm-group-row"
      style={{ ...rowStyle(active), paddingLeft: 8 + (indent ?? 0) * 14 }}
      onClick={onClick}
    >
      <span style={iconStyle}>{icon}</span>
      <Tooltip title={name} mouseEnterDelay={0.4}>
        <span style={nameStyle}>{name}</span>
      </Tooltip>
      {count != null && count > 0 && <span style={countStyle}>{count}</span>}
      {actions}
    </div>
  )
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
  const renderNode = (g: BookmarkGroup, depth: number) => (
    <div key={g.id}>
      <Row
        active={selected.kind === 'group' && selected.id === g.id}
        icon={<FolderOutlined />}
        name={g.name}
        count={g.bookmarkCount}
        indent={depth}
        onClick={() => onSelect({ kind: 'group', id: g.id })}
        actions={
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
              className="bm-row-actions"
              type="text"
              size="small"
              icon={<MoreOutlined />}
              onClick={(e) => e.stopPropagation()}
            />
          </Dropdown>
        }
      />
      {g.children.map((c) => renderNode(c, depth + 1))}
    </div>
  )

  return (
    <div
      style={{
        width: 220,
        flexShrink: 0,
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
        borderRight: '1px solid #f0f0f0',
        paddingRight: 12,
      }}
    >
      <div style={{ flexShrink: 0 }}>
        <Row
          active={selected.kind === 'all'}
          icon={<AppstoreOutlined />}
          name="全部收藏"
          count={total}
          onClick={() => onSelect({ kind: 'all' })}
        />
        <Row
          active={selected.kind === 'ungrouped'}
          icon={<InboxOutlined />}
          name="默认分组"
          count={ungroupedCount}
          onClick={() => onSelect({ kind: 'ungrouped' })}
        />
      </div>
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        {groups.map((g) => renderNode(g, 0))}
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
          <div
            className="bm-group-row"
            style={{ ...rowStyle(false), color: '#8c8c8c' }}
            onClick={() => onCreate(null)}
          >
            <span style={iconStyle}>
              <FolderAddOutlined />
            </span>
            <span style={nameStyle}>新建分组</span>
          </div>
        )}
      </div>
    </div>
  )
}
