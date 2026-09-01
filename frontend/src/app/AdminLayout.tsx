import { Layout, Menu } from 'antd'
import {
  CloudServerOutlined,
  DashboardOutlined,
  DeploymentUnitOutlined,
  FolderOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useEffect } from 'react'
import AppHeader from './AppHeader'
import { startNotificationStream, stopNotificationStream } from '../features/notifications/store'

const { Sider, Content } = Layout

/** 管理后台布局（仅 ADMIN，由 RequireAdmin 守卫）：项目管理 / 用户管理 / 服务器运维 / 会话模板。 */
export default function AdminLayout() {
  const navigate = useNavigate()
  const location = useLocation()

  // 管理员同样接收全局通知实时流
  useEffect(() => {
    startNotificationStream()
    return () => stopNotificationStream()
  }, [])

  const selectedKey = location.pathname.startsWith('/admin/projects')
    ? '/admin/projects'
    : location.pathname

  return (
    <Layout style={{ height: '100vh' }}>
      <Sider theme="dark" width={200}>
        <div style={{ padding: '16px', color: '#fff', fontWeight: 600 }}>
          Dev-Mind 后台
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          onClick={({ key }) => navigate(key)}
          items={[
            { key: '/admin/projects', icon: <FolderOutlined />, label: '项目管理' },
            { key: '/admin/users', icon: <SafetyCertificateOutlined />, label: '用户管理' },
            { key: '/admin/servers', icon: <CloudServerOutlined />, label: '服务器运维' },
            { key: '/admin/templates', icon: <DeploymentUnitOutlined />, label: '会话模板' },
            { type: 'divider' },
            { key: '/dashboard', icon: <DashboardOutlined />, label: '返回工作台' },
          ]}
        />
      </Sider>
      <Layout>
        <AppHeader />
        <Content style={{ padding: 24, overflow: 'auto' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
