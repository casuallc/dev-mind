import { Button, Form, Input, Modal, Select, Space, Tag, Tooltip, Typography, message } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { createUser, deleteUser, listRoles, listUsers, resetPassword, updateUser } from '../api'
import { getUserSnapshot } from '../authStore'
import type { AuthUser, RoleView } from '../types'
import { baseRoleMeta, displayRole, roleSelectOptions } from '../roleMeta'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { fmtTime } from '../../../shared/utils/format'

interface UsersPanelProps {
  createOpen: boolean
  onCreateOpenChange: (open: boolean) => void
  refreshTick: number
}

/** 用户管理 → 用户：账号 CRUD 与角色分配（角色来自角色管理页维护的 roles 表）。 */
export default function UsersPanel({ createOpen, onCreateOpenChange, refreshTick }: UsersPanelProps) {
  const [users, setUsers] = useState<AuthUser[]>([])
  const [roles, setRoles] = useState<RoleView[]>([])
  const [loading, setLoading] = useState(false)
  const [editTarget, setEditTarget] = useState<AuthUser | null>(null)
  const [resetTarget, setResetTarget] = useState<AuthUser | null>(null)
  const [roleTarget, setRoleTarget] = useState<AuthUser | null>(null)
  const [roleValue, setRoleValue] = useState<string>('')
  const [createForm] = Form.useForm()
  const [editForm] = Form.useForm()
  const [resetForm] = Form.useForm()
  const me = getUserSnapshot()

  const reload = useCallback(() => {
    setLoading(true)
    Promise.all([listUsers(), listRoles()])
      .then(([userList, roleList]) => {
        setUsers(userList)
        setRoles(roleList)
      })
      .catch((e) => showError(e, '加载用户失败'))
      .finally(() => setLoading(false))
  }, [])

  useEffect(reload, [reload, refreshTick])

  const roleOptions = roleSelectOptions(roles)

  const onCreate = async () => {
    const v = await createForm.validateFields()
    try {
      await createUser(v)
      message.success(`用户 ${v.username} 已创建`)
      onCreateOpenChange(false)
      createForm.resetFields()
      reload()
    } catch (e) {
      showError(e, '创建失败')
    }
  }

  const openEdit = (u: AuthUser) => {
    setEditTarget(u)
    editForm.setFieldsValue({ displayName: u.displayName, description: u.description ?? '' })
  }

  const onSaveEdit = async () => {
    const v = await editForm.validateFields()
    if (!editTarget) return
    try {
      await updateUser(editTarget.id, { displayName: v.displayName, description: v.description })
      message.success(`已保存 ${editTarget.username} 的资料`)
      setEditTarget(null)
      editForm.resetFields()
      reload()
    } catch (e) {
      showError(e, '保存失败')
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
      showError(e, '重置失败')
    }
  }

  const openRole = (u: AuthUser) => {
    setRoleTarget(u)
    setRoleValue(u.roleCode || u.role)
  }

  const onSaveRole = async () => {
    if (!roleTarget) return
    try {
      await updateUser(roleTarget.id, { role: roleValue })
      const label = roleOptions.find((o) => o.value === roleValue)?.label ?? roleValue
      message.success(`${roleTarget.username} 的角色已改为 ${label}`)
      setRoleTarget(null)
      reload()
    } catch (e) {
      showError(e, '修改失败')
    }
  }

  const toggleStatus = async (u: AuthUser) => {
    const next = u.status === 'DISABLED' ? 'ACTIVE' : 'DISABLED'
    try {
      await updateUser(u.id, { status: next })
      message.success(`${u.username} 已${next === 'ACTIVE' ? '启用' : '禁用'}`)
      reload()
    } catch (e) {
      showError(e, '操作失败')
    }
  }

  const confirmDelete = (u: AuthUser) => {
    Modal.confirm({
      title: `删除用户：${u.username}`,
      content: '删除后该账号不可恢复，其登录态（refresh token）全部失效。确认删除？',
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await deleteUser(u.id)
          message.success(`已删除用户 ${u.username}`)
          reload()
        } catch (e) {
          showError(e, '删除失败')
          throw e
        }
      },
    })
  }

  return (
    <>
      <Typography.Paragraph type="secondary">
        管理平台账号与角色分配。角色在「角色」页维护：基础权限决定接口读写能力（ADMIN 全权限 / DEVELOPER
        读写 / VIEWER 只读），菜单权限决定入口是否可见；local 为系统内置身份，不可修改。
      </Typography.Paragraph>
      <FitTable
        rowKey="id"
        loading={loading}
        dataSource={users}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: '暂无用户，点击右上角「新建用户」创建第一个账号' }}
        columns={[
          { title: '用户名', dataIndex: 'username' },
          { title: '显示名', dataIndex: 'displayName' },
          {
            title: '描述',
            dataIndex: 'description',
            ellipsis: true,
            render: (v?: string | null) => v || <Typography.Text type="secondary">—</Typography.Text>,
          },
          {
            title: '角色',
            dataIndex: 'role',
            render: (baseRole: string, u) => {
              const meta = baseRoleMeta(baseRole)
              const code = u.roleCode || baseRole
              return (
                <Space size={6}>
                  <Tooltip title={`基础权限：${meta.text}　角色编码：${code}`}>
                    <Tag color={meta.color}>{displayRole(u)}</Tag>
                  </Tooltip>
                  {code !== displayRole(u) && (
                    <Typography.Text type="secondary" code>
                      {code}
                    </Typography.Text>
                  )}
                </Space>
              )
            },
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
          { title: '创建时间', dataIndex: 'createdAt', width: 160, render: (v?: string) => fmtTime(v) },
          {
            title: '操作',
            width: 340,
            render: (_, u) => {
              const isLocal = u.username === 'local'
              const isSelf = u.username === me?.username
              return (
                <Space size={4}>
                  <Button size="small" disabled={isLocal} onClick={() => openEdit(u)}>
                    编辑
                  </Button>
                  <Button size="small" disabled={isLocal} onClick={() => openRole(u)}>
                    角色
                  </Button>
                  <Button size="small" disabled={isLocal} onClick={() => setResetTarget(u)}>
                    重置密码
                  </Button>
                  <Button size="small" disabled={isLocal || isSelf} onClick={() => toggleStatus(u)}>
                    {u.status === 'DISABLED' ? '启用' : '禁用'}
                  </Button>
                  <Button size="small" danger disabled={isLocal || isSelf} onClick={() => confirmDelete(u)}>
                    删除
                  </Button>
                </Space>
              )
            },
          },
        ]}
      />

      <Modal
        title={`修改角色：${roleTarget?.username ?? ''}`}
        open={!!roleTarget}
        onOk={onSaveRole}
        onCancel={() => setRoleTarget(null)}
        destroyOnHidden
      >
        <Select
          value={roleValue}
          style={{ width: '100%', marginTop: 8 }}
          onChange={setRoleValue}
          options={roleOptions}
          showSearch
          optionFilterProp="label"
        />
      </Modal>

      <Modal
        title={`编辑用户：${editTarget?.username ?? ''}`}
        open={!!editTarget}
        onOk={onSaveEdit}
        onCancel={() => setEditTarget(null)}
        destroyOnHidden
      >
        <Form form={editForm} layout="vertical">
          <Form.Item
            name="displayName"
            label="显示名"
            rules={[{ required: true, message: '请输入显示名' }, { max: 128 }]}
          >
            <Input placeholder="展示用名称" />
          </Form.Item>
          <Form.Item name="description" label="描述" rules={[{ max: 512 }]}>
            <Input.TextArea rows={3} placeholder="选填，如职责/备注；留空则清空" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="新建用户"
        open={createOpen}
        onOk={onCreate}
        onCancel={() => {
          onCreateOpenChange(false)
          createForm.resetFields()
        }}
        destroyOnHidden
      >
        <Form form={createForm} layout="vertical">
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input placeholder="登录账号" />
          </Form.Item>
          <Form.Item name="displayName" label="显示名">
            <Input placeholder="留空则同用户名" />
          </Form.Item>
          <Form.Item name="description" label="描述" rules={[{ max: 512 }]}>
            <Input.TextArea rows={2} placeholder="选填，如职责/备注" />
          </Form.Item>
          <Form.Item
            name="password"
            label="初始密码"
            rules={[{ required: true, min: 6, message: '至少 6 位' }]}
          >
            <Input.Password />
          </Form.Item>
          <Form.Item name="role" label="角色" initialValue="DEVELOPER" rules={[{ required: true }]}>
            <Select options={roleOptions} showSearch optionFilterProp="label" />
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
    </>
  )
}
