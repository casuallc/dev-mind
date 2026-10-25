// CAP-86 知识库新建/编辑弹窗：工作台左栏「新建」与库设置页共用。
import { useEffect } from 'react'
import { Form, Input, message, Modal, Select } from 'antd'
import type { Project } from '../../projects/types'
import { createBase, updateBase } from '../api'
import type { KnowledgeBase, KnowledgeBaseInput } from '../types'
import { showError } from '../../../shared/utils/showError'

export default function KnowledgeBaseFormModal({
  open,
  base,
  projects,
  onClose,
  onSaved,
}: {
  open: boolean
  /** 传入 = 编辑；不传 = 新建 */
  base?: KnowledgeBase | null
  projects: Project[]
  onClose: () => void
  onSaved: (base: KnowledgeBase) => void
}) {
  const [form] = Form.useForm<KnowledgeBaseInput>()
  const scope = Form.useWatch('scope', form)

  useEffect(() => {
    if (!open) return
    form.resetFields()
    if (base) {
      form.setFieldsValue({
        name: base.name,
        description: base.description ?? undefined,
        scope: base.scope,
        projectId: base.projectId ?? undefined,
        injectMode: base.injectMode,
        status: base.status,
      })
    } else {
      form.setFieldsValue({ scope: 'global', injectMode: 'RAG', status: 'active' })
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
      content: '保存后立即生效；归档的库会退出检索与会话注入。',
      okText: '保存',
      cancelText: '取消',
      onOk: () => commit(values),
    })
  }

  const projectOptions = projects.map((p) => ({ value: p.id, label: `${p.name} (${p.id})` }))

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
        <Form.Item name="scope" label="范围" rules={[{ required: true }]}>
          <Select
            disabled={!!base}
            options={[
              { value: 'global', label: '全局（所有会话可用）' },
              { value: 'project', label: '项目（仅指定项目）' },
            ]}
          />
        </Form.Item>
        {scope === 'project' && (
          <Form.Item name="projectId" label="项目" rules={[{ required: true, message: '请选择项目' }]}>
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="选择项目"
              disabled={!!base}
              options={projectOptions}
            />
          </Form.Item>
        )}
        <Form.Item
          name="injectMode"
          label="注入方式"
          rules={[{ required: true }]}
          extra="全量注入：会话启动时注入整库（适合经验/规范库）；RAG：按提问检索并召回相关片段（适合文档量大）"
        >
          <Select
            options={[
              { value: 'RAG', label: 'RAG 按需检索（推荐）' },
              { value: 'FULL', label: '全量注入（适合经验库）' },
            ]}
          />
        </Form.Item>
        {base && (
          <Form.Item name="status" label="状态">
            <Select
              options={[
                { value: 'active', label: '启用' },
                { value: 'archived', label: '归档停用（退出检索与注入）' },
              ]}
            />
          </Form.Item>
        )}
      </Form>
    </Modal>
  )
}
