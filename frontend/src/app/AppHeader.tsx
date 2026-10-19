import { Layout, Space, Typography, Button, Tooltip } from 'antd'
import { HomeOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import NotificationBell from '../features/notifications/components/NotificationBell'
import UserMenu from '../features/auth/components/UserMenu'

const { Header } = Layout

/** 后台顶栏：当前选中菜单名 + 通知铃铛 + 用户下拉（仅 AdminLayout 使用）。
 * 版本/构建信息见用户下拉「关于」弹框。 */
export default function AppHeader({ title }: { title: string }) {
  const navigate = useNavigate()

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
      <Typography.Text strong>{title}</Typography.Text>
      <Space size={12}>
        {/* 与工作台顶栏「后台管理」入口同款：图标 + Tooltip */}
        <Tooltip title="返回工作台">
          <Button type="text" icon={<HomeOutlined />} onClick={() => navigate('/')} />
        </Tooltip>
        <NotificationBell />
        <UserMenu />
      </Space>
    </Header>
  )
}
