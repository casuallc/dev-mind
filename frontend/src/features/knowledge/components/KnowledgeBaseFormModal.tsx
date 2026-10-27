// CAP-86 知识库新建/编辑弹窗：工作台左栏「新建」与库设置页共用。
// CAP-94 起库为独立一等资产：不再有范围/所属项目维度。
import { useEffect } from 'react'
import { Form, Input, message, Modal, Select } from 'antd'
import { createBase, updateBase } from '../api'
import type { KnowledgeBase, KnowledgeBaseInput } from '../types'
import { showError } from '../../../shared/utils/showError'

export default function KnowledgeBaseFormModal({
  open,
  base,
  onClose,
  onSaved,
}: {
  open: boolean
  /** 传入 = 编辑；不传 = 新建 */
  base?: KnowledgeBase | null
  onClose: () => void
  onSaved: (base: KnowledgeBase) => void
}) {
  const [form] = Form.useForm<KnowledgeBaseInput>()

  useEffect(() => {
    if (!open) return
    form.resetFields()
    if (base) {
      form.setFieldsValue({
        name: base.name,
        description: base.description ?? undefined,
        status: base.status,
      })
    } else {
      form.setFieldsValue({ status: 'active' })
    }
  }, [open, base, form])

  const commit = async (values: KnowledgeBaseInput) => {
    try {
      const saved = base
        ? await updateBase(base.id, values)
        : await createBase(values)
      message.success(base ? '知识库已更新' : '知识库已创建')
      onSaved(saved)
      onClose()
    } catch (e) {
      showError(e, '保存失败')
    }
  }

  const onSave = async () => {
    const values = await form.validateFields()
    if (!base) {
      await commit(values)
      return
    }
    Modal.confirm({
      centered: true,
      title: `保存知识库「${base.name}」的修改？`,
      content: '保存后立即生效；归档的库不再参与检索。',
      okText: '保存',
      cancelText: '取消',
      onOk: () => commit(values),
    })
  }

  return (
    <Modal
      title={base ? '编辑知识库' : '新建知识库'}
      open={open}
      onCancel={onClose}
      onOk={onSave}
      okText="保存"
      cancelText="取消"
    >
      <Form form={form} labelCol={{ span: 5 }} wrapperCol={{ span: 18 }}>
        <Form.Item name="name" label="名称" rules={[{ required: true, message: '请填写名称' }]}>
          <Input placeholder="如：运维手册库" />
        </Form.Item>
        <Form.Item name="description" label="描述">
          <Input placeholder="这个库装什么？（可选）" />
        </Form.Item>
        {base && (
          <Form.Item name="status" label="状态">
            <Select
              options={[
                { value: 'active', label: '启用' },
                { value: 'archived', label: '归档停用（退出检索）' },
              ]}
            />
          </Form.Item>
        )}
      </Form>
    </Modal>
  )
}
