import { Descriptions, Dropdown, Modal, Space, Tag, Typography } from 'antd'
import { InfoCircleOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons'
import { useState, useSyncExternalStore } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../../../shared/api/client'
import { logout } from '../api'
import { clearAuth, getRefreshToken, getUserSnapshot, subscribeAuth } from '../authStore'

const ROLE_LABELS: Record<string, { color: string; text: string }> = {
  ADMIN: { color: 'red', text: 'ADMIN' },
  DEVELOPER: { color: 'blue', text: 'DEVELOPER' },
  VIEWER: { color: 'default', text: 'VIEWER' },
}

interface AboutInfo {
  name: string
  description: string
  version: string
  buildTime: string | null
  commit: string | null
  branch: string | null
  commitTime: string | null
}

/** CAP-01 Header 用户区：当前用户 + 角色 + 下拉（关于 / 退出登录）。
 * 个人信息 / 第三方账号 / Jira 推送模板 / 工作日志统一收进一级导航「设置」页（/settings），不走下拉开入口。 */
export default function UserMenu() {
  const user = useSyncExternalStore(subscribeAuth, getUserSnapshot)
  const navigate = useNavigate()
  const [aboutOpen, setAboutOpen] = useState(false)
  const [about, setAbout] = useState<AboutInfo | null>(null)

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
  const openAbout = () => {
    setAboutOpen(true)
    api
      .get<AboutInfo>('/about')
      .then(setAbout)
      .catch(() => setAbout(null))
  }
  return (
    <>
      <Dropdown
        menu={{
          items: [
            { key: 'about', icon: <InfoCircleOutlined />, label: '关于', onClick: openAbout },
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
      <Modal
        title={about ? `${about.name} · ${about.description}` : '关于'}
        open={aboutOpen}
        footer={null}
        onCancel={() => setAboutOpen(false)}
      >
        {about ? (
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="产品名称">{about.name}</Descriptions.Item>
            <Descriptions.Item label="版本">v{about.version}</Descriptions.Item>
            <Descriptions.Item label="构建时间">{about.buildTime ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="Commit">
              {about.commit ? (
                <Typography.Text code copyable>
                  {about.commit}
                </Typography.Text>
              ) : (
                '—'
              )}
            </Descriptions.Item>
            <Descriptions.Item label="分支">{about.branch ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="提交时间">{about.commitTime ?? '—'}</Descriptions.Item>
          </Descriptions>
        ) : (
          <Typography.Text type="secondary">后端未连接，无法获取版本信息</Typography.Text>
        )}
      </Modal>
    </>
  )
}
