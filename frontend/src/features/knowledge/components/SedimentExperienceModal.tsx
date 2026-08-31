// 会话中「沉淀经验」入口：把本次会话学到的东西提交为知识提案（CAP-04 FR-05）。
import { useEffect } from 'react'
import { Form, Input, message, Modal, Select } from 'antd'
import { createProposal } from '../api'
import type { KnowledgeProposalInput } from '../types'

interface Props {
  open: boolean
  onClose: () => void
  sessionId?: string
  projectId?: string
  defaultTitle?: string
}

export default function SedimentExperienceModal({ open, onClose, sessionId, projectId, defaultTitle }: Props) {
  const [form] = Form.useForm<KnowledgeProposalInput>()

  useEffect(() => {
    if (open) {
      form.resetFields()
      form.setFieldsValue({
        title: defaultTitle ?? '',
        targetScope: projectId ? 'project' : 'global',
        targetProjectId: projectId,
        sourceSessionId: sessionId,
      })
    }
  }, [open, defaultTitle, projectId, sessionId, form])

  const onOk = async () => {
    const v = await form.validateFields()
    try {
      await createProposal({
        title: v.title,
        contentMd: v.contentMd,
        targetScope: v.targetScope ?? 'project',
        targetProjectId: v.targetProjectId,
        sourceSessionId: sessionId,
      })
      message.success('经验已提交为提案，等待审核')
      onClose()
    } catch (e) {
      message.error(`提交失败：${(e as Error).message}`)
    }
  }

  return (
    <Modal
      title="沉淀经验"
      open={open}
      onCancel={onClose}
      onOk={onOk}
      okText="提交提案"
      cancelText="取消"
      width={560}
    >
      <Form form={form} labelCol={{ span: 5 }} wrapperCol={{ span: 18 }}>
        <Form.Item name="title" label="标题" rules={[{ required: true, message: '请填写标题' }]}>
          <Input placeholder="如：Maven 多模块增量编译踩坑记录" />
        </Form.Item>
        <Form.Item name="targetScope" label="去向" rules={[{ required: true }]}>
          <Select
            options={[
              { value: 'project', label: '项目经验（仅本项目注入）' },
              { value: 'global', label: '全局经验（所有项目可注入）' },
            ]}
          />
        </Form.Item>
        <Form.Item noStyle shouldUpdate>
          {({ getFieldValue }) =>
            getFieldValue('targetScope') === 'project' && (
              <Form.Item name="targetProjectId" label="目标项目" rules={[{ required: true, message: '请选择项目' }]}>
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="选择项目"
                  options={projectId ? [{ value: projectId, label: `${projectId}（当前）` }] : []}
                />
              </Form.Item>
            )
          }
        </Form.Item>
        <Form.Item name="contentMd" label="内容" rules={[{ required: true, message: '请填写经验内容' }]}>
          <Input.TextArea rows={7} placeholder="本次学到的经验（Markdown），审核采纳后进入知识库自动注入" />
        </Form.Item>
      </Form>
    </Modal>
  )
}
