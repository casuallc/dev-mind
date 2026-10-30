import { Alert, Button, Checkbox, Drawer, Space, Tag, Typography, message } from 'antd'
import { useEffect, useState } from 'react'
import { fetchMenuCatalog, saveMenuGrants } from '../api'
import type { MenuItem, RoleView } from '../types'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'
import { showError } from '../../../shared/utils/showError'

const ZONE_SECTIONS = [
  { zone: 'TOP', title: '工作台顶部导航' },
  { zone: 'PROJECT', title: '项目二级页签' },
  { zone: 'ADMIN', title: '后台侧边菜单' },
]

interface RoleMenuDrawerProps {
  role: RoleView | null
  onClose: () => void
  onSaved: () => void
}

/** 单个角色的菜单可见范围编辑：按 zone 分组勾选，保存即全量覆盖该角色授权。 */
export default function RoleMenuDrawer({ role, onClose, onSaved }: RoleMenuDrawerProps) {
  const [catalog, setCatalog] = useState<MenuItem[]>([])
  const [checked, setChecked] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!role) return
    setChecked(role.menuKeys)
    setLoading(true)
    fetchMenuCatalog()
      .then(setCatalog)
      .catch((e) => showError(e, '加载菜单目录失败'))
      .finally(() => setLoading(false))
  }, [role])

  const active = catalog.filter((m) => !m.retired)

  const toggle = (menuKey: string, on: boolean) => {
    setChecked((prev) => (on ? [...new Set([...prev, menuKey])] : prev.filter((k) => k !== menuKey)))
  }

  const toggleZone = (zoneMenus: MenuItem[], allChecked: boolean) => {
    const keys = zoneMenus.map((m) => m.menuKey)
    setChecked((prev) =>
      allChecked
        ? prev.filter((k) => !keys.includes(k))
        : [...new Set([...prev, ...keys])],
    )
  }

  const onSave = async () => {
    if (!role) return
    setSaving(true)
    try {
      await saveMenuGrants(role.code, checked)
      message.success(`已保存「${role.name}」的菜单权限`)
      onSaved()
      onClose()
    } catch (e) {
      showError(e, '保存菜单权限失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Drawer
      title={role ? `菜单权限：${role.name}（${role.code}）` : '菜单权限'}
      open={!!role}
      onClose={onClose}
      width={DRAWER_DEFAULT}
      destroyOnHidden
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={saving} disabled={loading} onClick={onSave}>
            保存
          </Button>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="菜单权限只控制入口是否可见，不改变接口鉴权：接口访问仍由角色的基础权限与后端过滤器链独立判定。"
      />
      {ZONE_SECTIONS.map(({ zone, title }) => {
        const zoneMenus = active.filter((m) => m.zone === zone)
        const zoneKeys = zoneMenus.map((m) => m.menuKey)
        const allChecked = zoneKeys.length > 0 && zoneKeys.every((k) => checked.includes(k))
        const someChecked = zoneKeys.some((k) => checked.includes(k))
        return (
          <div key={zone} style={{ marginBottom: 24 }}>
            <Space style={{ marginBottom: 8 }} size={12}>
              <Typography.Title level={5} style={{ margin: 0 }}>
                {title}
              </Typography.Title>
              <Checkbox
                checked={allChecked}
                indeterminate={!allChecked && someChecked}
                disabled={loading || zoneMenus.length === 0}
                onChange={() => toggleZone(zoneMenus, allChecked)}
              >
                全选
              </Checkbox>
            </Space>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
                gap: 8,
              }}
            >
              {zoneMenus.map((menu) => (
                <Checkbox
                  key={menu.menuKey}
                  checked={checked.includes(menu.menuKey)}
                  disabled={loading}
                  onChange={(e) => toggle(menu.menuKey, e.target.checked)}
                >
                  <Space size={6}>
                    <span>{menu.title}</span>
                    <Typography.Text type="secondary" code>
                      {menu.menuKey}
                    </Typography.Text>
                    {zone === 'ADMIN' && menu.groupName && <Tag>{menu.groupName}</Tag>}
                  </Space>
                </Checkbox>
              ))}
            </div>
          </div>
        )
      })}
    </Drawer>
  )
}
