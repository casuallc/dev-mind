import { Alert, Button, Card, Checkbox, Space, Table, Tag, Typography, message } from 'antd'
import type { TableProps } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { fetchMenuCatalog, fetchMenuGrants, saveMenuGrants } from '../api'
import type { MenuGrants, MenuItem } from '../types'
import { pageCardBodyScrollStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'

/** CAP-61 FR-04 菜单权限矩阵：行=菜单（按 zone 分组），列=角色；ADMIN 列恒全选且不可编辑。 */
const ZONE_SECTIONS = [
  { zone: 'TOP', title: '工作台顶部导航' },
  { zone: 'PROJECT', title: '项目二级页签' },
  { zone: 'ADMIN', title: '后台侧边菜单' },
]

const ROLE_LABELS: Record<string, string> = {
  ADMIN: 'ADMIN（恒全见）',
  DEVELOPER: 'DEVELOPER',
  VIEWER: 'VIEWER',
}

export default function MenuPermissionsPage() {
  const [catalog, setCatalog] = useState<MenuItem[]>([])
  const [grants, setGrants] = useState<MenuGrants | null>(null)
  const [loading, setLoading] = useState(false)
  const [savingRole, setSavingRole] = useState<string | null>(null)

  const reload = () => {
    setLoading(true)
    Promise.all([fetchMenuCatalog(), fetchMenuGrants()])
      .then(([items, roleGrants]) => {
        setCatalog(items)
        setGrants(roleGrants)
      })
      .catch((e) => showError(e, '加载菜单权限失败'))
      .finally(() => setLoading(false))
  }

  useEffect(reload, [])

  const roles = grants?.roles ?? ['ADMIN', 'DEVELOPER', 'VIEWER']

  const toggle = async (role: string, menuKey: string, checked: boolean) => {
    if (!grants || role === 'ADMIN') return
    const next = new Set(grants.grants[role] ?? [])
    if (checked) next.add(menuKey)
    else next.delete(menuKey)
    setSavingRole(role)
    try {
      setGrants(await saveMenuGrants(role, [...next]))
      message.success(`已保存 ${role} 的菜单授权`)
    } catch (e) {
      showError(e, '保存菜单授权失败')
    } finally {
      setSavingRole(null)
    }
  }

  const columns = (zone: string): TableProps<MenuItem>['columns'] => [
    {
      title: '菜单',
      dataIndex: 'title',
      render: (_, menu) => (
        <Space size={8}>
          <span>{menu.title}</span>
          <Typography.Text type="secondary" code>
            {menu.menuKey}
          </Typography.Text>
          {menu.retired && <Tag>已下架</Tag>}
        </Space>
      ),
    },
    ...(zone === 'ADMIN'
      ? [{ title: '分组', dataIndex: 'groupName', width: 120, render: (v?: string | null) => v || '—' }]
      : []),
    ...roles.map((role) => ({
      title: ROLE_LABELS[role] ?? role,
      key: role,
      width: role === 'ADMIN' ? 150 : 180,
      align: 'center' as const,
      render: (_: unknown, menu: MenuItem) =>
        role === 'ADMIN' ? (
          <Checkbox checked disabled />
        ) : (
          <Checkbox
            checked={!!grants?.grants[role]?.includes(menu.menuKey)}
            disabled={menu.retired || savingRole !== null}
            onChange={(e) => void toggle(role, menu.menuKey, e.target.checked)}
          />
        ),
    })),
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
      title="菜单权限"
      extra={
        <Button icon={<ReloadOutlined />} onClick={reload} loading={loading}>
          刷新
        </Button>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="菜单权限只控制入口是否可见，不改变接口鉴权：接口访问仍由角色与后端过滤器链独立判定。"
      />
      {ZONE_SECTIONS.map(({ zone, title }) => (
        <div key={zone} style={{ marginBottom: 24 }}>
          <Typography.Title level={5} style={{ marginTop: 0 }}>
            {title}
          </Typography.Title>
          <Table<MenuItem>
            rowKey="menuKey"
            size="small"
            loading={loading}
            dataSource={catalog.filter((m) => m.zone === zone)}
            columns={columns(zone)}
            pagination={false}
          />
        </div>
      ))}
    </Card>
  )
}
