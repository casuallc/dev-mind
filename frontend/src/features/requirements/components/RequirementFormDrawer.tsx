// 需求新建/编辑弹窗：项目列表卡（新建）与需求详情页（编辑）复用。
// Jira 来源（source=JIRA）：托管字段（标题/类型/描述/优先级/标签/经办人/报告人/截止日期/修复版本）
// 由同步维护、本地禁用，仅本地负责人可改（服务端同样强制忽略）。
import { useEffect } from 'react'
import { DatePicker, Form, Input, Modal, Select, message } from 'antd'
import dayjs from 'dayjs'
import { createRequirement, updateRequirement } from '../api'
import type { Requirement, RequirementInput } from '../types'
import { ALL_PRIORITIES, ALL_TYPES, TYPE_LABEL } from './requirementMeta'

/** 表单值形态：dueDate 在表单里是 dayjs，提交时转 'YYYY-MM-DD' */
type FormValues = Omit<RequirementInput, 'dueDate'> & { dueDate?: dayjs.Dayjs | null }

const JIRA_MANAGED_TIP = 'Jira 来源字段由同步维护，本地只读'

export default function RequirementFormModal({ projectId, editing, open, onClose, onSaved }: {
  projectId: string
  editing: Requirement | null
  open: boolean
  onClose: () => void
  onSaved: (saved: Requirement) => void
}) {
  const [form] = Form.useForm<FormValues>()
  const jiraManaged = editing?.source === 'JIRA'

  useEffect(() => {
    if (open) {
      if (editing) {
        form.setFieldsValue({
          ...editing,
          dueDate: editing.dueDate ? dayjs(editing.dueDate) : null,
        })
      } else {
        form.setFieldsValue({
          title: '', description: '', ownerId: '', type: 'FEATURE',
          priority: undefined, assignee: '', reporter: '', labels: [], fixVersions: [], dueDate: null,
        })
      }
    }
  }, [open, editing, form])

  const onSave = async (v: FormValues) => {
    const input: RequirementInput = {
      ...v,
      dueDate: v.dueDate ? v.dueDate.format('YYYY-MM-DD') : undefined,
    }
    try {
      const saved = editing
        ? await updateRequirement(projectId, editing.id, input)
        : await createRequirement(projectId, input)
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
        <Form.Item label="标题" name="title" rules={[{ required: true, message: '请输入标题' }]}
          tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <Input placeholder="如 用户登录支持扫码" disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="类型" name="type" rules={[{ required: true, message: '请选择类型' }]}
          tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <Select options={ALL_TYPES.map((t) => ({ value: t, label: TYPE_LABEL[t] }))} disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="描述（业务目标）" name="description"
          tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <Input.TextArea rows={3} disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="优先级" name="priority" tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <Select allowClear options={ALL_PRIORITIES.map((p) => ({ value: p, label: p }))} disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="标签" name="labels" tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          {/* 不设 tokenSeparators：标签按整串存储（后端逗号拼接），防止逗号被拆 */}
          <Select mode="tags" open={false} placeholder="回车添加" disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="经办人" name="assignee" tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <Input placeholder="可选" disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="报告人" name="reporter" tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <Input placeholder="可选" disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="截止日期" name="dueDate" tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <DatePicker style={{ width: '100%' }} disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="修复版本" name="fixVersions" tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <Select mode="tags" open={false} placeholder="回车添加，如 1.0" disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="本地负责人" name="ownerId"
          tooltip={jiraManaged ? '平台侧流程负责人，与 Jira 经办人相互独立，本地可改' : undefined}>
          <Input placeholder="可选" />
        </Form.Item>
      </Form>
    </Modal>
  )
}
