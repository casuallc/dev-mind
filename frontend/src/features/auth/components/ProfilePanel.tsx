import { Button, Descriptions, Divider, Form, Input, Tag, Typography, message } from 'antd'
import { useState, useSyncExternalStore } from 'react'
import { changePassword } from '../api'
import { getUserSnapshot, subscribeAuth } from '../authStore'
import { showError } from '../../../shared/utils/showError'

const ROLE_LABELS: Record<string, { color: string; text: string }> = {
  ADMIN: { color: 'red', text: 'ADMIN' },
  DEVELOPER: { color: 'blue', text: 'DEVELOPER' },
  VIEWER: { color: 'default', text: 'VIEWER' },
}

/** 设置页「个人信息」视图：账号信息只读展示 + 修改密码（原 UserMenu 弹窗搬入）。 */
export default function ProfilePanel() {
  const user = useSyncExternalStore(subscribeAuth, getUserSnapshot)
  const [form] = Form.useForm<{ oldPassword: string; newPassword: string }>()
  const [saving, setSaving] = useState(false)

  const onChangePassword = async (v: { oldPassword: string; newPassword: string }) => {
    setSaving(true)
    try {
      await changePassword(v.oldPassword, v.newPassword)
      message.success('密码已修改')
      form.resetFields()
    } catch (e) {
      showError(e, '修改失败')
    } finally {
      setSaving(false)
    }
  }

  if (!user) return null
  const role = ROLE_LABELS[user.role] ?? { color: 'default', text: user.role }
  return (
    <div style={{ maxWidth: 480 }}>
      <Typography.Paragraph type="secondary">
        你的账号信息（用户名 / 显示名 / 角色）与登录密码修改入口。
      </Typography.Paragraph>
      <Descriptions
        column={1}
        items={[
          { key: 'username', label: '用户名', children: user.username },
          { key: 'displayName', label: '显示名', children: user.displayName || '—' },
          { key: 'role', label: '角色', children: <Tag color={role.color}>{role.text}</Tag> },
        ]}
      />
      <Divider orientation="left" orientationMargin={0}>修改密码</Divider>
      <Form form={form} layout="vertical" onFinish={onChangePassword}>
        <Form.Item name="oldPassword" label="原密码" rules={[{ required: true, message: '请输入原密码' }]}>
          <Input.Password autoComplete="current-password" />
        </Form.Item>
        <Form.Item
          name="newPassword"
          label="新密码"
          rules={[{ required: true, min: 6, message: '至少 6 位' }]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={saving}>
          保存
        </Button>
      </Form>
    </div>
  )
}
