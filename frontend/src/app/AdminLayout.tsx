import { Layout, Menu } from 'antd'
import type { MenuProps } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useEffect } from 'react'
import AppHeader from './AppHeader'
import { menuSelectedKey } from './menuSelectedKey'
import { menuIcon } from './menuIcons'
import MenuAccessGuard from './MenuAccessGuard'
import { startNotificationStream, stopNotificationStream } from '../features/notifications/store'
import { useMenus } from '../features/auth/useMenus'
import type { MenuItem } from '../features/auth/types'

const { Sider, Content } = Layout

type AdminMenuItem = NonNullable<MenuProps['items']>[number]

/** CAP-61：后台侧边菜单按下发清单装配；分组顺序取目录 sortNo 的首次出现顺序 */
function buildAdminMenu(menus: MenuItem[]): AdminMenuItem[] {
  const items: AdminMenuItem[] = []
  const groups = new Map<string, AdminMenuItem[]>()
  for (const menu of menus) {
    const node = { key: menu.path, icon: menuIcon(menu.icon), label: menu.title }
    if (menu.groupName) {
      const children = groups.get(menu.groupName) ?? []
      children.push(node)
      groups.set(menu.groupName, children)
    } else {
      items.push(node)
    }
  }
  for (const [label, children] of groups) {
    items.push({ type: 'group', label, children })
  }
  return items
}

/** 按菜单 key 递归查 label（分组/分割线跳过），供顶栏展示当前选中菜单名 */
function menuLabel(items: AdminMenuItem[], key: string): string | undefined {
  for (const item of items) {
    if (!item) continue
    if ('key' in item && item.key === key && 'label' in item && typeof item.label === 'string') {
      return item.label
    }
    if ('children' in item && item.children) {
      const hit = menuLabel(item.children as AdminMenuItem[], key)
      if (hit) return hit
    }
  }
  return undefined
}

/** 管理后台布局（ADMIN + 被授予后台菜单的其他角色，由 RequireAdmin 守卫）。 */
export default function AdminLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const { items: menus } = useMenus()
  const adminItems = buildAdminMenu(menus.filter((m) => m.zone === 'ADMIN'))

  // 管理员同样接收全局通知实时流
  useEffect(() => {
    startNotificationStream()
    return () => stopNotificationStream()
  }, [])

  const selectedKey = menuSelectedKey(location.pathname)
  const title = location.pathname.startsWith('/admin/knowledge/bases/')
    ? '知识库详情'
    : (menuLabel(adminItems, selectedKey) ?? '后台管理')

  return (
    <Layout style={{ height: '100vh' }}>
      {/* 菜单组增多后总高可能超视口：Sider 内部滚动，不溢出整页（layout 回归断言文档不溢出） */}
      <Sider theme="light" width={200} style={{ borderRight: '1px solid #f0f0f0', overflowY: 'auto', overflowX: 'hidden' }}>
        <div style={{ padding: '16px', fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}>
          <img src="/logo.svg" alt="Dev-Mind" width={22} height={22} />
          Dev-Mind 后台
        </div>
        <Menu
          theme="light"
          mode="inline"
          selectedKeys={[selectedKey]}
          onClick={({ key }) => navigate(key)}
          items={adminItems}
        />
      </Sider>
      <Layout>
        <AppHeader title={title} />
        {/* flex 列布局与工作台一致：子页根节点 flex:1 minHeight:0 撑满高度（见 shared/utils/pageLayout） */}
        <Content style={{ padding: '16px 24px 24px', overflow: 'auto', display: 'flex', flexDirection: 'column' }}>
          <MenuAccessGuard>
            <Outlet />
          </MenuAccessGuard>
        </Content>
      </Layout>
    </Layout>
  )
}
