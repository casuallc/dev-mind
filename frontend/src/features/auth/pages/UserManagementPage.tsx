import { Button, Form, Input, Modal, Select, Space, Table, Tag, message } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { createUser, listUsers, resetPassword, updateUser } from '../api'
import { getUserSnapshot } from '../authStore'
import type { AuthUser } from '../types'

/** CAP-01 后台页：用户管理（仅 ADMIN，由 RequireAdmin 路由守卫保证）。 */
export default function UserManagementPage() {
  const [users, setUsers] = useState<AuthUser[]>([])
  const [loading, setLoading] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [resetTarget, setResetTarget] = useState<AuthUser | null>(null)
  const [createForm] = Form.useForm()
  const [resetForm] = Form.useForm()
  const me = getUserSnapshot()

  const reload = () => {
    setLoading(true)
    listUsers()
      .then(setUsers)
      .catch((e) => message.error(`加载用户失败: ${e.message}`))
      .finally(() => setLoading(false))
  }

  useEffect(reload, [])

  const onCreate = async () => {
    const v = await createForm.validateFields()
    try {
      await createUser(v)
      message.success(`用户 ${v.username} 已创建`)
      setCreateOpen(false)
      createForm.resetFields()
      reload()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败')
    }
  }

  const onReset = async () => {
    const v = await resetForm.validateFields()
    if (!resetTarget) return
    try {
      await resetPassword(resetTarget.id, v.password)
      message.success(`已重置 ${resetTarget.username} 的密码`)
      setResetTarget(null)
      resetForm.resetFields()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重置失败')
    }
  }

  const changeRole = async (u: AuthUser, role: string) => {
    try {
      await updateUser(u.id, { role })
      message.success(`${u.username} 角色已改为 ${role}`)
      reload()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '修改失败')
    }
  }

  const toggleStatus = async (u: AuthUser) => {
    const next = u.status === 'DISABLED' ? 'ACTIVE' : 'DISABLED'
    try {
      await updateUser(u.id, { status: next })
      message.success(`${u.username} 已${next === 'ACTIVE' ? '启用' : '禁用'}`)
      reload()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  return (
    <div>
      <Space style={{ marginBottom: 16, justifyContent: 'space-between', width: '100%' }}>
        <h2 style={{ margin: 0 }}>用户管理</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          新建用户
        </Button>
      </Space>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={users}
        pagination={false}
        columns={[
          { title: '用户名', dataIndex: 'username' },
          { title: '显示名', dataIndex: 'displayName' },
          {
            title: '角色',
            dataIndex: 'role',
            render: (role: string, u) => (
              <Select
                size="small"
                value={role}
                style={{ width: 130 }}
                disabled={u.username === 'local'}
                onChange={(r) => changeRole(u, r)}
                options={[
                  { value: 'ADMIN', label: <Tag color="red">ADMIN</Tag> },
                  { value: 'DEVELOPER', label: <Tag color="blue">DEVELOPER</Tag> },
                  { value: 'VIEWER', label: <Tag>VIEWER</Tag> },
                ]}
              />
            ),
          },
          {
            title: '状态',
            dataIndex: 'status',
            render: (s: string, u) =>
              u.username === 'local' ? (
                <Tag>系统身份</Tag>
              ) : (
                <Tag color={s === 'DISABLED' ? 'default' : 'green'}>
                  {s === 'DISABLED' ? '已禁用' : '正常'}
                </Tag>
              ),
          },
          {
            title: '操作',
            render: (_, u) => (
              <Space>
                <Button size="small" onClick={() => setResetTarget(u)} disabled={u.username === 'local'}>
                  重置密码
                </Button>
                <Button
                  size="small"
                  danger={u.status !== 'DISABLED'}
                  disabled={u.username === 'local' || u.username === me?.username}
                  onClick={() => toggleStatus(u)}
                >
                  {u.status === 'DISABLED' ? '启用' : '禁用'}
                </Button>
              </Space>
            ),
          },
        ]}
      />

      <Modal
        title="新建用户"
        open={createOpen}
        onOk={onCreate}
        onCancel={() => setCreateOpen(false)}
        destroyOnHidden
      >
        <Form form={createForm} layout="vertical">
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input placeholder="登录账号" />
          </Form.Item>
          <Form.Item name="displayName" label="显示名">
            <Input placeholder="留空则同用户名" />
          </Form.Item>
          <Form.Item
            name="password"
            label="初始密码"
            rules={[{ required: true, min: 6, message: '至少 6 位' }]}
          >
            <Input.Password />
          </Form.Item>
          <Form.Item name="role" label="角色" initialValue="DEVELOPER" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'ADMIN', label: 'ADMIN（全权限）' },
                { value: 'DEVELOPER', label: 'DEVELOPER（业务读写）' },
                { value: 'VIEWER', label: 'VIEWER（只读）' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`重置密码：${resetTarget?.username ?? ''}`}
        open={!!resetTarget}
        onOk={onReset}
        onCancel={() => setResetTarget(null)}
        destroyOnHidden
      >
        <Form form={resetForm} layout="vertical">
          <Form.Item
            name="password"
            label="新密码"
            rules={[{ required: true, min: 6, message: '至少 6 位' }]}
          >
            <Input.Password />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
