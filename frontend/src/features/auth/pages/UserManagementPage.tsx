import { Button, Card, Drawer, Form, Input, Modal, Select, Space, Table, Tag, Typography, message } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
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
  const [manageTarget, setManageTarget] = useState<AuthUser | null>(null)
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
      setManageTarget((t) => (t && t.id === u.id ? { ...t, role } : t))
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
      setManageTarget((t) => (t && t.id === u.id ? { ...t, status: next } : t))
      reload()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  return (
    <Card
      title="用户管理"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={reload}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            新建用户
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        管理平台账号与角色权限：ADMIN 全权限 / DEVELOPER 业务读写 / VIEWER 只读；local 为系统内置身份，不可修改。
      </Typography.Paragraph>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={users}
        pagination={false}
        locale={{ emptyText: '暂无用户，点击右上角「新建用户」创建第一个账号' }}
        columns={[
          { title: '用户名', dataIndex: 'username' },
          { title: '显示名', dataIndex: 'displayName' },
          {
            title: '角色',
            dataIndex: 'role',
            render: (role: string) =>
              role === 'ADMIN' ? (
                <Tag color="red">ADMIN</Tag>
              ) : role === 'DEVELOPER' ? (
                <Tag color="blue">DEVELOPER</Tag>
              ) : (
                <Tag>VIEWER</Tag>
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
              <Button size="small" onClick={() => setManageTarget(u)}>
                管理
              </Button>
            ),
          },
        ]}
      />

      <Drawer
        title={`管理用户：${manageTarget?.username ?? ''}`}
        open={!!manageTarget}
        onClose={() => setManageTarget(null)}
        width={360}
      >
        {manageTarget && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <div>
              <Typography.Text type="secondary">角色</Typography.Text>
              <Select
                value={manageTarget.role}
                style={{ width: '100%', marginTop: 8 }}
                disabled={manageTarget.username === 'local'}
                onChange={(r) => changeRole(manageTarget, r)}
                options={[
                  { value: 'ADMIN', label: 'ADMIN（全权限）' },
                  { value: 'DEVELOPER', label: 'DEVELOPER（业务读写）' },
                  { value: 'VIEWER', label: 'VIEWER（只读）' },
                ]}
              />
            </div>
            <Button
              onClick={() => setResetTarget(manageTarget)}
              disabled={manageTarget.username === 'local'}
            >
              重置密码
            </Button>
            <Button
              disabled={manageTarget.username === 'local' || manageTarget.username === me?.username}
              onClick={() => toggleStatus(manageTarget)}
            >
              {manageTarget.status === 'DISABLED' ? '启用' : '禁用'}
            </Button>
          </Space>
        )}
      </Drawer>

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
    </Card>
  )
}
