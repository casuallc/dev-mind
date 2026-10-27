// 会话中「沉淀经验」入口：把本次会话学到的东西提交为知识提案（CAP-04 FR-05）。
// CAP-94：提案不再有「去向」——只记录内容与溯源（来源会话/来源项目），采纳时由审核人显式选目标库。
import { useEffect } from 'react'
import { Form, Input, message, Modal } from 'antd'
import { createProposal } from '../api'
import type { KnowledgeProposalInput } from '../types'
import { showError } from '../../../shared/utils/showError'

interface Props {
  open: boolean
  onClose: () => void
  sessionId?: string
  /** 来源项目（纯溯源标注，随提案记录） */
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
        sourceSessionId: sessionId,
      })
    }
  }, [open, defaultTitle, sessionId, form])

  const onOk = async () => {
    const v = await form.validateFields()
    try {
      await createProposal({
        title: v.title,
        contentMd: v.contentMd,
        sourceSessionId: sessionId,
        sourceProject: projectId,
      })
      message.success('经验已提交为提案，等待审核')
      onClose()
    } catch (e) {
      showError(e, '提交失败')
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
        <Form.Item name="contentMd" label="内容" rules={[{ required: true, message: '请填写经验内容' }]}>
          <Input.TextArea
            rows={7}
            placeholder="本次学到的经验（Markdown）；审核采纳时选择目标知识库，之后供检索命中"
          />
        </Form.Item>
      </Form>
    </Modal>
  )
}
