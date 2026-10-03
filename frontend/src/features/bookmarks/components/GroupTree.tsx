import { Button, Dropdown, Empty, Tooltip, Tree } from 'antd'
import type { TreeDataNode, TreeProps } from 'antd'
import {
  AppstoreOutlined,
  DeleteOutlined,
  EditOutlined,
  FolderAddOutlined,
  FolderOpenOutlined,
  FolderOutlined,
  InboxOutlined,
  MoreOutlined,
  ShareAltOutlined,
} from '@ant-design/icons'
import { useMemo, useRef, useState } from 'react'
import type { CSSProperties, Key, ReactNode } from 'react'
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
  /** 拖拽落点结算出的新父级（null = 顶级）；后端 PUT 有成环校验兜底，这里只做交互 */
  onMove: (g: BookmarkGroup, parentId: number | null) => void
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
  // 不 flex:1：短名称时计数紧跟名字（全部收藏（2）），长名称只截断自身、计数不被压缩
  flex: '0 1 auto',
  minWidth: 0,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const iconStyle: CSSProperties = { flexShrink: 0, color: 'inherit', opacity: 0.75 }

const countStyle: CSSProperties = { flexShrink: 0, fontSize: 12, color: '#8c8c8c' }

/** 折叠状态持久化（记「收起的」而不是「展开的」：默认全展开，新建分组不会被意外藏住） */
const COLLAPSED_KEY = 'bookmark.groupTree.collapsed'

function loadCollapsed(): number[] {
  try {
    const v: unknown = JSON.parse(localStorage.getItem(COLLAPSED_KEY) ?? '[]')
    return Array.isArray(v) ? v.filter((x): x is number => typeof x === 'number') : []
  } catch {
    return []
  }
}

/** 在分组树里按 id 深查（页面侧移动成功的提示文案也要查新父级名字，故导出） */
export function findGroup(groups: BookmarkGroup[], id: number): BookmarkGroup | null {
  for (const g of groups) {
    if (g.id === id) return g
    const hit = findGroup(g.children, id)
    if (hit) return hit
  }
  return null
}

/** id 是否落在 root 的子树里（含 root 自身）——拖拽禁止把分组拖进自己的子树 */
function inSubtree(root: BookmarkGroup, id: number): boolean {
  return root.id === id || root.children.some((c) => inSubtree(c, id))
}

/** 全部收藏/默认分组两个虚拟行：不进 Tree（不可拖、无折叠箭头），保留原来的整行样式 */
function Row({
  active,
  icon,
  name,
  count,
  actions,
  onClick,
}: {
  active: boolean
  icon: ReactNode
  name: string
  count?: number
  actions?: ReactNode
  onClick: () => void
}) {
  return (
    <div className="bm-group-row" style={rowStyle(active)} onClick={onClick}>
      <span style={iconStyle}>{icon}</span>
      <Tooltip title={name} mouseEnterDelay={0.4}>
        <span style={nameStyle}>{name}</span>
      </Tooltip>
      {count != null && count > 0 && <span style={countStyle}>（{count}）</span>}
      <span style={{ flex: 1 }} />
      {actions}
    </div>
  )
}

/**
 * 分组树侧栏（FR-02）。分组用 antd Tree 渲染：支持折叠/展开（折叠集本地记忆）、
 * 拖拽更改父分组（禁拖入自己的子树，后端成环校验兜底）；每行的「…」收新建子分组/分享/重命名/删除。
 * 收藏的批量转移在表格工具栏里做，不在此处。
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
  onMove,
}: Props) {
  const [collapsed, setCollapsed] = useState<number[]>(loadCollapsed)
  const dragIdRef = useRef<number | null>(null)

  const { allIds, parentOf } = useMemo(() => {
    const allIds: number[] = []
    const parentOf = new Map<number, number | null>()
    const walk = (gs: BookmarkGroup[], parent: number | null) => {
      for (const g of gs) {
        allIds.push(g.id)
        parentOf.set(g.id, parent)
        walk(g.children, g.id)
      }
    }
    walk(groups, null)
    return { allIds, parentOf }
  }, [groups])

  const persistCollapsed = (next: number[]) => {
    setCollapsed(next)
    localStorage.setItem(COLLAPSED_KEY, JSON.stringify(next))
  }

  /** 点行即折叠/展开（无下拉箭头；有子分组才 toggle，叶子分组点了只是选中） */
  const toggleCollapsed = (id: number) =>
    persistCollapsed(collapsed.includes(id) ? collapsed.filter((x) => x !== id) : [...collapsed, id])

  const expandedKeys = allIds.filter((id) => !collapsed.includes(id))
  const onExpand = (keys: Key[]) => persistCollapsed(allIds.filter((id) => !keys.includes(id)))

  const actionsFor = (g: BookmarkGroup) => (
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
  )

  const titleOf = (g: BookmarkGroup): ReactNode => {
    const hasKids = g.children.length > 0
    const expanded = hasKids && !collapsed.includes(g.id)
    return (
      // 点击行 = 选中（冒泡给 Tree 的 onSelect）；有子分组时同时折叠/展开
      <div
        className="bm-group-row"
        style={{ display: 'flex', alignItems: 'center', gap: 6, height: 28, minWidth: 0 }}
        onClick={() => {
          if (hasKids) toggleCollapsed(g.id)
        }}
      >
        <span style={iconStyle}>{expanded ? <FolderOpenOutlined /> : <FolderOutlined />}</span>
        <Tooltip title={g.name} mouseEnterDelay={0.4}>
          <span style={nameStyle}>{g.name}</span>
        </Tooltip>
        {g.bookmarkCount > 0 && <span style={countStyle}>（{g.bookmarkCount}）</span>}
        <span style={{ flex: 1 }} />
        {actionsFor(g)}
      </div>
    )
  }

  const toNode = (g: BookmarkGroup): TreeDataNode => ({
    key: g.id,
    title: titleOf(g),
    children: g.children.map(toNode),
  })

  // dropPosition 0 = 落进 dropNode 内部；-1/1 = 落在它前/后的缝隙，生效父级是 dropNode 的父级
  const effectiveParentOf = (key: Key, dropPosition: number): number | null =>
    dropPosition === 0 ? Number(key) : parentOf.get(Number(key)) ?? null

  const allowDrop: TreeProps['allowDrop'] = ({ dropNode, dropPosition }) => {
    const dragId = dragIdRef.current
    if (dragId == null) return false
    const parent = effectiveParentOf(dropNode.key, dropPosition)
    if (parent == null) return true
    const drag = findGroup(groups, dragId)
    return drag != null && !inSubtree(drag, parent)
  }

  const onDrop: TreeProps['onDrop'] = (info) => {
    const drag = findGroup(groups, Number(info.dragNode.key))
    const target = findGroup(groups, Number(info.node.key))
    dragIdRef.current = null
    if (!drag || !target) return
    const parentId = info.dropToGap ? (target.parentId ?? null) : target.id
    if (parentId === (drag.parentId ?? null)) return // 原地松手不算移动
    // 拖进收起的新父级时先展开，让节点立刻可见
    if (parentId != null && collapsed.includes(parentId)) {
      persistCollapsed(collapsed.filter((id) => id !== parentId))
    }
    onMove(drag, parentId)
  }

  return (
    <div
      style={{
        width: 264,
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
          <>
            <Tree
              className="bm-group-tree"
              blockNode
              // expandedKeys 是「全部 − 折叠集」：收起父级时其子级键仍在集合里。
              // rc-tree 挂载时 defaultExpandParent（默认 true）会把这些子级的父级强行
              // 展开（getDerivedStateFromProps 的 conductExpandParent），折叠记忆刷新即失效
              autoExpandParent={false}
              defaultExpandParent={false}
              treeData={groups.map(toNode)}
              expandedKeys={expandedKeys}
              onExpand={onExpand}
              selectedKeys={selected.kind === 'group' ? [selected.id] : []}
              // 点已选中的行 antd 会回空 keys——忽略，保持当前过滤不丢
              onSelect={(keys) => {
                const k = keys[0]
                if (k != null) onSelect({ kind: 'group', id: Number(k) })
              }}
              draggable={{ icon: false }}
              allowDrop={allowDrop}
              onDragStart={({ node }) => {
                dragIdRef.current = Number(node.key)
              }}
              onDragEnd={() => {
                dragIdRef.current = null
              }}
              onDrop={onDrop}
            />
            <div
              className="bm-new-group"
              style={{
                marginTop: 4,
                height: 32,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                border: '1px dashed #d9d9d9',
                borderRadius: 6,
                cursor: 'pointer',
                color: '#8c8c8c',
              }}
              onClick={() => onCreate(null)}
            >
              新建分组
            </div>
          </>
        )}
      </div>
    </div>
  )
}
