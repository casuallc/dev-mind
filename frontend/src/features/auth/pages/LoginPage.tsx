import { Button, Card, Form, Input, Typography, message } from 'antd'
import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { login } from '../api'
import { setAuth } from '../authStore'
import { isApiRequestError } from '../../../shared/api/error'

/** CAP-01 登录页：裸路由（不带 AppLayout），成功后回指挥中心。 */
export default function LoginPage() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true)
    try {
      const resp = await login(values.username.trim(), values.password)
      setAuth(resp)
      message.success(`欢迎，${resp.user.displayName || resp.user.username}`)
      navigate('/', { replace: true })
    } catch (e) {
      message.error(isApiRequestError(e) && e.status === 401 ? '用户名或密码错误' : '登录失败，请稍后重试')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div
      style={{
        height: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#f0f2f5',
      }}
    >
      <Card style={{ width: 360, boxShadow: '0 4px 16px rgba(0,0,0,0.08)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10, marginBottom: 24 }}>
          <img src="/logo.svg" alt="Dev-Mind" width={36} height={36} />
          <Typography.Title level={3} style={{ margin: 0 }}>
            Dev-Mind
          </Typography.Title>
        </div>
        <Form onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" autoFocus />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0 }}>
            <Button type="primary" htmlType="submit" block loading={loading}>
              登录
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}
