import { Layout, Menu } from 'antd'
import {
  RobotOutlined,
  SafetyCertificateOutlined,
  BellOutlined,
  ReadOutlined,
  FileTextOutlined,
  DashboardOutlined,
  HomeOutlined,
  BulbOutlined,
  ToolOutlined,
  DeploymentUnitOutlined,
  ExperimentOutlined,
  BranchesOutlined,
  SettingOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useEffect, useSyncExternalStore } from 'react'
import AppHeader from './AppHeader'
import ProjectSwitcher from '../features/projects/components/ProjectSwitcher'
import { startNotificationStream, stopNotificationStream } from '../features/notifications/store'
import { getUserSnapshot, isAdmin, subscribeAuth } from '../features/auth/authStore'

const { Sider, Content } = Layout

// 菜单选中态：前缀匹配（长的在前），需求详情页高亮「需求」
const SELECT_PREFIXES: Array<[string, string]> = [
  ['/projects/', '/requirements'], // /projects/:id/requirements/:rid → 需求
  ['/sessions/', '/sessions'],
  ['/docs/', '/docs'],
  ['/overview', '/overview'],
  ['/requirements', '/requirements'],
  ['/builds', '/builds'],
  ['/deployments', '/deployments'],
  ['/tests', '/tests'],
  ['/worktrees', '/worktrees'],
  ['/settings', '/settings'],
  ['/dashboard', '/dashboard'],
  ['/knowledge', '/knowledge'],
  ['/notifications', '/notifications'],
]

function selectedKeyOf(pathname: string): string {
  for (const [prefix, key] of SELECT_PREFIXES) {
    if (pathname.startsWith(prefix)) return key
  }
  return pathname
}

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

  const selectedKey = selectedKeyOf(location.pathname)

  return (
    <Layout style={{ height: '100vh' }}>
      <Sider theme="dark" width={200}>
        <div style={{ padding: '16px 16px 8px', color: '#fff', fontWeight: 600 }}>
          Dev-Mind
        </div>
        <ProjectSwitcher />
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          onClick={({ key }) => navigate(key)}
          items={[
            // 当前项目区：以某个具体项目为主线，切换项目在侧边栏顶部
            { key: '/overview', icon: <HomeOutlined />, label: '项目概览' },
            { key: '/requirements', icon: <BulbOutlined />, label: '需求' },
            { key: '/builds', icon: <ToolOutlined />, label: '构建' },
            { key: '/deployments', icon: <DeploymentUnitOutlined />, label: '部署' },
            { key: '/tests', icon: <ExperimentOutlined />, label: '测试' },
            { key: '/worktrees', icon: <BranchesOutlined />, label: 'Worktree' },
            { key: '/settings', icon: <SettingOutlined />, label: '项目设置' },
            { type: 'divider' as const },
            // 平台区
            { key: '/dashboard', icon: <DashboardOutlined />, label: '指挥中心' },
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
