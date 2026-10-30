import { Alert, Button, Checkbox, Drawer, Space, Tag, Tree, Typography, message } from 'antd'
import type { TreeDataNode, TreeProps } from 'antd'
import { useEffect, useMemo, useState } from 'react'
import { fetchMenuCatalog, saveMenuGrants } from '../api'
import type { MenuItem, RoleView } from '../types'
import { usePermissions } from '../usePermissions'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'
import { showError } from '../../../shared/utils/showError'

const ZONE_SECTIONS = [
  { zone: 'TOP', title: '工作台顶部导航' },
  { zone: 'PROJECT', title: '项目二级页签' },
  { zone: 'ADMIN', title: '后台侧边菜单' },
]

const TYPE_ORDER: Record<string, number> = { MENU: 0, TAB: 1, ACTION: 2 }

interface RoleMenuDrawerProps {
  role: RoleView | null
  onClose: () => void
  onSaved: () => void
}

function nodeTag(item: MenuItem) {
  if (item.nodeType === 'TAB') return <Tag color="blue">页签</Tag>
  if (item.nodeType === 'ACTION') {
    return item.writeAction ? <Tag color="orange">写操作</Tag> : <Tag>操作</Tag>
  }
  return null
}

function nodeTitle(item: MenuItem) {
  return (
    <Space size={6}>
      <span>{item.title}</span>
      <Typography.Text type="secondary" code>
        {item.menuKey}
      </Typography.Text>
      {nodeTag(item)}
    </Space>
  )
}

/**
 * CAP-61/CAP-103 角色权限编辑：菜单 → 页签 → 按钮级联勾选，保存即全量覆盖该角色授权。
 * 勾选子节点自动补齐祖先；取消父节点连同其下页签/按钮一起取消。
 */
export default function RoleMenuDrawer({ role, onClose, onSaved }: RoleMenuDrawerProps) {
  const [catalog, setCatalog] = useState<MenuItem[]>([])
  const [checked, setChecked] = useState<Set<string>>(new Set())
  const [expandedKeys, setExpandedKeys] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const { canAction } = usePermissions()
  const canSave = canAction('admin.users.action.grant-menu')

  useEffect(() => {
    if (!role) return
    setChecked(new Set(role.menuKeys))
    setLoading(true)
    fetchMenuCatalog()
      .then((items) => {
        setCatalog(items)
        setExpandedKeys(items.filter((m) => m.nodeType === 'MENU' && !m.retired).map((m) => m.menuKey))
      })
      .catch((e) => showError(e, '加载权限目录失败'))
      .finally(() => setLoading(false))
  }, [role])

  const active = useMemo(() => catalog.filter((m) => !m.retired), [catalog])
  const activeKeys = useMemo(() => new Set(active.map((m) => m.menuKey)), [active])
  const childrenByParent = useMemo(() => {
    const map = new Map<string, MenuItem[]>()
    for (const item of active) {
      if (!item.parentKey) continue
      const list = map.get(item.parentKey) ?? []
      list.push(item)
      map.set(item.parentKey, list)
    }
    for (const list of map.values()) {
      list.sort((a, b) => (TYPE_ORDER[a.nodeType] ?? 9) - (TYPE_ORDER[b.nodeType] ?? 9) || a.sortNo - b.sortNo)
    }
    return map
  }, [active])
  const parentByKey = useMemo(() => {
    const map = new Map<string, string>()
    for (const item of active) {
      if (item.parentKey) map.set(item.menuKey, item.parentKey)
    }
    return map
  }, [active])

  const descendantsOf = (key: string): string[] => {
    const out: string[] = []
    const walk = (parent: string) => {
      for (const child of childrenByParent.get(parent) ?? []) {
        out.push(child.menuKey)
        walk(child.menuKey)
      }
    }
    walk(key)
    return out
  }

  const zoneMenus = (zone: string) =>
    active
      .filter((m) => m.zone === zone && m.nodeType === 'MENU')
      .sort((a, b) => a.sortNo - b.sortNo)

  const treeDataOf = (menus: MenuItem[]): TreeDataNode[] =>
    menus.map((menu) => {
      const children = childrenByParent.get(menu.menuKey) ?? []
      return {
        key: menu.menuKey,
        title: nodeTitle(menu),
        children: children.length
          ? children.map((child) => ({ key: child.menuKey, title: nodeTitle(child), isLeaf: true }))
          : undefined,
      }
    })

  const onCheck: TreeProps['onCheck'] = (keys) => {
    const next = new Set((Array.isArray(keys) ? keys : keys.checked).map(String))
    const changed =
      [...next].find((k) => !checked.has(k)) ?? [...checked].find((k) => !next.has(k))
    if (!changed) {
      setChecked(next)
      return
    }
    if (next.has(changed)) {
      for (const key of descendantsOf(changed)) next.add(key)
      let cursor = parentByKey.get(changed)
      while (cursor) {
        next.add(cursor)
        cursor = parentByKey.get(cursor)
      }
    } else {
      for (const key of descendantsOf(changed)) next.delete(key)
    }
    setChecked(next)
  }

  const zoneKeysOf = (menus: MenuItem[]) =>
    menus.flatMap((menu) => [menu.menuKey, ...descendantsOf(menu.menuKey)])

  const toggleZone = (zoneKeys: string[], allChecked: boolean) => {
    setChecked((prev) => {
      const next = new Set(prev)
      if (allChecked) zoneKeys.forEach((k) => next.delete(k))
      else zoneKeys.forEach((k) => next.add(k))
      return next
    })
  }

  const halfCheckedKeys = useMemo(() => {
    const half: string[] = []
    for (const [parent, children] of childrenByParent) {
      if (checked.has(parent)) continue
      if (children.some((child) => checked.has(child.menuKey))) half.push(parent)
    }
    return half
  }, [childrenByParent, checked])

  const onSave = async () => {
    if (!role) return
    setSaving(true)
    try {
      await saveMenuGrants(role.code, [...checked].filter((key) => activeKeys.has(key)))
      message.success(`已保存「${role.name}」的权限`)
      onSaved()
      onClose()
    } catch (e) {
      showError(e, '保存权限失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Drawer
      title={role ? `角色权限：${role.name}（${role.code}）` : '角色权限'}
      open={!!role}
      onClose={onClose}
      width={DRAWER_DEFAULT}
      destroyOnHidden
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          {canSave && (
            <Button type="primary" loading={saving} disabled={loading} onClick={onSave}>
              保存
            </Button>
          )}
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="勾选菜单、页签与按钮：未勾选的页签/按钮在前端隐藏，已登记的写接口由后端同步拦截；基础权限为只读的角色仍不可执行写操作。"
      />
      {ZONE_SECTIONS.map(({ zone, title }) => {
        const menus = zoneMenus(zone)
        const zoneKeys = zoneKeysOf(menus)
        const allChecked = zoneKeys.length > 0 && zoneKeys.every((k) => checked.has(k))
        const someChecked = zoneKeys.some((k) => checked.has(k))
        return (
          <div key={zone} style={{ marginBottom: 24 }}>
            <Space style={{ marginBottom: 8 }} size={12}>
              <Typography.Title level={5} style={{ margin: 0 }}>
                {title}
              </Typography.Title>
              <Checkbox
                checked={allChecked}
                indeterminate={!allChecked && someChecked}
                disabled={loading || zoneKeys.length === 0}
                onChange={() => toggleZone(zoneKeys, allChecked)}
              >
                全选
              </Checkbox>
            </Space>
            <Tree
              checkable
              checkStrictly
              selectable={false}
              blockNode
              disabled={loading}
              treeData={treeDataOf(menus)}
              checkedKeys={{ checked: [...checked], halfChecked: halfCheckedKeys }}
              expandedKeys={expandedKeys}
              onExpand={(keys) => setExpandedKeys(keys.map(String))}
              onCheck={onCheck}
            />
          </div>
        )
      })}
    </Drawer>
  )
}
