import { Button, Form, Input, Modal, Select, Space, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useState } from 'react'
import { createRole, deleteRole, listRoles, updateRole } from '../api'
import type { RoleView } from '../types'
import { baseRoleMeta, CUSTOM_BASE_ROLE_OPTIONS } from '../roleMeta'
import RoleMenuDrawer from './RoleMenuDrawer'
import { usePermissions } from '../usePermissions'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { fmtTime } from '../../../shared/utils/format'

interface RolesPanelProps {
  createOpen: boolean
  onCreateOpenChange: (open: boolean) => void
  refreshTick: number
}

interface RoleFormValues {
  code: string
  name: string
  description?: string
  baseRole: string
}

/** 用户管理 → 角色：角色 = 基础接口权限 + 菜单可见范围；内置角色不可编辑/删除。 */
export default function RolesPanel({ createOpen, onCreateOpenChange, refreshTick }: RolesPanelProps) {
  const [roles, setRoles] = useState<RoleView[]>([])
  const [loading, setLoading] = useState(false)
  const [editTarget, setEditTarget] = useState<RoleView | null>(null)
  const [permTarget, setPermTarget] = useState<RoleView | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<RoleFormValues>()
  const { canAction } = usePermissions()
  const canManage = canAction('admin.users.action.manage-role')
  const canGrantMenu = canAction('admin.users.action.grant-menu')

  const reload = useCallback(() => {
    setLoading(true)
    listRoles()
      .then(setRoles)
      .catch((e) => showError(e, '加载角色失败'))
      .finally(() => setLoading(false))
  }, [])

  useEffect(reload, [reload, refreshTick])

  useEffect(() => {
    if (createOpen) {
      form.setFieldsValue({ code: '', name: '', description: '', baseRole: 'DEVELOPER' })
    }
  }, [createOpen, form])

  const closeForm = () => {
    onCreateOpenChange(false)
    setEditTarget(null)
    form.resetFields()
  }

  const openEdit = (role: RoleView) => {
    setEditTarget(role)
    form.setFieldsValue({
      code: role.code,
      name: role.name,
      description: role.description ?? '',
      baseRole: role.baseRole,
    })
  }

  const onSubmit = async () => {
    const v = await form.validateFields()
    setSaving(true)
    try {
      if (editTarget) {
        await updateRole(editTarget.code, {
          name: v.name,
          description: v.description ?? '',
          baseRole: v.baseRole,
        })
        message.success(`角色「${v.name}」已保存`)
      } else {
        await createRole({
          code: v.code,
          name: v.name,
          description: v.description,
          baseRole: v.baseRole,
        })
        message.success(`角色「${v.name}」已创建，默认授权工作台与项目菜单`)
      }
      closeForm()
      reload()
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const confirmDelete = (role: RoleView) => {
    Modal.confirm({
      title: `删除角色：${role.name}（${role.code}）`,
      content:
        role.userCount > 0
          ? `该角色仍有 ${role.userCount} 个用户使用，请先在「用户」页调整这些用户的角色。`
          : '删除后该角色的菜单授权一并清除，且不可恢复。确认删除？',
      okText: '删除',
      okButtonProps: { danger: true, disabled: role.userCount > 0 },
      onOk: async () => {
        try {
          await deleteRole(role.code)
          message.success(`已删除角色 ${role.code}`)
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
        角色 = 基础接口权限 + 菜单/页签/按钮权限。ADMIN 为内置管理员角色，恒见全部权限且不可改；自定义角色可选「读写」或「只读」基础权限，并在「权限配置」里勾选可见菜单、页签与操作按钮。仍有用户使用的角色不可删除。
      </Typography.Paragraph>
      <FitTable
        rowKey="code"
        loading={loading}
        dataSource={roles}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: '暂无角色' }}
        columns={[
          {
            title: '角色',
            dataIndex: 'name',
            render: (_: string, role) => (
              <Space size={6}>
                <span>{role.name}</span>
                <Typography.Text type="secondary" code>
                  {role.code}
                </Typography.Text>
                {role.builtin && <Tag color="gold">内置</Tag>}
              </Space>
            ),
          },
          {
            title: '基础权限',
            dataIndex: 'baseRole',
            width: 200,
            render: (baseRole: string) => {
              const meta = baseRoleMeta(baseRole)
              return <Tag color={meta.color}>{meta.text}</Tag>
            },
          },
          {
            title: '说明',
            dataIndex: 'description',
            ellipsis: true,
            render: (v?: string | null) => v || <Typography.Text type="secondary">—</Typography.Text>,
          },
          { title: '用户数', dataIndex: 'userCount', width: 90, align: 'center' },
          {
            title: '权限范围',
            dataIndex: 'menuKeys',
            width: 140,
            render: (keys: string[], role) =>
              role.code === 'ADMIN' ? (
                <Tag color="green">全部权限</Tag>
              ) : (
                <Typography.Text>{keys.length} 项</Typography.Text>
              ),
          },
          { title: '更新时间', dataIndex: 'updatedAt', width: 160, render: (v?: string) => fmtTime(v) },
          ...(canManage || canGrantMenu
            ? [
                {
                  title: '操作',
                  width: 260,
                  render: (_: unknown, role: RoleView) => (
                    <Space size={4}>
                      {canGrantMenu && (
                        <Button
                          size="small"
                          disabled={role.code === 'ADMIN'}
                          onClick={() => setPermTarget(role)}
                        >
                          权限配置
                        </Button>
                      )}
                      {canManage && (
                        <Button size="small" disabled={role.builtin} onClick={() => openEdit(role)}>
                          编辑
                        </Button>
                      )}
                      {canManage && (
                        <Button size="small" danger disabled={role.builtin} onClick={() => confirmDelete(role)}>
                          删除
                        </Button>
                      )}
                    </Space>
                  ),
                } satisfies ColumnsType<RoleView>[number],
              ]
            : []),
        ]}
      />

      <Modal
        title={editTarget ? `编辑角色：${editTarget.name}` : '新建角色'}
        open={createOpen || !!editTarget}
        onOk={onSubmit}
        confirmLoading={saving}
        onCancel={closeForm}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="code"
            label="角色编码"
            normalize={(v: string) => (v ?? '').toUpperCase()}
            rules={[
              { required: true, message: '请输入角色编码' },
              {
                pattern: /^[A-Z][A-Z0-9_]{1,15}$/,
                message: '2~16 位大写字母/数字/下划线，且以字母开头（如 QA_LEAD）',
              },
            ]}
          >
            <Input disabled={!!editTarget} placeholder="如 QA_LEAD；创建后不可修改" />
          </Form.Item>
          <Form.Item
            name="name"
            label="角色名称"
            rules={[{ required: true, message: '请输入角色名称' }, { max: 64 }]}
          >
            <Input placeholder="展示用名称，如 测试负责人" />
          </Form.Item>
          <Form.Item name="description" label="说明" rules={[{ max: 512 }]}>
            <Input.TextArea rows={2} placeholder="选填，如职责范围/使用说明" />
          </Form.Item>
          <Form.Item
            name="baseRole"
            label="基础权限"
            rules={[{ required: true, message: '请选择基础权限' }]}
            extra="决定后端接口读写能力；变更会同步该角色下所有用户。ADMIN 仅保留给内置管理员角色。"
          >
            <Select options={CUSTOM_BASE_ROLE_OPTIONS} />
          </Form.Item>
        </Form>
      </Modal>

      <RoleMenuDrawer
        role={permTarget}
        onClose={() => setPermTarget(null)}
        onSaved={reload}
      />
    </>
  )
}
