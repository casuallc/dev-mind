import { Dropdown, Form, Input, Modal, Space, Tag, Typography, message } from 'antd'
import { GithubOutlined, KeyOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons'
import { useState, useSyncExternalStore } from 'react'
import { useNavigate } from 'react-router-dom'
import { changePassword, logout } from '../api'
import { clearAuth, getRefreshToken, getUserSnapshot, subscribeAuth } from '../authStore'

const ROLE_LABELS: Record<string, { color: string; text: string }> = {
  ADMIN: { color: 'red', text: 'ADMIN' },
  DEVELOPER: { color: 'blue', text: 'DEVELOPER' },
  VIEWER: { color: 'default', text: 'VIEWER' },
}

/** CAP-01 Header 用户区：当前用户 + 角色 + 下拉（修改密码 / 退出登录）。 */
export default function UserMenu() {
  const user = useSyncExternalStore(subscribeAuth, getUserSnapshot)
  const navigate = useNavigate()
  const [pwdOpen, setPwdOpen] = useState(false)
  const [form] = Form.useForm()

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

  const onChangePassword = async () => {
    const v = await form.validateFields()
    try {
      await changePassword(v.oldPassword, v.newPassword)
      message.success('密码已修改')
      setPwdOpen(false)
      form.resetFields()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '修改失败')
    }
  }

  return (
    <>
      <Dropdown
        menu={{
          items: [
            { key: 'password', icon: <KeyOutlined />, label: '修改密码', onClick: () => setPwdOpen(true) },
            {
              key: 'git-credentials',
              icon: <GithubOutlined />,
              label: 'Git 凭证',
              onClick: () => navigate('/me/git-credentials'),
            },
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

      <Modal
        title="修改密码"
        open={pwdOpen}
        onOk={onChangePassword}
        onCancel={() => setPwdOpen(false)}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item name="oldPassword" label="原密码" rules={[{ required: true, message: '请输入原密码' }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[{ required: true, min: 6, message: '至少 6 位' }]}
          >
            <Input.Password />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
