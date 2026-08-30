import { Layout, Menu, Tag, Typography } from 'antd'
import {
  RobotOutlined,
  DeploymentUnitOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { api } from '../shared/api/client'

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

  const selectedKey = location.pathname.startsWith('/sessions/')
    ? '/sessions'
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
            { key: '/sessions', icon: <RobotOutlined />, label: '会话看板' },
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
            gap: 12,
          }}
        >
          <Typography.Text strong>Agent 会话管理</Typography.Text>
          <Tag color={health?.status === 'UP' ? 'green' : 'red'}>
            后端 {health ? `${health.status} · v${health.version}` : '未连接'}
          </Tag>
        </Header>
        <Content style={{ padding: 24, overflow: 'auto' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
