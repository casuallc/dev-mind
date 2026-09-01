import { Layout, Menu } from 'antd'
import {
  RobotOutlined,
  SafetyCertificateOutlined,
  FolderOutlined,
  BellOutlined,
  ReadOutlined,
  FileTextOutlined,
  DashboardOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useEffect, useSyncExternalStore } from 'react'
import AppHeader from './AppHeader'
import { startNotificationStream, stopNotificationStream } from '../features/notifications/store'
import { getUserSnapshot, isAdmin, subscribeAuth } from '../features/auth/authStore'

const { Sider, Content } = Layout

export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  // 认证态变化（登录/退出/刷新轮换）时重渲染菜单与用户区
  useSyncExternalStore(subscribeAuth, getUserSnapshot)

  // 启动全局通知实时流（铃铛角标/浏览器通知依赖它）
  useEffect(() => {
    startNotificationStream()
    return () => stopNotificationStream()
  }, [])

  const selectedKey = location.pathname.startsWith('/sessions/')
    ? '/sessions'
    : location.pathname.startsWith('/projects/')
      ? '/projects'
      : location.pathname.startsWith('/docs/')
        ? '/docs'
        : location.pathname

  return (
    <Layout style={{ height: '100vh' }}>
      <Sider theme="dark" width={200}>
        <div style={{ padding: '16px', color: '#fff', fontWeight: 600 }}>
          Dev-Mind
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          onClick={({ key }) => navigate(key)}
          items={[
            { key: '/dashboard', icon: <DashboardOutlined />, label: '指挥中心' },
            { key: '/projects', icon: <FolderOutlined />, label: '项目' },
            { key: '/sessions', icon: <RobotOutlined />, label: '会话看板' },
            { key: '/notifications', icon: <BellOutlined />, label: '通知中心' },
            { key: '/knowledge', icon: <ReadOutlined />, label: '知识库' },
            { key: '/docs', icon: <FileTextOutlined />, label: '文档管理' },
            // 管理功能集中在 /admin 后台，仅 ADMIN 可见入口
            ...(isAdmin()
              ? [
                  { type: 'divider' as const },
                  { key: '/admin', icon: <SafetyCertificateOutlined />, label: '后台管理' },
                ]
              : []),
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
