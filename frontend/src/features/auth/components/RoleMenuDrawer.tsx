import {
  Alert,
  Button,
  Checkbox,
  Drawer,
  Empty,
  Flex,
  Input,
  Modal,
  Space,
  Table,
  Tag,
  Typography,
  message,
  theme,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { SearchOutlined } from '@ant-design/icons'
import { useCallback, useEffect, useMemo, useState } from 'react'
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

/**
 * CAP-61/CAP-103 角色权限编辑：左侧菜单列表 + 右侧页签/按钮表格。
 * 勾选页签/按钮自动补齐祖先菜单；取消「菜单可见」连同其下页签/按钮一起取消。
 * 保存即全量覆盖该角色授权。
 */
export default function RoleMenuDrawer({ role, onClose, onSaved }: RoleMenuDrawerProps) {
  const [catalog, setCatalog] = useState<MenuItem[]>([])
  const [checked, setChecked] = useState<Set<string>>(new Set())
  const [initial, setInitial] = useState<Set<string>>(new Set())
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [onlyGranted, setOnlyGranted] = useState(false)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const { canAction } = usePermissions()
  const canSave = canAction('admin.users.action.grant-menu')
  const { token } = theme.useToken()

  useEffect(() => {
    if (!role) return
    setSearch('')
    setOnlyGranted(false)
    setSelectedKey(null)
    setLoading(true)
    fetchMenuCatalog()
      .then((items) => {
        const activeKeys = new Set(items.filter((m) => !m.retired).map((m) => m.menuKey))
        const parentOf = new Map<string, string>()
        for (const item of items) {
          if (!item.retired && item.parentKey) parentOf.set(item.menuKey, item.parentKey)
        }
        // 历史数据可能只有子节点授权：补齐祖先，避免打开即显示为「未保存变更」
        const keys = new Set<string>()
        for (const key of role.menuKeys) {
          if (!activeKeys.has(key)) continue
          let cursor: string | undefined = key
          while (cursor && activeKeys.has(cursor) && !keys.has(cursor)) {
            keys.add(cursor)
            cursor = parentOf.get(cursor)
          }
        }
        setCatalog(items)
        setChecked(new Set(keys))
        setInitial(new Set(keys))
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
  const descendantsOf = useCallback((key: string): string[] => {
    const out: string[] = []
    const walk = (parent: string) => {
      for (const child of childrenByParent.get(parent) ?? []) {
        out.push(child.menuKey)
        walk(child.menuKey)
      }
    }
    walk(key)
    return out
  }, [childrenByParent])

  const activeChecked = useMemo(
    () => new Set([...checked].filter((key) => activeKeys.has(key))),
    [checked, activeKeys],
  )
  const changedCount = useMemo(() => {
    let count = 0
    for (const key of activeChecked) if (!initial.has(key)) count++
    for (const key of initial) if (!activeChecked.has(key)) count++
    return count
  }, [activeChecked, initial])

  const updateChecked = (mutate: (next: Set<string>) => void) => {
    setChecked((prev) => {
      const next = new Set(prev)
      mutate(next)
      return next
    })
  }

  const toggleKeys = (keys: string[], on: boolean) => {
    updateChecked((next) => keys.forEach((key) => (on ? next.add(key) : next.delete(key))))
  }

  const toggleChild = (key: string, on: boolean) => {
    updateChecked((next) => {
      if (on) {
        next.add(key)
        let cursor = parentByKey.get(key)
        while (cursor) {
          next.add(cursor)
          cursor = parentByKey.get(cursor)
        }
      } else {
        next.delete(key)
        for (const descendant of descendantsOf(key)) next.delete(descendant)
      }
    })
  }

  const toggleMenu = (menuKey: string, on: boolean) => {
    updateChecked((next) => {
      if (on) {
        next.add(menuKey)
      } else {
        next.delete(menuKey)
        for (const descendant of descendantsOf(menuKey)) next.delete(descendant)
      }
    })
  }

  const toggleWholeMenu = (menuKey: string, on: boolean) => {
    toggleKeys([menuKey, ...descendantsOf(menuKey)], on)
  }

  const statsOf = (menuKey: string) => {
    const keys = [menuKey, ...descendantsOf(menuKey)]
    return { total: keys.length, granted: keys.filter((key) => checked.has(key)).length }
  }

  const filteredMenus = useMemo(() => {
    const q = search.trim().toLowerCase()
    return active
      .filter((m) => m.nodeType === 'MENU')
      .filter((m) => !q || m.title.toLowerCase().includes(q) || m.menuKey.toLowerCase().includes(q))
      .filter((m) => !onlyGranted || checked.has(m.menuKey) || descendantsOf(m.menuKey).some((key) => checked.has(key)))
      .sort((a, b) => a.sortNo - b.sortNo)
  }, [active, search, onlyGranted, checked, descendantsOf])

  const selected = useMemo(
    () => filteredMenus.find((m) => m.menuKey === selectedKey) ?? filteredMenus[0] ?? null,
    [filteredMenus, selectedKey],
  )
  const rows = useMemo(
    () => (selected ? [selected, ...(childrenByParent.get(selected.menuKey) ?? [])] : []),
    [selected, childrenByParent],
  )
  const selectedAllChecked = rows.length > 0 && rows.every((row) => checked.has(row.menuKey))

  const columns: ColumnsType<MenuItem> = [
    {
      title: '类型',
      dataIndex: 'nodeType',
      width: 76,
      render: (_, row) => {
        if (row.nodeType === 'TAB') return <Tag color="blue">页签</Tag>
        if (row.nodeType === 'ACTION') return row.writeAction ? <Tag color="orange">写操作</Tag> : <Tag>操作</Tag>
        return <Tag>菜单</Tag>
      },
    },
    {
      title: '名称',
      dataIndex: 'title',
      render: (title: string, row) =>
        row.nodeType === 'MENU' ? (
          <Space size={6}>
            <span>{title}</span>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              菜单可见
            </Typography.Text>
          </Space>
        ) : (
          title
        ),
    },
    {
      title: '权限编码',
      dataIndex: 'menuKey',
      width: 300,
      ellipsis: true,
      render: (key: string) => (
        <Typography.Text code style={{ fontSize: 12 }}>
          {key}
        </Typography.Text>
      ),
    },
    {
      title: '授权',
      key: 'granted',
      width: 64,
      align: 'center',
      render: (_, row) => (
        <Checkbox
          checked={checked.has(row.menuKey)}
          disabled={loading || !canSave}
          onChange={(e) =>
            row.nodeType === 'MENU'
              ? toggleMenu(row.menuKey, e.target.checked)
              : toggleChild(row.menuKey, e.target.checked)
          }
        />
      ),
    },
  ]

  const requestClose = () => {
    if (saving) return
    if (changedCount === 0) {
      onClose()
      return
    }
    Modal.confirm({
      centered: true,
      title: '放弃未保存的权限修改？',
      content: '本次勾选尚未保存，关闭后不会生效。',
      okText: '放弃修改',
      okButtonProps: { danger: true },
      cancelText: '继续编辑',
      onOk: onClose,
    })
  }

  const onSave = async () => {
    if (!role) return
    setSaving(true)
    try {
      await saveMenuGrants(role.code, [...activeChecked])
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
      onClose={requestClose}
      width={DRAWER_DEFAULT}
      destroyOnHidden
      footer={
        <Flex justify="space-between" align="center">
          <Typography.Text type="secondary">
            已选 {activeChecked.size} 项
            {changedCount > 0 ? ` · 未保存 ${changedCount} 项` : ''}
          </Typography.Text>
          <Space>
            <Button onClick={requestClose}>取消</Button>
            {canSave && (
              <Button type="primary" loading={saving} disabled={loading} onClick={onSave}>
                保存
              </Button>
            )}
          </Space>
        </Flex>
      }
      styles={{ body: { display: 'flex', flexDirection: 'column', overflow: 'hidden', paddingTop: 16 } }}
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="左侧选菜单，右侧勾选页签与按钮：未勾选的页签/按钮在前端隐藏，已登记的写接口由后端同步拦截；基础权限为只读的角色仍不可执行写操作。"
      />
      <div style={{ display: 'flex', gap: 16, flex: 1, minHeight: 0 }}>
        <div style={{ width: 268, flexShrink: 0, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder="搜索菜单名称或编码"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <Checkbox
            style={{ marginTop: 10 }}
            checked={onlyGranted}
            onChange={(e) => setOnlyGranted(e.target.checked)}
          >
            只看已授权
          </Checkbox>
          <div style={{ flex: 1, overflow: 'auto', marginTop: 12, paddingRight: 4 }}>
            {ZONE_SECTIONS.map(({ zone, title }) => {
              const zoneMenus = filteredMenus.filter((m) => m.zone === zone)
              if (zoneMenus.length === 0) return null
              const zoneKeys = zoneMenus.flatMap((m) => [m.menuKey, ...descendantsOf(m.menuKey)])
              const allChecked = zoneKeys.every((key) => checked.has(key))
              const someChecked = zoneKeys.some((key) => checked.has(key))
              return (
                <div key={zone} style={{ marginBottom: 16 }}>
                  <Flex justify="space-between" align="center" style={{ padding: '0 8px', marginBottom: 4 }}>
                    <Typography.Text strong type="secondary" style={{ fontSize: 12 }}>
                      {title}
                    </Typography.Text>
                    <Checkbox
                      checked={allChecked}
                      indeterminate={!allChecked && someChecked}
                      disabled={loading || !canSave}
                      onChange={() => toggleKeys(zoneKeys, !allChecked)}
                    >
                      全选
                    </Checkbox>
                  </Flex>
                  {zoneMenus.map((menu) => {
                    const { granted, total } = statsOf(menu.menuKey)
                    const isActive = selected?.menuKey === menu.menuKey
                    const partial = granted > 0 && granted < total
                    return (
                      <Button
                        key={menu.menuKey}
                        type="text"
                        block
                        onClick={() => setSelectedKey(menu.menuKey)}
                        style={{
                          height: 32,
                          paddingInline: 8,
                          marginBottom: 2,
                          background: isActive ? token.colorPrimaryBg : undefined,
                        }}
                      >
                        <Flex justify="space-between" align="center" gap={8} style={{ width: '100%' }}>
                          <span
                            style={{
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                              color: isActive ? token.colorPrimary : undefined,
                            }}
                          >
                            {menu.title}
                          </span>
                          <Space size={4}>
                            {partial && (
                              <span
                                style={{
                                  width: 6,
                                  height: 6,
                                  borderRadius: '50%',
                                  background: token.colorWarning,
                                }}
                              />
                            )}
                            <Typography.Text
                              type="secondary"
                              style={{ fontSize: 12, color: isActive ? token.colorPrimary : undefined }}
                            >
                              {granted}/{total}
                            </Typography.Text>
                          </Space>
                        </Flex>
                      </Button>
                    )
                  })}
                </div>
              )
            })}
            {filteredMenus.length === 0 && (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有匹配的菜单" style={{ marginTop: 40 }} />
            )}
          </div>
        </div>
        <div
          style={{
            flex: 1,
            minWidth: 0,
            minHeight: 0,
            overflow: 'auto',
            paddingLeft: 16,
            borderLeft: `1px solid ${token.colorSplit}`,
          }}
        >
          {selected ? (
            <>
              <Flex justify="space-between" align="center" style={{ marginBottom: 12 }}>
                <Space size={8} wrap>
                  <Typography.Text strong style={{ fontSize: 16 }}>
                    {selected.title}
                  </Typography.Text>
                  <Typography.Text type="secondary" code>
                    {selected.menuKey}
                  </Typography.Text>
                </Space>
                <Button
                  size="small"
                  disabled={loading || !canSave}
                  onClick={() => toggleWholeMenu(selected.menuKey, !selectedAllChecked)}
                >
                  {selectedAllChecked ? '清空本菜单' : '全选本菜单'}
                </Button>
              </Flex>
              <Table
                rowKey="menuKey"
                size="small"
                pagination={false}
                columns={columns}
                dataSource={rows}
              />
            </>
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有匹配的菜单" style={{ marginTop: 80 }} />
          )}
        </div>
      </div>
    </Drawer>
  )
}
