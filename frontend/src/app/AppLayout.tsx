import { Layout, Menu, Space, Tag, Typography } from 'antd'
import {
  RobotOutlined,
  DeploymentUnitOutlined,
  SafetyCertificateOutlined,
  FolderOutlined,
  BellOutlined,
  ReadOutlined,
  FileTextOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { api } from '../shared/api/client'
import NotificationBell from '../features/notifications/components/NotificationBell'
import { startNotificationStream, stopNotificationStream } from '../features/notifications/store'

const { Sider, Content, Header } = Layout

interface HealthInfo {
  status: string
  service: string
  version: string
  time: string
}

export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const [health, setHealth] = useState<HealthInfo | null>(null)

  useEffect(() => {
    api
      .get<HealthInfo>('/health')
      .then(setHealth)
      .catch(() => setHealth(null))
  }, [])

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
            { key: '/projects', icon: <FolderOutlined />, label: '项目管理' },
            { key: '/sessions', icon: <RobotOutlined />, label: '会话看板' },
            { key: '/notifications', icon: <BellOutlined />, label: '通知中心' },
            { key: '/knowledge', icon: <ReadOutlined />, label: '知识库' },
            { key: '/docs', icon: <FileTextOutlined />, label: '文档管理' },
            { key: '/templates', icon: <DeploymentUnitOutlined />, label: '会话模板' },
            { key: '/settings', icon: <SafetyCertificateOutlined />, label: '设置' },
          ]}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
          }}
        >
          <Space size={12}>
            <Typography.Text strong>Agent 会话管理</Typography.Text>
            <Tag color={health?.status === 'UP' ? 'green' : 'red'}>
              后端 {health ? `${health.status} · v${health.version}` : '未连接'}
            </Tag>
          </Space>
          <NotificationBell />
        </Header>
        <Content style={{ padding: 24, overflow: 'auto' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
