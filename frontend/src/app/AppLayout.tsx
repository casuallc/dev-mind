import { Layout, Menu } from 'antd'
import {
  RobotOutlined,
  SafetyCertificateOutlined,
  BellOutlined,
  HomeOutlined,
  BulbOutlined,
  FolderOutlined,
  ToolOutlined,
  DeploymentUnitOutlined,
  ExperimentOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useEffect, useSyncExternalStore } from 'react'
import AppHeader from './AppHeader'
import ProjectSwitcher from './ProjectSwitcher'
import { menuSelectedKey } from './menuSelectedKey'
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

  const selectedKey = menuSelectedKey(location.pathname)

  return (
    <Layout style={{ height: '100vh' }}>
      <Sider theme="light" width={200} style={{ borderRight: '1px solid #f0f0f0' }}>
        <div style={{ padding: '16px 16px 8px', fontWeight: 600 }}>
          Dev-Mind
        </div>
        <ProjectSwitcher />
        <Menu
          theme="light"
          mode="inline"
          selectedKeys={[selectedKey]}
          onClick={({ key }) => navigate(key)}
          items={[
            // 当前项目区：以某个具体项目为主线，切换项目在侧边栏顶部
            {
              type: 'group' as const,
              label: '当前项目',
              children: [
                { key: '/overview', icon: <HomeOutlined />, label: '项目概览' },
                { key: '/requirements', icon: <BulbOutlined />, label: '需求' },
                { key: '/builds', icon: <ToolOutlined />, label: '构建' },
                { key: '/deployments', icon: <DeploymentUnitOutlined />, label: '部署' },
                { key: '/tests', icon: <ExperimentOutlined />, label: '测试' },
              ],
            },
            {
              type: 'group' as const,
              label: '协作',
              children: [
                { key: '/sessions', icon: <RobotOutlined />, label: '会话看板' },
                { key: '/notifications', icon: <BellOutlined />, label: '通知中心' },
              ],
            },
            {
              type: 'group' as const,
              label: '平台',
              children: [
                { key: '/projects', icon: <FolderOutlined />, label: '全部项目' },
                // 管理功能集中在 /admin 后台，仅 ADMIN 可见入口
                ...(isAdmin()
                  ? [{ key: '/admin', icon: <SafetyCertificateOutlined />, label: '后台管理' }]
                  : []),
              ],
            },
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
