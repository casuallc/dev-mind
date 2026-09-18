import { Dropdown, Space, Tag, Typography } from 'antd'
import { LogoutOutlined, SettingOutlined, UserOutlined } from '@ant-design/icons'
import { useSyncExternalStore } from 'react'
import { useNavigate } from 'react-router-dom'
import { logout } from '../api'
import { clearAuth, getRefreshToken, getUserSnapshot, subscribeAuth } from '../authStore'

const ROLE_LABELS: Record<string, { color: string; text: string }> = {
  ADMIN: { color: 'red', text: 'ADMIN' },
  DEVELOPER: { color: 'blue', text: 'DEVELOPER' },
  VIEWER: { color: 'default', text: 'VIEWER' },
}
/** CAP-01 Header 用户区：当前用户 + 角色 + 下拉（个人设置 / 退出登录）。 */
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
        items: [
          // 个人信息 / 第三方账号（CAP-35）/ Jira 推送模板（CAP-47 FR-10）统一收进个人设置页
          { key: 'settings', icon: <SettingOutlined />, label: '个人设置', onClick: () => navigate('/me/settings') },
          { type: 'divider' },
          { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: onLogout },
        ],
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
