import { Form, Input, Modal, Typography } from 'antd'
import { useEffect } from 'react'
import type { BookmarkGroup } from '../types'

interface Props {
  open: boolean
  /** null = 新建（parent 为 null 时建根分组） */
  editing: BookmarkGroup | null
  parent: BookmarkGroup | null
  saving: boolean
  onClose: () => void
  onOk: (name: string) => void
}

/** FR-02 分组新建/重命名（分组层级建议两级内使用，父级在侧栏菜单里选，不在此表单里改） */
export default function GroupModal({ open, editing, parent, saving, onClose, onOk }: Props) {
  const [form] = Form.useForm<{ name: string }>()

  useEffect(() => {
    if (open) form.setFieldsValue({ name: editing?.name ?? '' })
  }, [open, editing, form])

  return (
    <Modal
      title={editing ? `重命名分组：${editing.name}` : parent ? `在「${parent.name}」下新建分组` : '新建分组'}
      open={open}
      confirmLoading={saving}
      onCancel={onClose}
      destroyOnHidden
      onOk={async () => {
        const v = await form.validateFields()
        onOk(v.name.trim())
      }}
    >
      <Form form={form} layout="vertical">
        <Form.Item name="name" label="分组名" rules={[{ required: true, message: '请输入分组名' }]}>
          <Input placeholder="如：环境 / 运维 / 团队入口" maxLength={128} />
        </Form.Item>
      </Form>
      {editing && (
        <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
          改名不影响组内收藏与已发出的分享。
        </Typography.Paragraph>
      )}
    </Modal>
  )
}
