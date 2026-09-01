import { Layout, Space, Tag, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { api } from '../shared/api/client'
import NotificationBell from '../features/notifications/components/NotificationBell'
import UserMenu from '../features/auth/components/UserMenu'

const { Header } = Layout

interface HealthInfo {
  status: string
  service: string
  version: string
  time: string
}

/** 共享顶栏：后端健康状态 + 通知铃铛 + 用户下拉，工作台与后台两个布局共用。 */
export default function AppHeader() {
  const [health, setHealth] = useState<HealthInfo | null>(null)

  useEffect(() => {
    api
      .get<HealthInfo>('/health')
      .then(setHealth)
      .catch(() => setHealth(null))
  }, [])

  return (
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
      <Space size={12}>
        <NotificationBell />
        <UserMenu />
      </Space>
    </Header>
  )
}
