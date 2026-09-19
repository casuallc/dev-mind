import { Dropdown, Space, Tag, Typography } from 'antd'
import { LogoutOutlined, UserOutlined } from '@ant-design/icons'
import { useSyncExternalStore } from 'react'
import { useNavigate } from 'react-router-dom'
import { logout } from '../api'
import { clearAuth, getRefreshToken, getUserSnapshot, subscribeAuth } from '../authStore'

const ROLE_LABELS: Record<string, { color: string; text: string }> = {
  ADMIN: { color: 'red', text: 'ADMIN' },
  DEVELOPER: { color: 'blue', text: 'DEVELOPER' },
  VIEWER: { color: 'default', text: 'VIEWER' },
}
/** CAP-01 Header 用户区：当前用户 + 角色 + 下拉（退出登录）。
 * 个人信息 / 第三方账号 / Jira 推送模板统一收进一级导航「设置」页（/me/settings），不再走下拉开入口。 */
export default function UserMenu() {
  const user = useSyncExternalStore(subscribeAuth, getUserSnapshot)
  const navigate = useNavigate()

  if (!user) return null
  const role = ROLE_LABELS[user.role] ?? { color: 'default', text: user.role }
  const onLogout = async () => {
    try {
      await logout(getRefreshToken())
    } catch {
      // 登出失败也强制清本地态
    }
    clearAuth()
    navigate('/login', { replace: true })
  }
  return (
    <Dropdown
      menu={{
        items: [{ key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: onLogout }],
      }}
    >
      <Space style={{ cursor: 'pointer' }}>
        <UserOutlined />
        <Typography.Text>{user.displayName || user.username}</Typography.Text>
        <Tag color={role.color}>{role.text}</Tag>
      </Space>
    </Dropdown>
  )
}
