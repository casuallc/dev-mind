// 需求新建/编辑弹窗：项目列表卡（新建）与需求详情页（编辑）复用。
import { useEffect } from 'react'
import { Form, Input, Modal, Select, message } from 'antd'
import { createRequirement, updateRequirement } from '../api'
import type { Requirement, RequirementInput } from '../types'
import { ALL_TYPES, TYPE_LABEL } from './requirementMeta'

export default function RequirementFormModal({ projectId, editing, open, onClose, onSaved }: {
  projectId: string
  editing: Requirement | null
  open: boolean
  onClose: () => void
  onSaved: (saved: Requirement) => void
}) {
  const [form] = Form.useForm<RequirementInput>()

  useEffect(() => {
    if (open) {
      form.setFieldsValue(editing ?? { title: '', description: '', ownerId: '', type: 'FEATURE' })
    }
  }, [open, editing, form])

  const onSave = async (v: RequirementInput) => {
    try {
      const saved = editing
        ? await updateRequirement(projectId, editing.id, v)
        : await createRequirement(projectId, v)
      message.success('已保存')
      onClose()
      onSaved(saved)
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  return (
    <Modal
      title={editing ? `编辑需求 ${editing.code}` : '新建需求'}
      open={open}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText="保存"
      width={560}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" onFinish={onSave} preserve={false}>
        <Form.Item label="标题" name="title" rules={[{ required: true, message: '请输入标题' }]}>
          <Input placeholder="如 用户登录支持扫码" />
        </Form.Item>
        <Form.Item label="类型" name="type" rules={[{ required: true, message: '请选择类型' }]}>
          <Select options={ALL_TYPES.map((t) => ({ value: t, label: TYPE_LABEL[t] }))} />
        </Form.Item>
        <Form.Item label="描述（业务目标）" name="description">
          <Input.TextArea rows={3} />
        </Form.Item>
        <Form.Item label="负责人" name="ownerId">
          <Input placeholder="可选" />
        </Form.Item>
      </Form>
    </Modal>
  )
}
